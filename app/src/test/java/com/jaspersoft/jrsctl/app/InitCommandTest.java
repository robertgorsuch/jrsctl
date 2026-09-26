package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigLoader;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsctl.core.secrets.PassphraseSource;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.ops.init.InitReport;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class InitCommandTest {

  @TempDir Path tmp;

  @AfterEach
  void restore() {
    Prompter.reset();
    Env.reset();
  }

  /** Runs init without --yes, answering its prompts from {@code answers}, one per line. */
  private Run interactive(Path home, Path install, String... answers) {
    Env.override(Map.of());
    Prompter.override(new StringReader(String.join(System.lineSeparator(), answers) + "\n"));
    return run("init", "--home", home.toString(), "--install-dir", install.toString());
  }

  static Path fakeLayout(Path install) throws Exception {
    Path tomcat = install.resolve("apache-tomcat");
    Path webapp = tomcat.resolve("webapps").resolve("jasperserver-pro");
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("classes"));
    Files.createDirectories(tomcat.resolve("conf"));
    Files.writeString(
        tomcat.resolve("conf").resolve("server.xml"),
        "<Server><Service><Connector port=\"8089\" protocol=\"HTTP/1.1\"/></Service></Server>",
        StandardCharsets.UTF_8);
    Path buildomatic = Files.createDirectories(install.resolve("buildomatic"));
    for (String ext : new String[] {".sh", ".bat"}) {
      Files.writeString(install.resolve("ctlscript" + ext), "", StandardCharsets.UTF_8);
      for (String script : new String[] {"js-export", "js-import", "js-ant"}) {
        Files.writeString(buildomatic.resolve(script + ext), "", StandardCharsets.UTF_8);
      }
    }
    Files.writeString(
        buildomatic.resolve("default_master.properties"),
        "dbType=postgresql\ndbHost=localhost\ndbPort=5432\ndbUsername=jasperdb\n"
            + "dbPassword=TopSecret\njs.dbName=jasperserver\n",
        StandardCharsets.UTF_8);
    return install;
  }

  record Run(int code, String out, String err) {}

  /**
   * Runs the real command tree. {@code err} is picocli's error writer plus everything written to
   * {@code System.err} meanwhile (logback's console appender, stray prints), so an assertion that
   * standard error stayed silent is about the process's standard error, not a private writer.
   */
  static Run run(String... args) {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    java.io.ByteArrayOutputStream systemErr = new java.io.ByteArrayOutputStream();
    java.io.PrintStream savedErr = System.err;
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code;
    System.setErr(
        new java.io.PrintStream(systemErr, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      code = cmd.execute(args);
    } finally {
      System.setErr(savedErr);
    }
    return new Run(
        code,
        out.toString(),
        err.toString() + systemErr.toString(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** Field test 3: a JRS 10 running and a JRS 9 beside it; the report ranks the 10 first. */
  private static InitReport twoInstallations(Path tmp) {
    return new InitReport(
        Config.defaults(),
        List.of(),
        List.of(
            candidate(tmp.resolve("jrs10"), "10.0.0", true, true),
            candidate(tmp.resolve("jrs9"), "9.0.0", false, false)),
        List.of("1 Java process whose command line this account cannot read"));
  }

  private static InitReport.Candidate candidate(
      Path install, String version, boolean running, boolean chosen) {
    Path tomcat = install.resolve("apache-tomcat");
    return new InitReport.Candidate(
        new TomcatLayout(
            install,
            tomcat,
            tomcat.resolve("webapps").resolve("jasperserver-pro"),
            "jasperserver-pro",
            Optional.empty(),
            Optional.empty(),
            Optional.of(8080)),
        Optional.of(version),
        running,
        chosen);
  }

  /** ADR-0043 (issue #118): a JBoss or WildFly home is refused with exit 6 before detection. */
  @Test
  void should_exit_6_when_the_install_dir_is_a_wildfly_home() throws Exception {
    Path home = tmp.resolve("home");
    Path wildfly = tmp.resolve("wildfly-36");
    Files.createDirectories(wildfly.resolve("standalone").resolve("deployments"));

    Run run = run("init", "--yes", "--home", home.toString(), "--install-dir", wildfly.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.UNSUPPORTED);
    assertThat(run.err()).contains("JBoss EAP or WildFly").contains("init --remote");
    assertThat(home.resolve("config.yaml")).doesNotExist();
  }

  @Test
  void should_number_every_installation_and_mark_the_recommended_one_when_listing() {
    StringWriter text = new StringWriter();

    InstallChoice.print(twoInstallations(tmp), new PrintWriter(text, true));

    assertThat(text.toString())
        .contains("Found 2 installations")
        .containsPattern("1\\) .*jrs10.*running.*10\\.0\\.0.*recommended")
        .containsPattern("2\\) .*jrs9.*not seen running.*9\\.0\\.0")
        .contains("note: 1 Java process whose command line this account cannot read");
  }

  @Test
  void should_return_the_picked_installation_when_the_operator_types_its_number() {
    Prompter.override(new StringReader("7\n2\n"));
    StringWriter text = new StringWriter();

    int picked = InstallChoice.ask(twoInstallations(tmp), new PrintWriter(text, true));

    assertThat(picked).isEqualTo(1);
    assertThat(text.toString()).contains("[1-2, Enter = 1]").contains("no installation 7");
  }

  @Test
  void should_keep_the_recommended_installation_when_the_operator_presses_enter() {
    Prompter.override(new StringReader("\n"));

    assertThat(InstallChoice.ask(twoInstallations(tmp), new PrintWriter(new StringWriter())))
        .isZero();
  }

  @Test
  void should_keep_the_recommended_installation_when_input_ends() {
    Prompter.override(new StringReader(""));

    assertThat(InstallChoice.ask(twoInstallations(tmp), new PrintWriter(new StringWriter())))
        .isZero();
  }

  @Test
  void should_describe_every_candidate_when_rendering_json() {
    List<Map<String, Object>> json = InstallChoice.json(twoInstallations(tmp));

    assertThat(json).hasSize(2);
    assertThat(json.get(0))
        .containsEntry("version", "10.0.0")
        .containsEntry("running", true)
        .containsEntry("recommended", true)
        .containsEntry("chosen", true)
        .containsEntry("edition", "commercial");
    assertThat(json.get(1)).containsEntry("recommended", false).containsEntry("running", false);
  }

  @Test
  void should_write_schema_valid_config_when_yes_and_install_dir_given() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run = run("init", "--yes", "--home", home.toString(), "--install-dir", install.toString());

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    Path file = home.resolve("config.yaml");
    assertThat(file).exists();
    String yaml = Files.readString(file, StandardCharsets.UTF_8);
    assertThat(yaml)
        .contains("webappName: jasperserver-pro")
        .contains("baseUrl: http://localhost:8089/jasperserver-pro")
        .contains("passwordRef: env:JRS_PASSWORD")
        .doesNotContain("TopSecret");
    Config loaded = new ConfigLoader().load(new JrsctlHome(home), Map.of(), Map.of());
    assertThat(loaded.server().baseUrl())
        .contains(URI.create("http://localhost:8089/jasperserver-pro"));
    // #73: config.yaml carries no database type; jrsctl reads it from default_master.properties
    assertThat(loaded.database().type()).isEmpty();
    assertThat(yaml).doesNotContain("postgresql");
    assertThat(run.out()).contains("wrote ").contains("server.webappName");
  }

  /** Field test 2, U3: the tester could not find where the backups go or how to move them. */
  @Test
  void should_print_the_home_and_its_free_space() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run = run("init", "--yes", "--home", home.toString(), "--install-dir", install.toString());

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out())
        .containsPattern(
            "jrsctl home: .* \\([0-9.]+ (GB|MB|B) free; backups and state live here; move it with"
                + " jrsctl home set <dir>\\)");
  }

  @Test
  void should_refuse_to_overwrite_when_config_exists_and_no_force() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");
    run("init", "--yes", "--home", home.toString(), "--install-dir", install.toString());

    Run second =
        run("init", "--yes", "--home", home.toString(), "--install-dir", install.toString());
    Run forced =
        run(
            "init",
            "--yes",
            "--force",
            "--home",
            home.toString(),
            "--install-dir",
            install.toString());

    assertThat(second.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(second.err()).contains("--force");
    assertThat(forced.code()).isZero();
  }

  @Test
  void should_print_json_report_and_not_write_when_json_without_yes() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run = run("init", "--json", "--home", home.toString(), "--install-dir", install.toString());

    assertThat(run.code()).isZero();
    assertThat(run.out()).contains("\"detectedInstall\" : true").contains("\"values\"");
    assertThat(home.resolve("config.yaml")).doesNotExist();
  }

  @Test
  void should_exit_2_with_an_error_document_when_json_and_non_interactive_without_yes()
      throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run =
        run(
            "init",
            "--json",
            "--non-interactive",
            "--home",
            home.toString(),
            "--install-dir",
            install.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.out()).contains("\"error\"").contains("confirmation required").contains("--yes");
    assertThat(home.resolve("config.yaml")).doesNotExist();
  }

  /** Issue #74: the configuration can be written as jrsctl.properties instead of config.yaml. */
  @Test
  void should_write_jrsctl_properties_when_format_properties_given() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run =
        run(
            "init",
            "--format",
            "properties",
            "--yes",
            "--home",
            home.toString(),
            "--install-dir",
            install.toString());

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(home.resolve("config.yaml")).doesNotExist();
    assertThat(home.resolve("jrsctl.properties"))
        .content()
        .contains("server.baseUrl=http://localhost:8089/jasperserver-pro")
        .contains("server.auth.passwordRef=env:JRS_PASSWORD");
    Config loaded = new ConfigLoader().load(new JrsctlHome(home), Map.of(), Map.of());
    assertThat(loaded.server().baseUrl())
        .contains(URI.create("http://localhost:8089/jasperserver-pro"));

    Run again =
        run(
            "init",
            "--force",
            "--yes",
            "--home",
            home.toString(),
            "--install-dir",
            install.toString());
    assertThat(again.code()).as("switching formats would leave two files").isEqualTo(2);
    assertThat(again.err()).contains("jrsctl.properties");
    assertThat(home.resolve("config.yaml")).doesNotExist();
  }

  /** Issue #68: a machine that only reaches the server over REST gets a server-only config. */
  @Test
  void should_write_a_server_only_config_when_remote_given() throws Exception {
    Path home = tmp.resolve("home");

    Run run =
        run(
            "init",
            "--remote",
            "https://jrs.example.com:8443/jasperserver-pro",
            "--yes",
            "--home",
            home.toString());

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).doesNotContain("no installation detected");
    Config loaded = new ConfigLoader().load(new JrsctlHome(home), Map.of(), Map.of());
    assertThat(loaded.server().baseUrl())
        .contains(URI.create("https://jrs.example.com:8443/jasperserver-pro"));
    assertThat(loaded.server().webappName()).contains(Config.WebappName.JASPERSERVER_PRO);
    assertThat(loaded.server().auth().username()).contains("superuser");
    assertThat(loaded.server().auth().passwordRef().map(SecretRef::render))
        .contains("env:JRS_PASSWORD");
    assertThat(loaded.server().namesLocalInstallation()).isFalse();
    assertThat(loaded.service().kind()).isEmpty();
  }

  @Test
  void should_refuse_remote_together_with_an_install_dir() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));

    Run run =
        run(
            "init",
            "--remote",
            "https://jrs.example.com/jasperserver",
            "--install-dir",
            install.toString(),
            "--yes",
            "--home",
            tmp.resolve("home").toString());

    assertThat(run.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(run.err()).contains("--remote").contains("--install-dir");
  }

  /** Issue #63: an operator who changes nothing gets the detected values after a few answers. */
  @Test
  void should_write_the_detected_values_when_the_operator_changes_nothing_and_confirms()
      throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run = interactive(home, install, "", "n", "y");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out())
        .contains("Change any of these values?")
        .contains("Store the passwords encrypted")
        .contains("JRS_PASSWORD")
        .contains("Next: jrsctl doctor");
    Config loaded = new ConfigLoader().load(new JrsctlHome(home), Map.of(), Map.of());
    assertThat(loaded.server().baseUrl())
        .contains(URI.create("http://localhost:8089/jasperserver-pro"));
    assertThat(loaded.server().auth().passwordRef().map(SecretRef::render))
        .contains("env:JRS_PASSWORD");
  }

  /** Issue #63: each value can be replaced; a directory that does not exist is asked again. */
  @Test
  void should_replace_a_value_and_ask_again_for_a_missing_directory_when_reviewing()
      throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");
    Path java = Files.createDirectories(tmp.resolve("jdk17"));
    java.util.List<String> answers = new java.util.ArrayList<>();
    answers.add("y");
    for (InitCommand.Field field : InitCommand.REVIEW_FIELDS) {
      switch (field.key()) {
        case "server.baseUrl" -> answers.add("https://jrs.example.com:8443/jasperserver-pro");
        case "server.auth.username" -> answers.add("jasperadmin");
        case "vendor.javaHome" -> {
          answers.add(tmp.resolve("no-such-jdk").toString());
          answers.add(java.toString());
        }
        default -> answers.add("");
      }
    }
    answers.add("n");
    answers.add("y");

    Run run = interactive(home, install, answers.toArray(String[]::new));

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).contains("no such directory");
    Config loaded = new ConfigLoader().load(new JrsctlHome(home), Map.of(), Map.of());
    assertThat(loaded.server().baseUrl())
        .contains(URI.create("https://jrs.example.com:8443/jasperserver-pro"));
    assertThat(loaded.server().auth().username()).contains("jasperadmin");
    assertThat(loaded.vendor().javaHome()).contains(java.toAbsolutePath().normalize());
  }

  /** Issue #63: passwords go to secrets.enc and the configuration names them as enc: references. */
  @Test
  void should_store_passwords_encrypted_and_write_enc_references_when_the_operator_agrees()
      throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run =
        interactive(
            home,
            install,
            "", // change nothing
            "y", // store the passwords encrypted
            "Adm1n-Secret", // server password
            "Db-Secret", // database password
            "store-pass", // new store passphrase
            "store-pass", // again
            "y"); // write

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    Config loaded = new ConfigLoader().load(new JrsctlHome(home), Map.of(), Map.of());
    assertThat(loaded.server().auth().passwordRef().map(SecretRef::render))
        .contains("enc:JRS_PASSWORD");
    assertThat(loaded.database().passwordRef().map(SecretRef::render))
        .contains("enc:JRS_DB_PASSWORD");
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            new JrsctlHome(home).secretsFile(),
            new PassphraseSource.Fixed(Secret.fromString("store-pass")));
    try (Secret server = store.get("JRS_PASSWORD").orElseThrow();
        Secret db = store.get("JRS_DB_PASSWORD").orElseThrow()) {
      assertThat(new String(server.chars())).isEqualTo("Adm1n-Secret");
      assertThat(new String(db.chars())).isEqualTo("Db-Secret");
    }
    assertThat(Files.readString(home.resolve("config.yaml"), StandardCharsets.UTF_8))
        .doesNotContain("Adm1n-Secret")
        .doesNotContain("Db-Secret");
    assertThat(run.out() + run.err())
        .doesNotContain("Adm1n-Secret")
        .doesNotContain("store-pass")
        .contains("JRSCTL_PASSPHRASE")
        .doesNotContain("before running jrsctl, set");
  }

  @Test
  void should_ask_again_when_the_two_passphrases_differ() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run =
        interactive(
            home,
            install,
            "",
            "y",
            "Adm1n-Secret",
            "",
            "one",
            "two",
            "store-pass",
            "store-pass",
            "y");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).contains("the passphrases differ");
    Config loaded = new ConfigLoader().load(new JrsctlHome(home), Map.of(), Map.of());
    assertThat(loaded.server().auth().passwordRef().map(SecretRef::render))
        .contains("enc:JRS_PASSWORD");
    assertThat(loaded.database().passwordRef().map(SecretRef::render))
        .contains("env:JRS_DB_PASSWORD");
    assertThat(run.out()).contains("before running jrsctl, set JRS_DB_PASSWORD");
  }

  @Test
  void should_store_nothing_when_the_operator_declines_to_write() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run =
        interactive(
            home, install, "", "y", "Adm1n-Secret", "Db-Secret", "store-pass", "store-pass", "n");

    assertThat(run.code()).isZero();
    assertThat(home.resolve("config.yaml")).doesNotExist();
    assertThat(new JrsctlHome(home).secretsFile()).doesNotExist();
  }

  @Test
  void should_write_nothing_when_input_ends_before_confirmation() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run = interactive(home, install, "y", "https://jrs.example.com/jasperserver-pro");

    assertThat(run.code()).isZero();
    assertThat(home.resolve("config.yaml")).doesNotExist();
  }
}
