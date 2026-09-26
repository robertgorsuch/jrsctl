package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.config.ConfigLoader;
import com.jaspersoft.jrsctl.core.config.ConfigWriter;
import com.jaspersoft.jrsctl.core.platform.DiskSpace;
import com.jaspersoft.jrsctl.core.platform.UserPaths;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.state.AuditActor;
import com.jaspersoft.jrsctl.ops.UnsupportedDeployment;
import com.jaspersoft.jrsctl.ops.init.InitOperation;
import com.jaspersoft.jrsctl.ops.init.InitReport;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl init}: detect the installation and write {@code config.yaml} (spec §12.0).
 * Invariants: nothing is written without confirmation unless {@code --yes}; an existing file is
 * kept unless {@code --force}; the values are shown with their sources first so the operator can
 * judge them; when the search finds more than one installation they are all listed, and
 * interactively the operator picks one by number, Enter keeping the recommended one, while {@code
 * --yes} and {@code --non-interactive} take the recommended one (field test 3); interactively (#63)
 * the operator may replace each {@link #REVIEW_FIELDS reviewed} value, which is validated like a
 * {@code --set} override before it is accepted, and may store the server and database passwords in
 * {@code secrets.enc}: they are read without echo into {@code char[]}s that are zeroed and never
 * printed, stored only after the write is confirmed and before the configuration is written, and
 * named by {@code enc:} references; otherwise, and always with {@code --yes}, {@code
 * --non-interactive} or {@code --json}, only {@code env:} placeholders are written.
 */
@Command(
    name = "init",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Detect the JasperReports Server installation and write config.yaml.")
final class InitCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Option(
      names = "--install-dir",
      paramLabel = "<dir>",
      description = "Installation root to inspect first (skips the platform search).")
  Path installDir;

  @Option(
      names = "--buildomatic-dir",
      paramLabel = "<dir>",
      description =
          "The installed buildomatic directory when it is not under the installation root"
              + " (another volume, a mount point or a network share).")
  Path buildomaticDir;

  @Option(names = "--force", description = "Overwrite an existing config.yaml.")
  boolean force;

  /** The file format {@code init} writes (#74, ADR-0022). */
  enum Format {
    YAML,
    PROPERTIES
  }

  @Option(
      names = "--format",
      paramLabel = "yaml|properties",
      description = "Write config.yaml (default) or jrsctl.properties.")
  Format format = Format.YAML;

  @Option(
      names = "--remote",
      paramLabel = "<url>",
      description =
          "Write a server-only configuration for this machine, which reaches the server over REST"
              + " (export and import) and has no installation, e.g."
              + " https://jrs.example.com/jasperserver-pro.")
  URI remote;

  /** A value the operator is offered to change: its key, a label and whether it is a directory. */
  record Field(String key, String label, boolean directory) {}

  /** The values reviewed interactively, in the order they are asked. */
  static final List<Field> REVIEW_FIELDS =
      List.of(
          new Field("server.baseUrl", "Server URL", false),
          new Field("server.auth.username", "Server admin user", false),
          new Field("server.installDir", "Installation directory", true),
          new Field("server.tomcatDir", "Tomcat directory", true),
          new Field("server.buildomaticDir", "Buildomatic directory", true),
          new Field(
              "service.kind",
              "Service type (windows-service, systemd, ctlscript, catalina, manual)",
              false),
          new Field("service.name", "Service name", false),
          new Field("database.url", "Repository database JDBC URL", false),
          new Field("database.username", "Repository database user", false),
          new Field("database.driverDir", "JDBC driver directory", true),
          new Field("vendor.javaHome", "Java for buildomatic", true));

  static final String SERVER_SECRET = "JRS_PASSWORD";
  static final String DATABASE_SECRET = "JRS_DB_PASSWORD";

  @Override
  public Integer call() throws IOException {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    Redactor redactor = Redactor.global();
    if (remote != null && (installDir != null || buildomaticDir != null)) {
      return ExitCodes.fail(
          out,
          err,
          global.json(),
          ExitCodes.USAGE,
          "--remote describes a machine without an installation; it cannot be combined with"
              + " --install-dir or --buildomatic-dir");
    }
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      InitOperation op = new InitOperation(boot.services());
      if (remote == null) {
        // ADR-0043: Tomcat only, never inside a container; said before anything is detected
        Optional<UnsupportedDeployment.Refusal> refused =
            op.unsupported(Optional.ofNullable(installDir), UnsupportedDeployment.Probe.host());
        if (refused.isPresent()) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.UNSUPPORTED,
              refused.get().reason(),
              Optional.of(refused.get().remediation()));
        }
      }
      InitReport report =
          remote != null
              ? op.detectRemote(remote)
              : op.detect(Optional.ofNullable(installDir), Optional.ofNullable(buildomaticDir));
      if (!global.json()) {
        // field test 3: every installation found is shown; interactively the operator picks one
        InstallChoice.print(report, out);
        if (report.candidates().size() > 1) {
          if (global.yes() || global.nonInteractive()) {
            out.println("using 1, the recommended one; pass --install-dir <dir> for another");
          } else {
            int picked = InstallChoice.ask(report, out);
            if (picked != 0) {
              report = op.choose(report, picked, Optional.ofNullable(buildomaticDir));
            }
          }
          out.println();
        }
      }
      Config config = op.toConfig(report);
      Path target =
          format == Format.PROPERTIES
              ? boot.services().home().propertiesConfigFile()
              : boot.services().home().yamlConfigFile();
      if (global.json()) {
        // one document: the detection report plus whether config.yaml was written (only --yes
        // writes in JSON mode, there is no prompt)
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("detectedInstall", report.detectedInstall());
        json.put("values", report.values());
        json.put("candidates", InstallChoice.json(report));
        json.put("notes", report.notes());
        json.put("config", ConfigWriter.toTree(config));
        json.put("configFile", target.toString());
        if (global.yes()) {
          try {
            Path written = op.write(config, target, force);
            json.put("written", true);
            json.put("writtenTo", written.toString());
          } catch (FileAlreadyExistsException e) {
            return alreadyExists(out, err, e);
          }
        } else if (global.nonInteractive()) {
          // spec §12.0: a caller that forbids prompting and gives no --yes is refused with 2, as
          // the text path and PlanExecutor do (assessment item P2); --json alone stays a report
          return ExitCodes.fail(
              out,
              err,
              true,
              ExitCodes.PRECHECK_FAILED,
              "confirmation required to write "
                  + target
                  + "; pass --yes to write without asking (--non-interactive never confirms)");
        } else {
          json.put("written", false);
        }
        JsonOut.print(out, json);
        return ExitCodes.SUCCESS;
      }
      TextTable table = new TextTable();
      for (InitReport.Detected d : report.values()) {
        table.row(d.key(), d.value(), Ansi.forStdout(global, Env.vars()).dim(d.source()));
      }
      for (String line : table.lines()) {
        out.println(redactor.redact(line));
      }
      if (!report.detectedInstall() && remote == null) {
        out.println("no installation detected; pass --install-dir <root> to point at one");
      }
      if (!global.yes()) {
        if (global.nonInteractive()) {
          err.println("error: confirmation required to write " + target);
          err.println(
              "pass --yes to write without asking (review 4.4: --non-interactive never confirms)");
          err.flush();
          return ExitCodes.PRECHECK_FAILED;
        }
        return interactive(boot, op, report, config, target, out, err);
      }
      try {
        Path written = op.write(config, target, force);
        out.println("wrote " + written);
        out.println(homeLine(boot));
        out.flush();
        return ExitCodes.SUCCESS;
      } catch (FileAlreadyExistsException e) {
        return alreadyExists(out, err, e);
      }
    }
  }

  /**
   * Review, passwords, confirmation, then the writes: secrets first, so a failure to store them
   * leaves no configuration naming references that do not exist.
   */
  private int interactive(
      Bootstrap boot,
      InitOperation op,
      InitReport report,
      Config detected,
      Path target,
      PrintWriter out,
      PrintWriter err)
      throws IOException {
    if (Files.exists(op.otherFormat(target))) {
      return alreadyExists(
          out,
          err,
          new FileAlreadyExistsException(
              op.otherFormat(target).toString(),
              null,
              "move it out of the jrsctl home to switch formats, or write that format"));
    }
    if (Files.exists(target) && !force) {
      return alreadyExists(out, err, new FileAlreadyExistsException(target.toString()));
    }
    ConfigLoader loader = new ConfigLoader();
    Config config = detected;
    out.println();
    if (Prompter.yes(out, "Change any of these values? [y/N] ", false)) {
      out.println("Press Enter to keep the value in brackets, or type a new one.");
      config = review(loader, config, report, out);
    }
    Map<String, char[]> passwords = new LinkedHashMap<>();
    List<Secret> held = new ArrayList<>();
    try {
      out.println();
      out.println("Passwords are never written to config.yaml.");
      if (Prompter.yes(
          out, "Store the passwords encrypted on this machine (recommended)? [Y/n] ", true)) {
        askPasswords(config, report, passwords, out);
      }
      Optional<EncryptedSecretStore> store = Optional.empty();
      if (!passwords.isEmpty()) {
        store = SecretStores.forWriting(boot, global, out, held);
        if (store.isEmpty()) {
          out.println("  no passphrase given; the passwords are not stored");
          clear(passwords);
        }
      }
      Map<String, String> refs = new LinkedHashMap<>();
      if (passwords.containsKey(SERVER_SECRET)) {
        refs.put("server.auth.passwordRef", "enc:" + SERVER_SECRET);
      }
      if (passwords.containsKey(DATABASE_SECRET)) {
        refs.put("database.passwordRef", "enc:" + DATABASE_SECRET);
      }
      if (!refs.isEmpty()) {
        config = loader.withOverrides(config, refs);
      }
      out.println();
      if (!Prompter.yes(out, "Write config to " + target + "? [y/N] ", false)) {
        out.println("config not written (pass --yes to write without asking)");
        out.flush();
        return ExitCodes.SUCCESS;
      }
      if (store.isPresent()) {
        try {
          storePasswords(boot, store.get(), passwords);
        } catch (SecretException e) {
          return ExitCodes.fail(
              out,
              err,
              false,
              ExitCodes.PRECHECK_FAILED,
              "passwords not stored and config not written: " + e.getMessage(),
              Optional.of("run jrsctl init again, or store them with jrsctl secrets set <NAME>"));
        }
      }
      Path written;
      try {
        written = op.write(config, target, force);
      } catch (FileAlreadyExistsException e) {
        return alreadyExists(out, err, e);
      }
      out.println("wrote " + written);
      out.println(homeLine(boot));
      if (store.isPresent()) {
        out.println(
            "stored "
                + String.join(" and ", passwords.keySet())
                + " encrypted in "
                + store.get().file()
                + "; jrsctl asks for the passphrase when it needs a password (for scheduled runs"
                + " set JRSCTL_PASSPHRASE or pass --passphrase-file)");
      }
      if (!config.envSecretNames().isEmpty()) {
        out.println(
            "before running jrsctl, set "
                + String.join(" and ", config.envSecretNames())
                + " in the environment, or store them encrypted with: jrsctl secrets set <NAME>");
      }
      out.println("Next: jrsctl doctor");
      out.flush();
      return ExitCodes.SUCCESS;
    } finally {
      clear(passwords);
      held.forEach(Secret::close);
    }
  }

  /** Asks for each reviewed field until the answer is kept or accepted; end of input stops. */
  private static Config review(
      ConfigLoader loader, Config start, InitReport report, PrintWriter out) {
    Config config = start;
    for (Field field : REVIEW_FIELDS) {
      boolean settled = false;
      while (!settled) {
        String written = ConfigKeys.value(config, field.key());
        // #73: database values read from default_master.properties show what jrsctl will use
        String current = written.isEmpty() ? detected(report, field.key()).orElse("") : written;
        Optional<String> answer = Prompter.line(out, "  " + field.label() + " [" + current + "]: ");
        if (answer.isEmpty()) {
          return config;
        }
        String value = answer.get();
        if (value.isEmpty()) {
          settled = true;
        } else if (field.directory()
            && !Files.isDirectory(Path.of(UserPaths.expand(value, Env.vars())))) {
          out.println(
              "    no such directory: "
                  + UserPaths.expand(value, Env.vars())
                  + " (press Enter to keep the old value)");
        } else {
          try {
            // a directory is stored expanded, since the service account has another home
            String stored = field.directory() ? UserPaths.expand(value, Env.vars()) : value;
            config = loader.withOverrides(config, Map.of(field.key(), stored));
            settled = true;
          } catch (ConfigException e) {
            out.println("    not accepted: " + e.getMessage());
          }
        }
      }
    }
    return config;
  }

  /** The value the detection report shows for {@code key}, when it has one. */
  private static Optional<String> detected(InitReport report, String key) {
    return report.values().stream()
        .filter(d -> d.key().equals(key))
        .map(InitReport.Detected::value)
        .findFirst();
  }

  private static void askPasswords(
      Config config, InitReport report, Map<String, char[]> passwords, PrintWriter out) {
    String user = config.server().auth().username().orElse("the server user");
    Prompter.secret(out, "  Password for " + user + " (Enter to skip): ")
        .ifPresent(p -> keep(passwords, SERVER_SECRET, p));
    Optional<String> dbUser =
        config.database().username().or(() -> detected(report, "database.username"));
    if (dbUser.isPresent()) {
      Prompter.secret(out, "  Password for database user " + dbUser.get() + " (Enter to skip): ")
          .ifPresent(p -> keep(passwords, DATABASE_SECRET, p));
    }
  }

  private static void keep(Map<String, char[]> passwords, String name, char[] value) {
    if (value.length == 0) {
      return;
    }
    passwords.put(name, value);
  }

  private static void storePasswords(
      Bootstrap boot, EncryptedSecretStore store, Map<String, char[]> passwords) {
    if (!store.exists()) {
      store.init();
    }
    for (Map.Entry<String, char[]> p : passwords.entrySet()) {
      try (Secret secret = Secret.of(p.getValue())) {
        store.set(p.getKey(), secret);
      }
      boot.services().stateStore().get().audit(AuditActor.current(), "secrets.set", p.getKey());
    }
  }

  private static void clear(Map<String, char[]> passwords) {
    passwords.values().forEach(c -> Arrays.fill(c, '\0'));
    passwords.clear();
  }

  /**
   * Where backups and state live and how to move them (field test 2, U3: the tester's /home held
   * 800 MB and nothing had said the jrsctl home was there).
   */
  private static String homeLine(Bootstrap boot) {
    java.nio.file.Path root = boot.services().home().root();
    String free;
    try {
      free = DiskSpace.human(boot.services().platform().files().freeSpaceBytes(root));
    } catch (IOException e) {
      free = "an unknown amount";
    }
    return "jrsctl home: "
        + root
        + " ("
        + free
        + " free; backups and state live here; move it with jrsctl home set <dir>)";
  }

  private int alreadyExists(PrintWriter out, PrintWriter err, FileAlreadyExistsException e) {
    return ExitCodes.fail(
        out,
        err,
        global.json(),
        ExitCodes.PRECHECK_FAILED,
        e.getFile() + " already exists",
        Optional.of(
            e.getReason() != null && e.getReason().contains("switch formats")
                ? e.getReason()
                : "pass --force to overwrite it"));
  }
}
