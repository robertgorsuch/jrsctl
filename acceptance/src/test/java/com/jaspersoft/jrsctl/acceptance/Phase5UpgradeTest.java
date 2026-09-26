package com.jaspersoft.jrsctl.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 5 acceptance (spec §14): the packaged jar plans and runs an upgrade against a fake Tomcat
 * layout, a fake target package whose {@code js-ant} copies a new webapp into place, a fake {@code
 * catalina} script that starts and stops a detectable stand-in process, and a WireMock
 * JasperReports Server that keeps reporting 8.2.0 PRO (assertions are on the plan and on file
 * effects, never on the reported version). The vendor JDK is a shell-script fake on Linux (Java 17,
 * target 9.0.0); on Windows a real JDK is required and the target version follows it (17 -> 9.0.0,
 * 11 -> 8.3.0; set {@code JRSCTL_VENDOR_JDK} to choose one).
 */
@Tag("phase5")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase5UpgradeTest {

  private static final String WEBAPP = "/jasperserver-pro";
  private static final String MARKER = "upgraded.marker";
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
  private static final Pattern BANNER = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?[^\"]*\"");

  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

  private static WireMockServer server;
  private static Cli cli;
  @TempDir static Path tmp;
  private static Path home;
  private static Path installedBuildomatic;
  private static Path install;
  private static Path tomcat;
  private static Path webapp;
  private static Path pkg;
  private static Path userHome;
  private static String targetVersion;
  private static String upgradeRunId;

  @BeforeAll
  static void setUp() throws Exception {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/serverInfo")).willReturn(okJson(SERVER_INFO)));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/resources"))
            .willReturn(okJson("{\"resourceLookup\":[]}")));
    // field test 2, E3: export planning asks whether each --uri exists
    server.stubFor(
        get(urlPathMatching(WEBAPP + "/rest_v2/resources/.+"))
            .willReturn(okJson("{\"uri\":\"/public\",\"label\":\"Public\"}")));
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
        post(urlPathEqualTo(WEBAPP + "/rest_v2/export"))
            .willReturn(okJson("{\"id\":\"e1\",\"phase\":\"inprogress\"}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/export/e1/state"))
            .willReturn(okJson("{\"id\":\"e1\",\"phase\":\"ready\",\"fileName\":\"export.zip\"}")));
    server.stubFor(
        get(urlPathEqualTo(WEBAPP + "/rest_v2/export/e1/export.zip"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/zip")
                    .withBody("PK\u0003\u0004 fake export archive body")));
    server.stubFor(
        post(urlPathEqualTo(WEBAPP + "/rest_v2/login"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Set-Cookie", "JSESSIONID=ABCDEF0123456789; Path=/; HttpOnly")));

    home = Files.createDirectories(tmp.resolve("home"));
    userHome = Files.createDirectories(tmp.resolve("user-home"));
    Files.writeString(userHome.resolve(".jrsks"), "keystore-bytes", StandardCharsets.UTF_8);
    Files.writeString(userHome.resolve(".jrsksp"), "keystore-props", StandardCharsets.UTF_8);
    install = fakeLayout(tmp.resolve("jrs"));
    tomcat = install.resolve("apache-tomcat-9");
    webapp = tomcat.resolve("webapps").resolve("jasperserver-pro");
    Path vendorJdk = vendorJdk();
    pkg = fakePackage(tmp.resolve("pkg"));
    cli =
        new Cli(
            Map.of("JRS_PASSWORD", "jasperadmin", "JAVA_TOOL_OPTIONS", "-Duser.home=" + userHome));
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
          kind: catalina
          scriptPath: %s
          stopTimeoutSeconds: 60
        vendor:
          javaHome: %s
        network:
          mode: public
        """
            .formatted(
                server.port(),
                WEBAPP,
                unix(install),
                unix(tomcat),
                unix(tomcat.resolve("bin").resolve(script("catalina"))),
                unix(vendorJdk)),
        StandardCharsets.UTF_8);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (server != null) {
      server.stop();
    }
    if (tomcat != null) {
      stopFakeTomcat();
    }
  }

  // ---------------------------------------------------------------- fixtures

  private static String unix(Path p) {
    return p.toString().replace("\\", "/");
  }

  private static String script(String name) {
    return name + (WINDOWS ? ".bat" : ".sh");
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
    if (!WINDOWS
        && (file.toString().endsWith(".sh") || file.getFileName().toString().equals("java"))) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  private static Path fakeLayout(Path install) throws IOException {
    Path tomcat = install.resolve("apache-tomcat-9");
    Path webapp = tomcat.resolve("webapps").resolve("jasperserver-pro");
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("classes"));
    Files.write(webapp.resolve("WEB-INF").resolve("lib").resolve("x.jar"), new byte[128]);
    write(webapp.resolve("version.txt"), "8.2.0");
    write(webapp.resolve("META-INF").resolve("context.xml"), "<Context/>");
    write(
        tomcat.resolve("conf").resolve("server.xml"),
        "<Server><Service><Connector port=\"18080\" protocol=\"HTTP/1.1\"/></Service></Server>");
    write(
        tomcat
            .resolve("conf")
            .resolve("Catalina")
            .resolve("localhost")
            .resolve("jasperserver-pro.xml"),
        "<Context docBase=\"jasperserver-pro\"/>");
    Path buildomatic = install.resolve("buildomatic");
    installedBuildomatic = buildomatic;
    vendorScripts(buildomatic, Optional.empty());
    write(
        buildomatic.resolve("default_master.properties"),
        "appServerType=tomcat\ndbType=postgresql\ndbHost=localhost\ndbPort=5432\ndbUsername=jasperdb\n"
            + "dbPassword=TopSecret\njs.dbName=jasperserver\n");
    catalinaScripts(tomcat);
    return install;
  }

  /** A fake {@code catalina} script whose "running" state a real service controller can see. */
  private static void catalinaScripts(Path tomcat) throws IOException {
    Path bin = Files.createDirectories(tomcat.resolve("bin"));
    if (WINDOWS) {
      Path exe = bin.resolve("tomcat9.exe");
      Files.copy(
          Path.of(System.getenv("SystemRoot"), "System32", "PING.EXE"),
          exe,
          StandardCopyOption.REPLACE_EXISTING);
      write(
          bin.resolve("catalina.bat"),
          "@echo off\r\n"
              + "set \"EXE=%~dp0tomcat9.exe\"\r\n"
              + "if \"%1\"==\"start\" (\r\n"
              + "  powershell -NoProfile -Command \"Start-Process -FilePath '%EXE%' -ArgumentList"
              + " '-n','900','127.0.0.1' -WindowStyle Hidden\"\r\n"
              + "  echo started\r\n"
              + "  exit /b 0\r\n"
              + ")\r\n"
              + "if \"%1\"==\"stop\" (\r\n"
              + "  powershell -NoProfile -Command \"Get-CimInstance Win32_Process -Filter"
              + " \\\"Name='tomcat9.exe'\\\" | Where-Object { $_.ExecutablePath -eq '%EXE%' } |"
              + " ForEach-Object { Stop-Process -Id $_.ProcessId -Force }\"\r\n"
              + "  echo stopped\r\n"
              + "  exit /b 0\r\n"
              + ")\r\n"
              + "exit /b 0\r\n");
    } else {
      String marker = "-Dcatalina.base=" + tomcat;
      write(
          bin.resolve("catalina.sh"),
          "#!/bin/sh\n"
              + "case \"$1\" in\n"
              + "  start) nohup sh -c 'sleep 900 # "
              + marker
              + "' >/dev/null 2>&1 </dev/null & ;;\n"
              + "  stop) pkill -f -- '"
              + marker
              + "' || true ;;\n"
              + "esac\n"
              + "exit 0\n");
    }
  }

  private static void stopFakeTomcat() throws Exception {
    // the fake script's own "stop" branch knows how to find its stand-in process
    List<String> cmd =
        List.of(tomcat.resolve("bin").resolve(script("catalina")).toString(), "stop");
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    p.waitFor(30, TimeUnit.SECONDS);
    if (WINDOWS) {
      // the stand-in process holds tomcat9.exe open until it is really gone; wait for that so the
      // JUnit temp directory can be deleted
      Path exe = tomcat.resolve("bin").resolve("tomcat9.exe");
      for (int i = 0; i < 50 && Files.exists(exe); i++) {
        try {
          Files.delete(exe);
        } catch (IOException e) {
          Thread.sleep(200);
        }
      }
    }
  }

  /** js-export writes a fake archive; js-ant in the package copies webapp-new over the webapp. */
  private static void vendorScripts(Path buildomatic, Optional<Path> webappNew) throws IOException {
    Files.createDirectories(buildomatic);
    write(
        buildomatic.resolve("js-export.bat"),
        "@echo off\r\n"
            + "echo Processing started\r\n"
            + "echo fake export > \"%2\"\r\n"
            + "echo Done\r\n"
            + "exit /b 0\r\n");
    write(
        buildomatic.resolve("js-export.sh"),
        "#!/bin/sh\necho Processing started\necho fake export > \"$2\"\necho Done\nexit 0\n");
    if (webappNew.isEmpty()) {
      // the installed buildomatic: what upgrade rollback --restore-database runs (ADR-0029); the
      // fakes log their arguments to vendor.log and say what the real ones say on success
      write(
          buildomatic.resolve("js-ant.bat"),
          "@echo off\r\necho %* >> \"%~dp0vendor.log\"\r\necho BUILD SUCCESSFUL\r\nexit /b 0\r\n");
      write(
          buildomatic.resolve("js-ant.sh"),
          "#!/bin/sh\necho \"$@\" >> \"$(dirname \"$0\")/vendor.log\"\necho BUILD SUCCESSFUL\n"
              + "exit 0\n");
      write(
          buildomatic.resolve("js-import.bat"),
          "@echo off\r\necho js-import %* >> \"%~dp0vendor.log\"\r\necho Processing started\r\n"
              + "echo VALIDATION COMPLETED\r\necho Done\r\nexit /b 0\r\n");
      write(
          buildomatic.resolve("js-import.sh"),
          "#!/bin/sh\necho \"js-import $@\" >> \"$(dirname \"$0\")/vendor.log\"\n"
              + "echo Processing started\necho VALIDATION COMPLETED\necho Done\nexit 0\n");
      return;
    }
    write(buildomatic.resolve("js-import.bat"), "@echo off\r\nexit /b 0\r\n");
    write(buildomatic.resolve("js-import.sh"), "#!/bin/sh\nexit 0\n");
    String target = webapp.toString();
    write(
        buildomatic.resolve("js-ant.bat"),
        "@echo off\r\n"
            + "echo js-ant fake target=%1 JAVA_HOME=%JAVA_HOME%\r\n"
            + "if \"%1\"==\"pre-upgrade-test-pro\" (echo BUILD SUCCESSFUL & exit /b 0)\r\n"
            + "xcopy /E /Y /I /Q \""
            + webappNew.get()
            + "\" \""
            + target
            + "\" >nul\r\n"
            + "if errorlevel 1 exit /b 1\r\n"
            + "exit /b 0\r\n");
    write(
        buildomatic.resolve("js-ant.sh"),
        "#!/bin/sh\n"
            + "echo \"js-ant fake target=$1 JAVA_HOME=$JAVA_HOME\"\n"
            + "if [ \"$1\" = \"pre-upgrade-test-pro\" ]; then echo BUILD SUCCESSFUL; exit 0; fi\n"
            + "cp -R \""
            + webappNew.get()
            + "/.\" \""
            + target
            + "/\" || exit 1\n"
            + "exit 0\n");
    // The wrappers a real package ships, with the vendor's argument contract (ADR-0012):
    // js-upgrade-newdb refuses to run without an existing export file.
    write(
        buildomatic.resolve("js-upgrade-newdb.bat"),
        "@echo off\r\n"
            + "if \"%~1\"==\"test\" (call \"%~dp0js-ant.bat\" pre-upgrade-test-pro"
            + " -Dstrategy=standard & exit /b %errorlevel%)\r\n"
            + "if \"%~1\"==\"\" (echo JasperReports Server import file[path-to-file-and-filename]"
            + " expected as input & exit /b 1)\r\n"
            + "if not exist \"%~1\" (echo import file %~1 does not exist & exit /b 1)\r\n"
            + "call \"%~dp0js-ant.bat\" upgrade-minimal-pro -Dstrategy=standard"
            + " \"-DimportFile=%~1\"\r\n"
            + "exit /b %errorlevel%\r\n");
    write(
        buildomatic.resolve("js-upgrade-newdb.sh"),
        "#!/bin/sh\n"
            + "if [ \"$1\" = \"test\" ]; then exec \"$(dirname \"$0\")/js-ant.sh\""
            + " pre-upgrade-test-pro -Dstrategy=standard; fi\n"
            + "if [ -z \"$1\" ]; then echo \"JasperReports Server import file expected as"
            + " input\"; exit 1; fi\n"
            + "if [ ! -f \"$1\" ]; then echo \"import file $1 does not exist\"; exit 1; fi\n"
            + "exec \"$(dirname \"$0\")/js-ant.sh\" upgrade-minimal-pro -Dstrategy=standard"
            + " \"-DimportFile=$1\"\n");
    write(
        buildomatic.resolve("js-upgrade-samedb.bat"),
        "@echo off\r\n"
            + "call \"%~dp0js-ant.bat\" upgrade-minimal-pro -Dstrategy=inDatabase\r\n"
            + "exit /b %errorlevel%\r\n");
    write(
        buildomatic.resolve("js-upgrade-samedb.sh"),
        "#!/bin/sh\n"
            + "exec \"$(dirname \"$0\")/js-ant.sh\" upgrade-minimal-pro -Dstrategy=inDatabase\n");
  }

  private static Path fakePackage(Path pkg) throws IOException {
    Path webappNew = pkg.resolve("webapp-new");
    write(webappNew.resolve(MARKER), "upgraded to " + targetVersion);
    write(webappNew.resolve("version.txt"), targetVersion);
    Files.createDirectories(pkg.resolve("jasperserver-pro").resolve("WEB-INF").resolve("lib"));
    write(
        pkg.resolve("jasperserver-pro").resolve("WEB-INF").resolve("lib").resolve("lib.jar"),
        "lib");
    vendorScripts(pkg.resolve("buildomatic"), Optional.of(webappNew));
    return pkg;
  }

  /** Picks the vendor JDK and, from its major version, the target version of the upgrade. */
  private static Path vendorJdk() throws Exception {
    if (!WINDOWS) {
      Path fake = tmp.resolve("jdk17");
      write(
          fake.resolve("bin").resolve("java"),
          "#!/bin/sh\necho 'openjdk version \"17.0.2\" 2022-01-18' 1>&2\nexit 0\n");
      targetVersion = "9.0.0";
      return fake;
    }
    List<Path> candidates = new ArrayList<>();
    for (String env : List.of("JRSCTL_VENDOR_JDK", "JAVA_HOME_17_X64", "JAVA_HOME_11_X64")) {
      String v = System.getenv(env);
      if (v != null && !v.isBlank()) {
        candidates.add(Path.of(v));
      }
    }
    for (String root :
        List.of(
            "C:\\Program Files\\Java",
            "C:\\Program Files\\Microsoft",
            "C:\\Program Files\\Eclipse Adoptium",
            "C:\\Program Files\\Amazon Corretto",
            "C:\\Program Files\\Zulu",
            "C:\\")) {
      Path dir = Path.of(root);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, "*jdk*")) {
        for (Path child : children) {
          candidates.add(child);
        }
      }
    }
    Optional<Path> jdk11 = Optional.empty();
    for (Path candidate : candidates) {
      Optional<Integer> major = javaMajor(candidate);
      if (major.isEmpty()) {
        continue;
      }
      if (major.get() == 17) {
        targetVersion = "9.0.0";
        return candidate;
      }
      if (major.get() == 11 && jdk11.isEmpty()) {
        jdk11 = Optional.of(candidate);
      }
    }
    if (jdk11.isPresent()) {
      targetVersion = "8.3.0";
      return jdk11.get();
    }
    Assumptions.abort(
        "no JDK 17 or 11 found for the vendor Java check; set JRSCTL_VENDOR_JDK to one");
    throw new IllegalStateException("unreachable");
  }

  private static Optional<Integer> javaMajor(Path javaHome) {
    Path java = javaHome.resolve("bin").resolve("java.exe");
    if (!Files.isRegularFile(java)) {
      return Optional.empty();
    }
    try {
      Process p = new ProcessBuilder(java.toString(), "-version").redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      p.waitFor(30, TimeUnit.SECONDS);
      Matcher m = BANNER.matcher(out);
      if (!m.find()) {
        return Optional.empty();
      }
      int major = Integer.parseInt(m.group(1));
      return Optional.of(major == 1 && m.group(2) != null ? Integer.parseInt(m.group(2)) : major);
    } catch (IOException | InterruptedException e) {
      return Optional.empty();
    }
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

  private static Path snapshotDir(String runId) {
    return home.resolve("snapshots").resolve(runId);
  }

  // ---------------------------------------------------------------- cases

  @Test
  @Order(1)
  void upgrade_plan_prints_the_five_phases_and_the_newdb_database_warning() throws Exception {
    // newdb takes its own full export and its rollback rebuilds the database from it (ADR-0029),
    // so it asks for no backup confirmation; samedb still does (order 2).
    Cli.Result plan =
        jrsctl("upgrade", "--to", targetVersion, "--package", pkg.toString(), "--plan")
            .assertExit(0);

    assertThat(plan.stdout())
        .contains("Plan  upgrade")
        .contains("preflight")
        .doesNotContain("confirm the operator backed up")
        .contains("backup")
        .contains("vendor-upgrade")
        .contains("reconcile")
        .contains("verify")
        .contains("js-upgrade-newdb drops and recreates the repository database")
        .contains("upgrade rollback --restore-database rebuilds it")
        .doesNotContain("Rollback restores files only")
        .contains("nothing has changed");
    assertThat(plan.stdout()).doesNotContain("complete file and connection restore");
    assertThat(webapp.resolve(MARKER)).doesNotExist();
    assertThat(webapp.resolve("version.txt")).hasContent("8.2.0");
  }

  @Test
  @Order(2)
  void samedb_without_db_backup_confirmed_exits_2_with_the_gate_message() throws Exception {
    Cli.Result refused =
        jrsctl(
                "upgrade",
                "--to",
                targetVersion,
                "--package",
                pkg.toString(),
                "--mode",
                "samedb",
                "--plan")
            .assertExit(2);

    assertThat(refused.stderr()).contains("--db-backup-confirmed").contains("cannot undo");
    assertThat(webapp.resolve(MARKER)).doesNotExist();

    Cli.Result confirmed =
        jrsctl(
                "upgrade",
                "--to",
                targetVersion,
                "--package",
                pkg.toString(),
                "--mode",
                "samedb",
                "--db-backup-confirmed",
                "--plan")
            .assertExit(0);
    assertThat(confirmed.stdout())
        .contains("confirm the operator backed up")
        .contains(
            "Rollback restores files only. Restore the database from your own backup before"
                + " running rollback.");
  }

  @Test
  @Order(2)
  void upgrade_test_rehearses_with_the_vendor_validation_and_changes_nothing() throws Exception {
    // spec §10.2 "Rehearsal": js-upgrade-newdb test, no stop, no backup, package left as found
    Cli.Result plan =
        jrsctl("upgrade", "--to", targetVersion, "--package", pkg.toString(), "--test", "--plan")
            .assertExit(0);
    assertThat(plan.stdout())
        .contains("Plan  upgrade.test")
        .contains("rehearse with the vendor's validation (js-upgrade-newdb test)")
        .contains("leave the target package as it was found")
        .contains("nothing is backed up and nothing is changed")
        .doesNotContain("stop the JasperReports Server service")
        .doesNotContain("full repository export");

    Cli.Result run =
        jrsctl("upgrade", "--to", targetVersion, "--package", pkg.toString(), "--test", "--yes")
            .assertExit(0);
    assertThat(run.stdout()).contains("succeeded").contains("rehearsal ok; nothing changed");
    assertThat(webapp.resolve(MARKER)).doesNotExist();
    assertThat(webapp.resolve("version.txt")).hasContent("8.2.0");
    assertThat(pkg.resolve("buildomatic").resolve("default_master.properties")).doesNotExist();
    assertThat(pkg.resolve("buildomatic").resolve("keystore.init.properties")).doesNotExist();
  }

  @Test
  @Order(2)
  void upgrade_export_adopts_an_archive_taken_earlier_and_is_refused_for_samedb() throws Exception {
    // ADR-0028 (field test 2, U5b): an export taken earlier stands in for the run's own.
    Path earlier = tmp.resolve("earlier-export.zip");
    try (java.util.zip.ZipOutputStream out =
        new java.util.zip.ZipOutputStream(Files.newOutputStream(earlier))) {
      out.putNextEntry(new java.util.zip.ZipEntry("index.xml"));
      out.write("<export/>".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }

    Cli.Result plan =
        jrsctl(
                "upgrade",
                "--to",
                targetVersion,
                "--package",
                pkg.toString(),
                "--db-backup-confirmed",
                "--export",
                earlier.toString(),
                "--key-alias",
                "deprecatedImportExportEncSecret",
                "--plan")
            .assertExit(0);
    assertThat(plan.stdout())
        .contains("adopt the export taken earlier")
        .contains("every change made in the repository after that export")
        .contains(earlier.getFileName().toString())
        .doesNotContain("full repository export with js-export");

    Cli.Result samedb =
        jrsctl(
                "upgrade",
                "--to",
                targetVersion,
                "--package",
                pkg.toString(),
                "--mode",
                "samedb",
                "--db-backup-confirmed",
                "--export",
                earlier.toString(),
                "--plan")
            .assertExit(1);
    assertThat(samedb.stderr()).contains("--export is only used by a newdb upgrade");

    Cli.Result aliasAlone =
        jrsctl(
                "upgrade",
                "--to",
                targetVersion,
                "--package",
                pkg.toString(),
                "--db-backup-confirmed",
                "--key-alias",
                "deprecatedImportExportEncSecret",
                "--plan")
            .assertExit(1);
    assertThat(aliasAlone.stderr()).contains("--export");
    assertThat(webapp.resolve(MARKER)).doesNotExist();
  }

  @Test
  @Order(3)
  void upgrade_yes_runs_the_vendor_script_and_keeps_point_b_backups() throws Exception {
    // The package's js-upgrade-newdb refuses to run without an existing export file, so a green
    // run proves jrsctl passed the point-B full export (ADR-0012).
    Cli.Result run =
        jrsctl(
                "upgrade",
                "--to",
                targetVersion,
                "--package",
                pkg.toString(),
                "--db-backup-confirmed",
                "--yes")
            .assertExit(0);

    assertThat(run.stdout()).contains("succeeded");
    assertThat(webapp.resolve(MARKER)).exists();
    assertThat(webapp.resolve("version.txt")).hasContent(targetVersion);

    Cli.Result runs = jrsctl("runs", "list", "--json").assertExit(0);
    JsonNode rows = new ObjectMapper().readTree(runs.stdout());
    JsonNode upgrade = null;
    for (JsonNode row : rows) {
      if (row.get("operation").asText().equals("upgrade")) {
        upgrade = row;
        break;
      }
    }
    assertThat(upgrade).as("an upgrade run is recorded: " + runs.stdout()).isNotNull();
    assertThat(upgrade.get("terminalState").asText()).isEqualTo("SUCCEEDED");
    upgradeRunId = upgrade.get("runId").asText();

    Path snapshots = snapshotDir(upgradeRunId);
    assertThat(snapshots.resolve("full-export.zip")).exists();
    assertThat(snapshots.resolve("backup-webapp")).isDirectory();
    try (DirectoryStream<Path> archives =
        Files.newDirectoryStream(snapshots.resolve("backup-webapp"), "webapp.*")) {
      assertThat(archives.iterator().hasNext()).as("webapp archive present").isTrue();
    }
    assertThat(snapshots.resolve("backup-config").resolve("manifest.json")).exists();
    assertThat(snapshots.resolve("backup-keystore").resolve("manifest.json")).exists();
    assertThat(snapshots.resolve("upgrade.json")).exists();
    String master =
        Files.readString(
            pkg.resolve("buildomatic").resolve("default_master.properties"),
            StandardCharsets.UTF_8);
    assertThat(master).contains("dbHost=localhost").doesNotContain("TopSecret");
    assertThat(jrsctl("runs", "show", upgradeRunId).assertExit(0).stdout())
        .contains("run-vendor-upgrade")
        .contains("SUCCEEDED");
  }

  @Test
  @Order(4)
  void upgrade_rollback_to_point_b_restores_the_previous_webapp() throws Exception {
    assertThat(upgradeRunId).as("order 3 recorded the upgrade run id").isNotNull();

    Cli.Result plan =
        jrsctl("upgrade", "rollback", upgradeRunId, "--to-point", "B", "--plan").assertExit(0);
    assertThat(plan.stdout())
        .contains("Plan  upgrade.rollback")
        .contains("restore the webapp")
        .contains("Rollback restores files only")
        .contains("re-run with --restore-database");
    assertThat(webapp.resolve(MARKER)).exists();
    // issue #186: the trailing hex of the run id names the same run
    String shortId = upgradeRunId.substring(upgradeRunId.lastIndexOf('-') + 1);
    assertThat(
            jrsctl("upgrade", "rollback", shortId, "--to-point", "B", "--plan")
                .assertExit(0)
                .stdout())
        .contains("Plan  upgrade.rollback")
        .contains("restore the webapp");

    // ADR-0029: the newdb database is rebuilt from the point-B export with the restored buildomatic
    Cli.Result withDatabase =
        jrsctl(
                "upgrade",
                "rollback",
                upgradeRunId,
                "--to-point",
                "B",
                "--restore-database",
                "--plan")
            .assertExit(0);
    assertThat(withDatabase.stdout())
        .contains("rebuild the old repository database")
        .contains("re-import the point-B export")
        .contains("rebuilds the repository database from")
        .doesNotContain("Rollback restores files only");

    Cli.Result rolled =
        jrsctl(
                "upgrade",
                "rollback",
                upgradeRunId,
                "--to-point",
                "B",
                "--restore-database",
                "--yes")
            .assertExit(0);

    assertThat(rolled.stdout()).contains("succeeded");
    assertThat(webapp.resolve(MARKER)).doesNotExist();
    assertThat(webapp.resolve("version.txt")).hasContent("8.2.0");
    assertThat(webapp.resolve("WEB-INF").resolve("lib").resolve("x.jar")).exists();
    String vendorLog =
        Files.readString(installedBuildomatic.resolve("vendor.log"), StandardCharsets.UTF_8);
    assertThat(vendorLog)
        .contains("init-js-db-pro")
        .contains("js-import --input-zip")
        .contains("full-export.zip");
    assertThat(vendorLog.indexOf("init-js-db-pro")).isLessThan(vendorLog.indexOf("js-import"));
  }

  @Test
  @Order(5)
  void customizations_register_list_and_diff_track_a_file() throws Exception {
    Path custom = webapp.resolve("WEB-INF").resolve("classes").resolve("custom.properties");
    Files.writeString(custom, "a=1\nb=2\n", StandardCharsets.UTF_8);

    assertThat(jrsctl("customizations", "register", custom.toString()).assertExit(0).stdout())
        .contains("registered");
    assertThat(jrsctl("customizations", "list").assertExit(0).stdout())
        .contains("custom.properties");
    jrsctl("customizations", "diff", custom.toString()).assertExit(0);

    Files.writeString(custom, "a=1\nb=3\n", StandardCharsets.UTF_8);
    Cli.Result diff = jrsctl("customizations", "diff", custom.toString()).assertExit(1);
    assertThat(diff.stdout()).contains("-b=2").contains("+b=3");

    Path outside = tmp.resolve("outside.txt");
    Files.writeString(outside, "x", StandardCharsets.UTF_8);
    assertThat(jrsctl("customizations", "register", outside.toString()).assertExit(2).stderr())
        .contains("outside the installation");

    assertThat(jrsctl("customizations", "unregister", custom.toString()).assertExit(0).stdout())
        .contains("unregistered");
    assertThat(jrsctl("customizations", "list").assertExit(0).stdout())
        .contains("no customizations registered");
  }
}
