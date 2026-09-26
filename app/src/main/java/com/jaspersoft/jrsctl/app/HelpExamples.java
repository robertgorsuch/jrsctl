package com.jaspersoft.jrsctl.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Worked examples at the end of every command's {@code --help} (#61). Invariants: the examples live
 * here, keyed by command path, and are attached programmatically to the whole tree the way {@link
 * Explain} adds {@code --explain}, so no command class declares them; every runnable command has at
 * least one example and every example parses as the command it is listed under ({@code
 * HelpExamplesTest}); a group without examples of its own ends its help with a pointer to its
 * subcommands' help; a footer a command already declares is kept after the examples; the text is
 * static and reads nothing from the home, the configuration or the server.
 */
final class HelpExamples {

  /** One example: what it does, then the command line. */
  record Example(String what, String command) {}

  private static final Map<String, List<Example>> EXAMPLES = examples();

  private HelpExamples() {}

  /** The examples for a command path such as {@code "hotfix apply"} ({@code ""} = root). */
  static List<Example> examples(String commandPath) {
    return EXAMPLES.getOrDefault(commandPath, List.of());
  }

  /** Every command path with examples, in the order they are documented. */
  static Map<String, List<Example>> all() {
    return EXAMPLES;
  }

  /** Attaches the examples to every command under {@code root}. */
  static CommandLine install(CommandLine root) {
    install(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    return root;
  }

  private static void install(CommandLine cmd, Set<CommandSpec> seen) {
    CommandSpec spec = cmd.getCommandSpec();
    if (!seen.add(spec)) {
      return;
    }
    String path = relative(spec.qualifiedName(" "));
    if (!path.equals("help")) {
      List<String> footer = new ArrayList<>();
      List<Example> examples = examples(path);
      for (Example e : examples) {
        footer.add("  " + e.what());
        footer.add("    " + e.command());
      }
      if (hasVisibleSubcommands(cmd) && !path.isEmpty()) {
        if (!footer.isEmpty()) {
          footer.add("");
        }
        footer.add("Examples for each command: jrsctl " + path + " <command> --help");
      }
      if (!footer.isEmpty()) {
        List<String> declared = List.of(spec.usageMessage().footer());
        if (!declared.isEmpty()) {
          footer.add("");
          footer.addAll(declared);
        }
        spec.usageMessage()
            .footerHeading(examples.isEmpty() ? "%n" : "%nExamples:%n")
            .footer(footer.stream().map(HelpExamples::escape).toArray(String[]::new));
      }
    }
    for (CommandLine sub : cmd.getSubcommands().values()) {
      install(sub, seen);
    }
  }

  private static boolean hasVisibleSubcommands(CommandLine cmd) {
    return cmd.getSubcommands().values().stream()
        .anyMatch(
            s ->
                !s.getCommandSpec().usageMessage().hidden()
                    && !s.getCommandSpec().name().equals("help"));
  }

  /** Footer lines are format strings; a literal percent sign must be doubled. */
  private static String escape(String line) {
    return line.replace("%", "%%");
  }

  private static String relative(String qualifiedName) {
    return qualifiedName.equals("jrsctl") ? "" : qualifiedName.substring("jrsctl ".length());
  }

  private static Map<String, List<Example>> examples() {
    Map<String, List<Example>> m = new LinkedHashMap<>();
    m.put(
        "",
        List.of(
            new Example(
                "First run: check the tool, then connect it to the server", "jrsctl selfcheck"),
            new Example(
                "Detect the installation and write the configuration",
                "jrsctl init --install-dir /opt/jasperreports-server-pro-10.0.0"),
            new Example("Check the server before any change", "jrsctl doctor")));
    m.put(
        "selfcheck",
        List.of(
            new Example("Check the tool after unpacking a new version", "jrsctl selfcheck"),
            new Example(
                "The same report as JSON, for a support ticket", "jrsctl selfcheck --json")));
    m.put(
        "init",
        List.of(
            new Example(
                "Detect the installation under a directory and write config.yaml",
                "jrsctl init --install-dir /opt/jasperreports-server-pro-10.0.0"),
            new Example(
                "Buildomatic lives elsewhere (another disk or a share)",
                "jrsctl init --install-dir /opt/jasperreports-server-pro-10.0.0"
                    + " --buildomatic-dir /data/jrs/buildomatic"),
            new Example(
                "Show what would be written, without writing anything",
                "jrsctl init --install-dir /opt/jasperreports-server-pro-10.0.0 --json"),
            new Example(
                "Write jrsctl.properties instead of config.yaml",
                "jrsctl init --install-dir /opt/jasperreports-server-pro-10.0.0 --format properties"),
            new Example(
                "From another machine: REST export and import only",
                "jrsctl init --remote https://jrs.example.com:8443/jasperserver-pro")));
    m.put(
        "doctor",
        List.of(
            new Example("Check everything before a change; changes nothing", "jrsctl doctor"),
            new Example(
                "Keep diagnosing a server version outside the compatibility matrix",
                "jrsctl doctor --allow-unsupported")));
    m.put(
        "smoke",
        List.of(
            new Example(
                "Log in, list the repository, run a report, export; changes nothing",
                "jrsctl smoke"),
            new Example(
                "Also create, run and delete a temporary report under /temp",
                "jrsctl smoke --mutating")));
    m.put(
        "config show",
        List.of(
            new Example("Show the settings in use and where each came from", "jrsctl config show"),
            new Example(
                "The same as jrsctl.properties lines", "jrsctl config show --format properties"),
            new Example(
                "See the effect of a one-off override",
                "jrsctl config show --set server.baseUrl=https://jrs.example.com:8443/jasperserver-pro")));
    m.put(
        "config set",
        List.of(
            new Example(
                "Point jrsctl at another server address",
                "jrsctl config set server.baseUrl https://jrs.example.com:8443/jasperserver-pro"),
            new Example(
                "Type the admin password (hidden) and store it encrypted",
                "jrsctl config set server.auth.passwordRef"),
            new Example("Be asked for the new value", "jrsctl config set service.name")));
    m.put(
        "config unset",
        List.of(
            new Example(
                "Remove a setting so its default applies",
                "jrsctl config unset server.runAsUser")));
    m.put(
        "config keys",
        List.of(
            new Example(
                "List every setting, its value, where it comes from and what it does",
                "jrsctl config keys")));
    m.put(
        "hotfix verify",
        List.of(
            new Example(
                "Check a hotfix is genuine and fits this server; changes nothing",
                "jrsctl hotfix verify JRS-10.0.0-HF-0002.zip"),
            new Example(
                "Check an official Jaspersoft cumulative hotfix as downloaded",
                "jrsctl hotfix verify hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip")));
    m.put(
        "hotfix apply",
        List.of(
            new Example(
                "See every step, backup and restart first; changes nothing",
                "jrsctl hotfix apply JRS-10.0.0-HF-0002.zip --plan"),
            new Example(
                "Apply it (shows the plan and asks before running)",
                "jrsctl hotfix apply JRS-10.0.0-HF-0002.zip"),
            new Example(
                "Unattended, for example from a scheduler",
                "jrsctl hotfix apply JRS-10.0.0-HF-0002.zip --yes"),
            new Example(
                "Apply an official Jaspersoft cumulative hotfix as downloaded (it asks you to"
                    + " confirm the checksum against the support portal)",
                "jrsctl hotfix apply hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip"),
            new Example(
                "The same unattended, after checking the checksum yourself",
                "jrsctl hotfix apply hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip --yes"
                    + " --allow-unsigned")));
    m.put(
        "hotfix rollback",
        List.of(
            new Example(
                "Take an installed hotfix out again", "jrsctl hotfix rollback JRS-10.0.0-HF-0002"),
            new Example(
                "Also take out later hotfixes that depend on it; show the plan only",
                "jrsctl hotfix rollback JRS-10.0.0-HF-0002 --cascade --plan")));
    m.put(
        "hotfix list",
        List.of(new Example("Show installed and rolled-back hotfixes", "jrsctl hotfix list")));
    m.put(
        "hotfix record",
        List.of(
            new Example(
                "Enter an official package that was applied by hand, so list and upgrade know it",
                "jrsctl hotfix record hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip")));
    m.put(
        "hotfix build",
        List.of(
            new Example(
                "Authors only: hash, sign and zip a bundle directory (see jrsctl docs"
                    + " hotfix-authoring)",
                "jrsctl hotfix build ./my-hotfix --key file:/secure/my-team.key --out my-hotfix.zip")));
    m.put(
        "export",
        List.of(
            new Example(
                "Back up the whole repository; the server keeps running",
                "jrsctl export --out /backups/repository.zip"),
            new Example(
                "Back up one folder",
                "jrsctl export --uri /public/Samples --out /backups/samples.zip"),
            new Example(
                "Everything, with users, roles and settings; the server keeps running",
                "jrsctl export --full-server --out /backups/full-server.zip"),
            new Example(
                "The same with the service stopped while the export runs",
                "jrsctl export --full-server --stop-service --out /backups/full-server.zip")));
    m.put(
        "import",
        List.of(
            new Example(
                "See what an import would do; changes nothing",
                "jrsctl import /backups/samples.zip --plan"),
            new Example(
                "Import, replacing resources that already exist",
                "jrsctl import /backups/samples.zip --update"),
            new Example(
                "Skip the rollback copy; a failed import then cannot put back what it overwrote",
                "jrsctl import /backups/samples.zip --update --no-snapshot"),
            new Example(
                "Archive from a server with another keystore",
                "jrsctl import /backups/full-server.zip --source-keystore /tmp/source/.jrsks"
                    + " --source-keystore-password-ref enc:SOURCE_KEYSTORE_PASSWORD")));
    m.put(
        "upgrade",
        List.of(
            new Example(
                "Look at the plan; changes nothing",
                "jrsctl upgrade --to 10.0.0 --package /opt/dist/jasperreports-server-pro-10.0.0-bin"
                    + " --plan"),
            new Example(
                "Rehearse with the vendor's own validation; changes nothing",
                "jrsctl upgrade --to 10.0.0 --package /opt/dist/jasperreports-server-pro-10.0.0-bin"
                    + " --test"),
            new Example(
                "Run the upgrade (newdb: jrsctl exports the repository first and can rebuild the"
                    + " database from that export)",
                "jrsctl upgrade --to 10.0.0 --package /opt/dist/jasperreports-server-pro-10.0.0-bin"),
            new Example(
                "Migrate the schema in place instead; back up the database yourself first",
                "jrsctl upgrade --to 10.0.0 --package /opt/dist/jasperreports-server-pro-10.0.0-bin"
                    + " --mode samedb --db-backup-confirmed")));
    m.put(
        "upgrade rollback",
        List.of(
            new Example(
                "Put the files of an upgrade back (restore the database from your backup first)",
                "jrsctl upgrade rollback <run-id> --to-point B")));
    m.put(
        "customizations register",
        List.of(
            new Example(
                "Keep a changed file safe across upgrades, with the vendor's original",
                "jrsctl customizations register"
                    + " /opt/jrs/apache-tomcat/webapps/jasperserver-pro/WEB-INF/applicationContext-security.xml"
                    + " --original /opt/dist/applicationContext-security.xml")));
    m.put(
        "customizations scan",
        List.of(
            new Example(
                "Find what the site changed, against the unpacked vendor distribution",
                "jrsctl customizations scan --vendor /opt/dist/jasperreports-server-pro-10.0.0-bin"),
            new Example(
                "Register everything found, without asking",
                "jrsctl customizations scan --vendor /opt/dist/jasperserver-pro.war --register")));
    m.put(
        "customizations unregister",
        List.of(
            new Example(
                "Stop tracking a file (the file itself is not touched)",
                "jrsctl customizations unregister"
                    + " /opt/jrs/apache-tomcat/webapps/jasperserver-pro/WEB-INF/applicationContext-security.xml")));
    m.put(
        "customizations list",
        List.of(
            new Example(
                "Show tracked files and whether they changed", "jrsctl customizations list")));
    m.put(
        "customizations diff",
        List.of(
            new Example(
                "Compare a tracked file with its registered copy",
                "jrsctl customizations diff"
                    + " /opt/jrs/apache-tomcat/webapps/jasperserver-pro/WEB-INF/applicationContext-security.xml")));
    m.put(
        "runs list",
        List.of(
            new Example("Show recent runs and how they ended", "jrsctl runs list"),
            new Example("Only the last ten", "jrsctl runs list --limit 10"),
            new Example(
                "Failed or rolled-back runs", "jrsctl runs list --status failed,rolled-back"),
            new Example(
                "Hotfix runs of the last week", "jrsctl runs list --operation hotfix --since 7d")));
    m.put(
        "runs show",
        List.of(
            new Example("Show every step of one run", "jrsctl runs show <run-id>"),
            new Example("The same, naming the run by the end of its id", "jrsctl runs show ab12"),
            new Example(
                "The same as JSON, for a support ticket", "jrsctl runs show <run-id> --json")));
    m.put(
        "runs support-bundle",
        List.of(
            new Example(
                "Write the bundle for a support ticket", "jrsctl runs support-bundle <run-id>"),
            new Example(
                "Into a named file, listing the entries as JSON",
                "jrsctl runs support-bundle <run-id> --out bundle.zip --json")));
    m.put(
        "runs recover",
        List.of(
            new Example(
                "Finish an interrupted run from the step it stopped in",
                "jrsctl runs recover <run-id> --resume"),
            new Example(
                "Undo an interrupted run instead", "jrsctl runs recover <run-id> --rollback")));
    m.put(
        "runs prune",
        List.of(
            new Example("See which old backups would be deleted", "jrsctl runs prune --dry-run"),
            new Example(
                "Delete them (backups a rollback needs are always kept)", "jrsctl runs prune")));
    m.put(
        "home show",
        List.of(
            new Example(
                "See where jrsctl keeps its state and backups, and how full that volume is",
                "jrsctl home show")));
    m.put(
        "home set",
        List.of(
            new Example(
                "Keep state and backups on a bigger volume from now on",
                "jrsctl home set /data/jrsctl")));
    m.put(
        "home reset",
        List.of(
            new Example("Go back to the home the redirect was written in", "jrsctl home reset")));
    m.put(
        "keys list",
        List.of(new Example("Show the keys trusted to sign hotfixes", "jrsctl keys list")));
    m.put(
        "keys add",
        List.of(
            new Example(
                "Trust the public key of another hotfix publisher",
                "jrsctl keys add customer customer.pub")));
    m.put(
        "keys remove", List.of(new Example("Stop trusting a key", "jrsctl keys remove customer")));
    m.put(
        "keys generate",
        List.of(
            new Example(
                "Authors only: create a signing key pair",
                "jrsctl keys generate my-team --private-out /secure/my-team.key")));
    m.put(
        "secrets init",
        List.of(new Example("Create the encrypted password store", "jrsctl secrets init")));
    m.put(
        "secrets set",
        List.of(
            new Example(
                "Store the server password (typed, not shown); use it as enc:JRS_PASSWORD",
                "jrsctl secrets set JRS_PASSWORD"),
            new Example(
                "Take the value from an environment variable",
                "jrsctl secrets set JRS_DB_PASSWORD --from-env JRS_DB_PASSWORD")));
    m.put(
        "secrets remove",
        List.of(new Example("Delete a stored password", "jrsctl secrets remove JRS_DB_PASSWORD")));
    m.put(
        "secrets list",
        List.of(new Example("Show the names stored (never the values)", "jrsctl secrets list")));
    m.put(
        "docs",
        List.of(
            new Example("List the built-in documents", "jrsctl docs"),
            new Example("Read the operator guide", "jrsctl docs operator-guide"),
            new Example(
                "Page through it as plain text", "jrsctl docs operator-guide --format text")));
    return Collections.unmodifiableMap(m);
  }
}
