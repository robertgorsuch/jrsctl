package com.jaspersoft.jrsctl.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One-line descriptions of the configuration keys for {@code jrsctl config keys} (#70). Invariants:
 * every key the schema knows has a description ({@code ConfigCommandTest} fails otherwise); the
 * text says what the key is for in an operator's words, not its type, which the schema already
 * checks.
 */
final class ConfigKeys {

  private static final Pattern DOT = Pattern.compile("\\.");

  private static final Map<String, String> DESCRIPTIONS =
      Map.ofEntries(
          Map.entry(
              "server.baseUrl", "Address of the server, e.g. http://host:8080/jasperserver-pro"),
          Map.entry(
              "server.webappName", "jasperserver-pro (Commercial) or jasperserver (Community)"),
          Map.entry("server.installDir", "Directory JasperReports Server was installed in"),
          Map.entry("server.tomcatDir", "Tomcat directory that runs the server"),
          Map.entry(
              "server.buildomaticDir",
              "Installed buildomatic directory, when not under installDir"),
          Map.entry("server.runAsUser", "Account the server runs as (finds its keystore)"),
          Map.entry("server.auth.mode", "How jrsctl logs in: basic, form or token"),
          Map.entry(
              "server.auth.username", "Admin user jrsctl logs in as (superuser on Commercial)"),
          Map.entry("server.auth.passwordRef", "Admin password: enc:NAME, env:NAME or file:/path"),
          Map.entry("server.auth.tokenLocation", "Token login: send the token as query or header"),
          Map.entry(
              "service.kind",
              "How the server is started: windows-service, systemd, ctlscript, catalina or manual"),
          Map.entry("service.name", "Windows service or systemd unit name"),
          Map.entry("service.scriptPath", "Start/stop script for ctlscript or catalina"),
          Map.entry("service.stopTimeoutSeconds", "Seconds to wait for the server to stop"),
          Map.entry(
              "service.forceStopAfterSeconds",
              "Seconds before a catalina/ctlscript Tomcat that will not stop is ended"),
          Map.entry(
              "database.type", "Repository database: postgresql, mysql, oracle, mssql or db2"),
          Map.entry("database.url", "JDBC URL of the repository database"),
          Map.entry("database.username", "Repository database user"),
          Map.entry(
              "database.passwordRef",
              "Repository database password: enc:NAME, env:NAME or file:/path"),
          Map.entry("database.driverDir", "Directory holding the JDBC driver jar"),
          Map.entry("vendor.javaHome", "JDK buildomatic runs with (not the Java jrsctl runs on)"),
          Map.entry(
              "network.mode", "isolated: only server.baseUrl is contacted; public: proxy allowed"),
          Map.entry("network.proxy.host", "HTTP proxy host (network.mode public)"),
          Map.entry("network.proxy.port", "HTTP proxy port"),
          Map.entry("network.proxy.username", "Proxy user"),
          Map.entry(
              "network.proxy.passwordRef", "Proxy password: enc:NAME, env:NAME or file:/path"),
          Map.entry("network.proxy.noProxy", "Hosts that bypass the proxy, comma-separated"),
          Map.entry("network.trustStore.path", "Extra truststore with the server's CA certificate"),
          Map.entry(
              "network.trustStore.passwordRef",
              "Truststore password: enc:NAME, env:NAME or file:/path"),
          Map.entry("backups.retentionDays", "Days backups are kept; 0 turns off pruning by age"),
          Map.entry("backups.maxSnapshots", "Most backups kept (protected ones are never removed)"),
          Map.entry("smoke.reportUri", "Report smoke runs to check the server"));

  private ConfigKeys() {}

  /** What a path-valued key must point at when it is written (field test 2, G9). */
  enum PathKind {
    NONE,
    DIRECTORY,
    FILE
  }

  private static final java.util.Set<String> DIRECTORY_KEYS =
      java.util.Set.of(
          "server.installDir",
          "server.tomcatDir",
          "server.buildomaticDir",
          "vendor.javaHome",
          "database.driverDir");
  private static final java.util.Set<String> FILE_KEYS =
      java.util.Set.of("service.scriptPath", "network.trustStore.path");

  static PathKind pathKind(String key) {
    if (DIRECTORY_KEYS.contains(key)) {
      return PathKind.DIRECTORY;
    }
    if (FILE_KEYS.contains(key)) {
      return PathKind.FILE;
    }
    return PathKind.NONE;
  }

  /** The description of {@code key}, or a note that it has none. */
  static String description(String key) {
    return DESCRIPTIONS.getOrDefault(key, "(no description)");
  }

  /** True for keys that hold a secret reference. */
  static boolean isSecret(String key) {
    return key.endsWith("passwordRef");
  }

  /** The value of {@code key} in {@code config} as it would be written, or empty when absent. */
  static String value(Config config, String key) {
    JsonNode node = ConfigWriter.toTree(config);
    for (String segment : DOT.splitAsStream(key).toList()) {
      node = node.path(segment);
    }
    if (node.isArray()) {
      List<String> items = new ArrayList<>();
      node.forEach(n -> items.add(n.asText()));
      return String.join(",", items);
    }
    return node.isValueNode() && !node.isNull() ? node.asText() : "";
  }

  /** The secrets.enc entry a password typed for {@code key} is stored under by default. */
  static String secretName(String key) {
    return switch (key) {
      case "server.auth.passwordRef" -> "JRS_PASSWORD";
      case "database.passwordRef" -> "JRS_DB_PASSWORD";
      case "network.proxy.passwordRef" -> "JRS_PROXY_PASSWORD";
      case "network.trustStore.passwordRef" -> "JRS_TRUSTSTORE_PASSWORD";
      default ->
          "JRS_" + DOT.matcher(key.replace("Ref", "")).replaceAll("_").toUpperCase(Locale.ROOT);
    };
  }
}
