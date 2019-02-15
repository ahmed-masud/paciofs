/*
 * Copyright (c) 2018, Zuse Institute Berlin.
 *
 * Licensed under the New BSD License, see LICENSE file for details.
 *
 */

package de.zib.paciofs;

import akka.actor.ActorSystem;
import akka.cluster.Cluster;
import akka.event.Logging;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.UseHttp2;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.Function;
import akka.management.AkkaManagement;
import akka.management.cluster.bootstrap.ClusterBootstrap;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.TLSClientAuth;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import de.zib.paciofs.blockchain.Bitcoind;
import de.zib.paciofs.io.posix.grpc.PosixIoServiceHandlerFactory;
import de.zib.paciofs.io.posix.grpc.PosixIoServiceImpl;
import de.zib.paciofs.logging.LogbackPropertyDefiners;
import de.zib.paciofs.logging.Markers;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacioFs {
  private static final String OPTION_CONFIG = "config";
  private static final String OPTION_CONFIG_SHORT = "c";
  private static final String OPTION_HELP = "help";
  private static final String OPTION_HELP_SHORT = "h";
  private static final String OPTION_SKIP_BOOTSTRAP = "skip-bootstrap";
  private static final String OPTION_SKIP_BOOTSTRAP_SHORT = "s";

  private static Logger log;

  private PacioFs() {}

  /**
   * Starts PacioFs and waits for shutdown.
   * @param args the command line arguments
   */
  public static void main(String[] args) {
    final CommandLine cmd = parseCommandLine(args);
    final boolean skipBootstrap = cmd.hasOption(OPTION_SKIP_BOOTSTRAP);

    // parses application.conf from the classpath
    // exclude bootstrapping configuration if requested (e.g. if we are not
    // running in kubernetes)
    final Config applicationConfig = skipBootstrap
        ? ConfigFactory.load().withoutPath("akka.management.cluster.bootstrap")
        : ConfigFactory.load();

    // if the user has supplied a configuration, use the default configuration only as a fallback
    // for missing options (i.e. the user configuration wins)
    final Config config;
    if (cmd.hasOption(OPTION_CONFIG)) {
      config = ConfigFactory.parseFile(new File(cmd.getOptionValue(OPTION_CONFIG)))
                   .withFallback(applicationConfig);
    } else {
      config = applicationConfig;
    }

    // no logging is allowed to happen before here
    initializeLogging(config);

    // the entire Akka configuration is a bit overwhelming
    log.debug(Markers.CONFIGURATION, "Using configuration: {}", config);

    // create the actor system
    final ActorSystem paciofs = ActorSystem.create("paciofs", config);

    // reset Akka logging if necessary
    initializeAkkaLogging(paciofs);

    // again, skip bootstrapping if requested
    if (!skipBootstrap) {
      // hosts HTTP routes used by bootstrap
      AkkaManagement.get(paciofs).start();

      // starts dynamic bootstrapping
      ClusterBootstrap.get(paciofs).start();
    }

    final Cluster cluster = Cluster.get(paciofs);
    log.info("Started [{}], cluster.selfAddress = {}", paciofs, cluster.selfAddress());

    // actor for the blockchain
    paciofs.actorOf(Bitcoind.props(), "bitcoind");

    // get ready to serve the I/O service
    final Http http = Http.get(paciofs);
    final Materializer mat = ActorMaterializer.create(paciofs);
    final Function<HttpRequest, CompletionStage<HttpResponse>> posixIoHandler =
        PosixIoServiceHandlerFactory.create(new PosixIoServiceImpl(), mat);

    // set up HTTP if desired
    try {
      http.bindAndHandleAsync(posixIoHandler,
              ConnectHttp.toHost(config.getString(PacioFsOptions.HTTP_BIND_HOSTNAME_KEY),
                  config.getInt(PacioFsOptions.HTTP_BIND_PORT_KEY), UseHttp2.always()),
              mat)
          .thenAccept(binding
              -> log.info("{} gRPC HTTP server bound to: {}",
                  PosixIoServiceImpl.class.getSimpleName(), binding.localAddress()));
    } catch (ConfigException.Missing | ConfigException.WrongType e) {
      log.info("No valid HTTP configuration found, not serving HTTP ({})", e.getMessage());
    }

    // set up HTTPS if desired
    try {
      final HttpsConnectionContext https = httpsConnectionContext(config);
      http.bindAndHandleAsync(posixIoHandler,
              ConnectHttp
                  .toHostHttps(config.getString(PacioFsOptions.HTTPS_BIND_HOSTNAME_KEY),
                      config.getInt(PacioFsOptions.HTTPS_BIND_PORT_KEY))
                  .withCustomHttpsContext(https),
              mat)
          .thenAccept(binding
              -> log.info("{} gRPC HTTPS server bound to: {}",
                  PosixIoServiceImpl.class.getSimpleName(), binding.localAddress()));
    } catch (ConfigException.Missing | ConfigException.WrongType e) {
      log.info("No valid HTTPS configuration found, not serving HTTPS ({})", e.getMessage());
    } catch (GeneralSecurityException | IOException e) {
      log.error("{}: not serving HTTPS", e.getMessage());
      log.error(Markers.EXCEPTION, e.getMessage(), e);
    }
  }

  /* Utility functions */

  private static HttpsConnectionContext httpsConnectionContext(Config config)
      throws GeneralSecurityException, IOException {
    final String algorithm = "SunX509";
    final String protocol = "TLS";
    final String type = "PKCS12";

    // initialize key manager (contains our certificates)
    final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(algorithm);
    char[] password =
        readPasswordFromFile(config.getString(PacioFsOptions.HTTPS_SERVER_CERT_PASS_PATH_KEY));
    keyManagerFactory.init(
        readKeyStoreFromFile(
            config.getString(PacioFsOptions.HTTPS_SERVER_CERT_PATH_KEY), password, type),
        password);

    // get the context
    final SSLContext sslContext = SSLContext.getInstance(protocol);

    try {
      // initialize trust manager (contains trusted certificates)
      final TrustManagerFactory trustManagerFactory;
      password = readPasswordFromFile(config.getString(PacioFsOptions.HTTPS_CA_CERT_PASS_PATH_KEY));
      trustManagerFactory = TrustManagerFactory.getInstance(algorithm);
      trustManagerFactory.init(readKeyStoreFromFile(
          config.getString(PacioFsOptions.HTTPS_CA_CERT_PATH_KEY), password, type));

      sslContext.init(
          keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
    } catch (ConfigException.Missing e) {
      // tolerate missing trust manager configuration
      log.info("Using system trust managers because {} and/or {} were not set",
          PacioFsOptions.HTTPS_CA_CERT_PATH_KEY, PacioFsOptions.HTTPS_CA_CERT_PASS_PATH_KEY);

      sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
    }

    // build the context, requiring mutual authentication
    return ConnectionContext.https(sslContext, Optional.empty(), Optional.empty(),
        Optional.of(TLSClientAuth.need()), Optional.empty());
  }

  private static void initializeAkkaLogging(ActorSystem system) {
    final Config config = system.settings().config();

    // Akka loggers get started with the default application.conf, so reset the log level in case
    // the user has changed it
    try {
      system.eventStream().setLogLevel(
          Logging.levelFor(config.getString(AkkaOptions.LOG_LEVEL_KEY)).get().asInt());
    } catch (ConfigException.Missing e) {
      // the user has not specified a custom log level
    } catch (NoSuchElementException e) {
      log.warn("Invalid Akka log level: {}", config.getString(AkkaOptions.LOG_LEVEL_KEY));
    }
  }

  private static void initializeLogging(Config config) {
    // supply our configuration to Logback
    LogbackPropertyDefiners.ConfigVarWithDefaultValue.setConfig(config);

    // now trigger initialization of Logback
    log = LoggerFactory.getLogger(PacioFs.class);
  }

  private static CommandLine parseCommandLine(String[] args) {
    final Options options = new Options();
    options.addOption(OPTION_HELP_SHORT, OPTION_HELP, false, "print this message and exit");

    options.addOption(OPTION_CONFIG_SHORT, OPTION_CONFIG, true, "path/to/paciofs.conf");
    options.addOption(OPTION_SKIP_BOOTSTRAP_SHORT, OPTION_SKIP_BOOTSTRAP, false,
        "whether to skip bootstrapping (e.g. when outside kubernetes)");

    final CommandLineParser parser = new DefaultParser();
    CommandLine cmd = null;

    // 0 on help option, 1 on error, -1 otherwise
    int exitCode;
    try {
      cmd = parser.parse(options, args, false);

      // exit on unrecognized arguments or help option
      final List<String> argList = cmd.getArgList();
      if (argList.size() > 0) {
        System.err.println("Unrecognized argument(s): " + String.join(" ", argList));
        exitCode = 1;
      } else {
        exitCode = cmd.hasOption(OPTION_HELP) ? 0 : -1;
      }
    } catch (ParseException e) {
      // exit on parsing error
      System.err.println(e.getMessage());
      exitCode = 1;
    }

    if (exitCode >= 0) {
      final HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("PacioFs", options);
      System.exit(exitCode);
    }

    return cmd;
  }

  private static KeyStore readKeyStoreFromFile(String path, char[] password, String type)
      throws GeneralSecurityException, IOException {
    final KeyStore keyStore = KeyStore.getInstance(type);
    try (InputStream p12 = new FileInputStream(path)) {
      keyStore.load(p12, password);
    }

    return keyStore;
  }

  private static char[] readPasswordFromFile(String path) throws IOException {
    final char[] password;
    try (BufferedReader passReader = new BufferedReader(
             new InputStreamReader(new FileInputStream(path), Charset.forName("UTF-8")))) {
      final String pass = passReader.readLine();
      if (pass == null) {
        throw new IllegalArgumentException(path + " is empty");
      }

      password = new char[pass.length()];
      pass.getChars(0, pass.length(), password, 0);
    }

    return password;
  }
}