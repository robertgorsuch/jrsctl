package com.jaspersoft.jrsctl.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.engine.RunLock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec §14 Phase 3, exercised through the packaged jar: a customer key is generated, a bundle is
 * built and signed with it, verified (and a tampered copy rejected), applied against a fake Tomcat
 * layout with a WireMock 8.2.0 PRO server (plan first, then for real), refused while another
 * process holds the run lock, rolled back, and an unsigned copy is refused unless {@code
 * --allow-unsigned}. The steps share one home and bundle, so they run in order.
 */
@Tag("phase3")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase3HotfixTest {

  private static final String WEBAPP = "/jasperserver-pro";
  private static final String HOTFIX_ID = "JRS-8.2.0-HF-0001";
  private static final String REPLACED = "webapps/jasperserver-pro/scripts/jrsctl-fix.js";
  private static final String ADDED = "webapps/jasperserver-pro/jrsctl/added.txt";
  private static final String ORIGINAL_JS = "// original\nconsole.log('before');\n";
  private static final String FIXED_JS = "// fixed by " + HOTFIX_ID + "\nconsole.log('after');\n";
  private static final String ADDED_TXT = "added by " + HOTFIX_ID + "\n";
  private static final String SERVER_INFO =
      """
      {
        "version": "8.2.0",
        "edition": "PRO",
        "editionName": "Professional",
        "features": "Fusion AHD EXP DB AUD ANA MT ",
        "build": "20230315_1234",
        "licenseType": "Commercial",
        "expiration": "2099-01-01",
        "dateFormatPattern": "yyyy-MM-dd",
        "datetimeFormatPattern": "yyyy-MM-dd'T'HH:mm:ss"
      }
      """;

  private static WireMockServer server;
  private static Cli cli;

  @TempDir static Path tmp;

  private static Path home;
  private static Path install;
  private static Path tomcat;
  private static Path privateKey;
  private static Path bundle;

  @BeforeAll
  static void setUp() throws Exception {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/serverInfo")).willReturn(okJson(SERVER_INFO)));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/resources"))
            .willReturn(okJson("{\"resourceLookup\":[]}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/jobs")).willReturn(okJson("{\"jobsummary\":[]}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/organizations"))
            .willReturn(okJson("{\"organization\":[]}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/export/jrsctl-probe/state"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"errorCode\":\"no.such.export.process\",\"message\":\"No task jrsctl-probe\"}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/import/jrsctl-probe/state"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"errorCode\":\"no.such.export.process\",\"message\":\"No task jrsctl-probe\"}")));
    server.stubFor(
        post(urlPathEqualTo(WEBAPP + "/rest_v2/login"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Set-Cookie", "JSESSIONID=ABCDEF0123456789; Path=/; HttpOnly")));

    cli = new Cli(Map.of("JRS_PASSWORD", "jasperadmin"));
    home = Files.createDirectories(tmp.resolve("home"));
    install = fakeLayout(tmp.resolve("jrs"));
    tomcat = install.resolve("apache-tomcat-9");
    privateKey = tmp.resolve("keys").resolve("customer.key");
    bundle = tmp.resolve(HOTFIX_ID + ".zip");
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:%d%s
          webappName: jasperserver-pro
          installDir: %s
          tomcatDir: %s
          auth:
            mode: basic
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: manual
        network:
          mode: public
        """
            .formatted(server.port(), WEBAPP, unix(install), unix(tomcat)),
        StandardCharsets.UTF_8);
  }

  @AfterAll
  static void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  // ---- fixtures -------------------------------------------------------------------------------

  private static String unix(Path p) {
    return p.toString().replace("\\", "/");
  }

  /** {@code apache-tomcat-9/webapps/jasperserver-pro/{WEB-INF/lib/x.jar, scripts/...}}. */
  private static Path fakeLayout(Path install) throws IOException {
    Path tomcat = install.resolve("apache-tomcat-9");
    Path webapp = tomcat.resolve("webapps").resolve("jasperserver-pro");
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("classes"));
    Files.createDirectories(webapp.resolve("scripts"));
    Files.write(webapp.resolve("WEB-INF").resolve("lib").resolve("x.jar"), new byte[128]);
    Files.writeString(
        webapp.resolve("scripts").resolve("jrsctl-fix.js"), ORIGINAL_JS, StandardCharsets.UTF_8);
    Files.createDirectories(tomcat.resolve("conf"));
    Files.writeString(
        tomcat.resolve("conf").resolve("server.xml"),
        "<Server><Service><Connector port=\"8080\" protocol=\"HTTP/1.1\"/></Service></Server>",
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
            + "js.dbName=jasperserver\n",
        StandardCharsets.UTF_8);
    return install;
  }

  /** A bundle directory: manifest.json plus payload/ mirroring the Tomcat-relative paths. */
  private static Path fixtureDir(Path dir) throws Exception {
    Path payload = dir.resolve("payload");
    Path replaced = payload.resolve(REPLACED);
    Path added = payload.resolve(ADDED);
    Files.createDirectories(replaced.getParent());
    Files.createDirectories(added.getParent());
    Files.writeString(replaced, FIXED_JS, StandardCharsets.UTF_8);
    Files.writeString(added, ADDED_TXT, StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("manifest.json"),
        """
        {
          "id": "%s",
          "version": "1",
          "title": "Phase 3 acceptance fix",
          "applies": { "versions": [">=8.2.0 <8.3.0"], "editions": ["PRO"] },
          "requires": [],
          "conflicts": [],
          "files": [
            { "action": "replace", "path": "%s", "sha256": "%s" },
            { "action": "add", "path": "%s", "sha256": "%s" }
          ],
          "restart": "none",
          "prechecks": [ { "type": "fileExists", "path": "%s" } ],
          "postchecks": [],
          "rollback": "snapshot"
        }
        """
            .formatted(HOTFIX_ID, REPLACED, sha256(replaced), ADDED, sha256(added), REPLACED),
        StandardCharsets.UTF_8);
    return dir;
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] buf = new byte[8192];
    try (InputStream in = Files.newInputStream(file)) {
      int n;
      while ((n = in.read(buf)) != -1) {
        md.update(buf, 0, n);
      }
    }
    return HexFormat.of().formatHex(md.digest());
  }

  private static String sha256Of(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Copies a ZIP entry by entry; {@code edit} returns the bytes to write for an entry (the original
   * ones to keep it unchanged) or empty to drop the entry.
   */
  private static Path rewriteZip(Path source, Path target, Function<Entry, Optional<byte[]>> edit)
      throws IOException {
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source));
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        byte[] bytes = in.readAllBytes();
        Optional<byte[]> replacement = edit.apply(new Entry(entry.getName(), bytes));
        if (replacement.isEmpty()) {
          continue;
        }
        out.putNextEntry(new ZipEntry(entry.getName()));
        out.write(replacement.get());
        out.closeEntry();
      }
    }
    return target;
  }

  // carries one zip entry to an edit function and is never compared, so array identity is harmless
  @SuppressWarnings("ArrayRecordComponent")
  private record Entry(String name, byte[] bytes) {}

  private static Path fileInTomcat(String relative) {
    return tomcat.resolve(relative);
  }

  private static Cli.Result jrsctl(String... args) throws Exception {
    // --ascii as well as --no-color: since review 3.5 the two are separate decisions, so a
    // UTF-8 terminal without colour still gets the tick glyphs. Assertions on the words need
    // to ask for the words.
    String[] all = new String[args.length + 4];
    System.arraycopy(args, 0, all, 0, args.length);
    all[args.length] = "--home";
    all[args.length + 1] = home.toString();
    all[args.length + 2] = "--no-color";
    all[args.length + 3] = "--ascii";
    return cli.run(all);
  }

  // ---- criteria -------------------------------------------------------------------------------

  @Test
  @Order(1)
  void keys_generate_then_hotfix_build_produce_a_signed_bundle() throws Exception {
    Cli.Result generated =
        jrsctl("keys", "generate", "customer", "--private-out", privateKey.toString())
            .assertExit(0);
    assertThat(generated.stdout()).contains("customer").contains("file:");
    assertThat(privateKey).exists();
    assertThat(jrsctl("keys", "list").assertExit(0).stdout()).contains("customer");

    Path fixture = fixtureDir(tmp.resolve("fixture"));
    Cli.Result built =
        jrsctl(
                "hotfix",
                "build",
                fixture.toString(),
                "--key",
                "file:" + privateKey,
                "--out",
                bundle.toString())
            .assertExit(0);
    assertThat(built.stdout()).contains("wrote");
    assertThat(bundle).exists();
    java.util.Set<String> names = new java.util.HashSet<>();
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(bundle))) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        names.add(e.getName());
      }
    }
    assertThat(names).contains("manifest.json", "SIGNATURE", "payload/" + REPLACED);
  }

  @Test
  @Order(2)
  void hotfix_verify_accepts_the_bundle_and_rejects_a_tampered_copy() throws Exception {
    Cli.Result ok = jrsctl("hotfix", "verify", bundle.toString()).assertExit(0);
    assertThat(ok.stdout()).contains("+ PASS").contains("signature").contains(HOTFIX_ID);

    Path tampered =
        rewriteZip(
            bundle,
            tmp.resolve("tampered.zip"),
            e -> {
              if (e.name().equals("payload/" + ADDED)) {
                byte[] bytes = e.bytes().clone();
                bytes[0] = (byte) (bytes[0] ^ 0x01);
                return Optional.of(bytes);
              }
              return Optional.of(e.bytes());
            });
    Cli.Result bad = jrsctl("hotfix", "verify", tampered.toString()).assertExit(7);
    assertThat(bad.stdout()).contains("x FAIL");
  }

  @Test
  @Order(3)
  void hotfix_apply_plan_prints_the_phases_and_changes_nothing() throws Exception {
    Cli.Result plan = jrsctl("hotfix", "apply", bundle.toString(), "--plan").assertExit(0);

    assertThat(plan.stdout())
        .contains("Plan")
        .contains("verify")
        .contains("backup")
        .contains("apply")
        .contains("record")
        .contains("nothing has changed");
    assertThat(sha256(fileInTomcat(REPLACED))).isEqualTo(sha256Of(ORIGINAL_JS));
    assertThat(fileInTomcat(ADDED)).doesNotExist();
  }

  @Test
  @Order(4)
  void hotfix_apply_yes_installs_the_files_and_records_the_hotfix() throws Exception {
    Cli.Result applied = jrsctl("hotfix", "apply", bundle.toString(), "--yes").assertExit(0);

    assertThat(applied.stdout()).contains("OK").contains("succeeded");
    assertThat(sha256(fileInTomcat(REPLACED))).isEqualTo(sha256Of(FIXED_JS));
    assertThat(fileInTomcat(ADDED)).exists();
    assertThat(Files.readString(fileInTomcat(ADDED), StandardCharsets.UTF_8)).isEqualTo(ADDED_TXT);

    Cli.Result list = jrsctl("hotfix", "list", "--json").assertExit(0);
    JsonNode rows = new ObjectMapper().readTree(list.stdout());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("id").asText()).isEqualTo(HOTFIX_ID);
    assertThat(rows.get(0).get("state").asText()).isEqualTo("INSTALLED");
    assertThat(rows.get(0).get("files").asInt()).isEqualTo(2);

    Cli.Result runs = jrsctl("runs", "list", "--json").assertExit(0);
    JsonNode run = new ObjectMapper().readTree(runs.stdout()).get(0);
    assertThat(run.get("operation").asText()).isEqualTo("hotfix.apply");
    assertThat(run.get("terminalState").asText()).isEqualTo("SUCCEEDED");
    assertThat(jrsctl("runs", "show", run.get("runId").asText()).assertExit(0).stdout())
        .contains("Steps")
        .contains("SUCCEEDED");
  }

  @Test
  @Order(5)
  void hotfix_apply_exits_9_while_another_process_holds_the_run_lock() throws Exception {
    try (RunLock held = new RunLock(new JrsctlHome(home), "r-acceptance-holder", Instant.now())) {
      Cli.Result refused = jrsctl("hotfix", "apply", bundle.toString(), "--yes").assertExit(9);
      assertThat(refused.stderr()).contains("r-acceptance-holder").contains("pid");
    }
  }

  @Test
  @Order(6)
  void hotfix_rollback_restores_the_original_files() throws Exception {
    Cli.Result rolledBack = jrsctl("hotfix", "rollback", HOTFIX_ID, "--yes").assertExit(0);

    assertThat(rolledBack.stdout()).contains("succeeded");
    assertThat(sha256(fileInTomcat(REPLACED))).isEqualTo(sha256Of(ORIGINAL_JS));
    assertThat(fileInTomcat(ADDED)).doesNotExist();
    Cli.Result list = jrsctl("hotfix", "list", "--json").assertExit(0);
    JsonNode rows = new ObjectMapper().readTree(list.stdout());
    assertThat(rows.get(0).get("state").asText()).isEqualTo("ROLLED_BACK");
  }

  @Test
  @Order(7)
  void unsigned_bundle_is_refused_with_exit_7_unless_allow_unsigned_given() throws Exception {
    Path unsigned =
        rewriteZip(
            bundle,
            tmp.resolve("unsigned.zip"),
            e -> e.name().equals("SIGNATURE") ? Optional.empty() : Optional.of(e.bytes()));

    jrsctl("hotfix", "verify", unsigned.toString()).assertExit(7);
    Cli.Result refused = jrsctl("hotfix", "apply", unsigned.toString(), "--yes").assertExit(7);
    assertThat(refused.stderr()).contains("--allow-unsigned");
    assertThat(sha256(fileInTomcat(REPLACED))).isEqualTo(sha256Of(ORIGINAL_JS));

    jrsctl("hotfix", "apply", unsigned.toString(), "--yes", "--allow-unsigned").assertExit(0);
    assertThat(sha256(fileInTomcat(REPLACED))).isEqualTo(sha256Of(FIXED_JS));
    assertThat(fileInTomcat(ADDED)).exists();
  }
}
