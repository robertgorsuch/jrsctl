package com.jaspersoft.jrsctl.jrs.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jaspersoft.jrsctl.core.engine.Runner;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.jrs.CapturingRunner;
import com.jaspersoft.jrsctl.jrs.FakePlatform;
import com.jaspersoft.jrsctl.jrs.RecordingSink;
import com.jaspersoft.jrsctl.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class VendorToolsTest {

  @TempDir Path tmp;

  private final CapturingRunner runner = new CapturingRunner();
  private final Redactor redactor = new Redactor();
  private final RecordingSink sink = new RecordingSink();
  private final VendorTools.LogScope scope =
      new VendorTools.LogScope("run-1", Optional.of("export.js-export"), "export");
  private Platform platform;
  private Buildomatic buildomatic;
  private Path javaHome;

  @BeforeEach
  void setUp() throws IOException {
    platform = new FakePlatform(Platform.OsFamily.LINUX, runner);
    Path dir = tmp.resolve("install").resolve("buildomatic");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("js-export.sh"), "#!/bin/sh\n");
    Files.writeString(dir.resolve("js-import.sh"), "#!/bin/sh\n");
    Files.writeString(dir.resolve("js-ant.sh"), "#!/bin/sh\n");
    buildomatic = new BuildomaticLocator(platform).locate(tmp.resolve("install")).orElseThrow();
    javaHome = tmp.resolve("jdk");
    Files.createDirectories(javaHome);
  }

  private VendorTools tools() {
    return new VendorTools(runner, platform.files(), redactor);
  }

  private static ExportRequest export(
      ExportRequest.Scope scope,
      Set<String> uris,
      boolean usersRoles,
      boolean access,
      boolean audit,
      boolean monitoring,
      boolean settings,
      boolean fullServer,
      Path out) {
    return new ExportRequest(
        scope, uris, usersRoles, access, audit, monitoring, settings, fullServer, out);
  }

  @Test
  void should_build_everything_export_args_when_full_server() {
    Path out = Path.of("full.zip");
    ExportRequest r =
        export(ExportRequest.Scope.REPOSITORY, Set.of(), true, true, true, true, true, true, out);

    assertThat(VendorTools.exportArgs(r, out))
        .containsExactly(
            "--output-zip",
            "full.zip",
            "--everything",
            "--users",
            "--roles",
            "--include-access-events",
            "--include-audit-events",
            "--include-monitoring-events",
            "--include-server-settings");
  }

  @Test
  void should_build_uri_export_args_when_repository_scope() {
    Path out = Path.of("part.zip");
    ExportRequest r =
        export(
            ExportRequest.Scope.REPOSITORY,
            Set.of("/public", "/organizations"),
            false,
            false,
            false,
            false,
            false,
            false,
            out);

    assertThat(VendorTools.exportArgs(r, out))
        .containsExactly(
            "--output-zip",
            "part.zip",
            "--uris",
            "/organizations,/public",
            "--repository-permissions");
  }

  @Test
  void should_export_root_when_no_uris_given() {
    Path out = Path.of("root.zip");
    ExportRequest r =
        export(
            ExportRequest.Scope.REPOSITORY,
            Set.of(),
            false,
            false,
            false,
            false,
            false,
            false,
            out);

    assertThat(VendorTools.exportArgs(r, out)).containsSequence("--uris", "/");
  }

  /** REST API reference 10.1 p.111, vendor doc review §4.2, issue #144. */
  @Test
  void should_pass_skip_dependent_and_favorite_resource_flags_when_requested() {
    Path out = Path.of("skip.zip");
    ExportRequest r =
        new ExportRequest(
            ExportRequest.Scope.REPOSITORY,
            Set.of("/public/report.jrxml"),
            false,
            false,
            false,
            false,
            false,
            false,
            out,
            true,
            Optional.empty(),
            Optional.empty(),
            true,
            true);

    assertThat(VendorTools.exportArgs(r, out))
        .containsExactly(
            "--output-zip",
            "skip.zip",
            "--uris",
            "/public/report.jrxml",
            "--repository-permissions",
            "--skip-dependent-resources",
            "--skip-favorite-resources");
  }

  /** Field test 2, E4 and I1: a named key reaches both vendor scripts as {@code --keyalias}. */
  @Test
  void should_pass_the_key_alias_to_js_export_and_js_import_when_given() {
    ExportRequest export =
        new ExportRequest(
                ExportRequest.Scope.EVERYTHING,
                Set.of(),
                false,
                false,
                false,
                false,
                false,
                true,
                Path.of("out.zip"))
            .withKeyAlias("deprecatedImportExportEncSecret");
    ImportRequest importRequest =
        new ImportRequest(
                Path.of("in.zip"),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                Optional.empty(),
                Optional.empty())
            .withKeyAlias("deprecatedImportExportEncSecret");

    assertThat(VendorTools.exportArgs(export, Path.of("out.zip")))
        .containsSequence("--keyalias", "deprecatedImportExportEncSecret");
    assertThat(VendorTools.importArgs(importRequest))
        .containsSequence("--keyalias", "deprecatedImportExportEncSecret");
  }

  /** Field test 2, E5 and I4: one organisation on export, the target one on import, merged. */
  @Test
  void should_pass_the_organisation_to_js_export_and_js_import_when_given() {
    ExportRequest export =
        new ExportRequest(
                ExportRequest.Scope.REPOSITORY,
                Set.of("/reports"),
                false,
                false,
                false,
                false,
                false,
                false,
                Path.of("out.zip"))
            .withOrganization("org1");
    ImportRequest plain =
        new ImportRequest(
                Path.of("in.zip"),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                Optional.empty(),
                Optional.empty())
            .withOrganization("org1", false);

    assertThat(VendorTools.exportArgs(export, Path.of("out.zip")))
        .containsSequence("--organization", "org1");
    assertThat(VendorTools.importArgs(plain))
        .containsSequence("--organization", "org1")
        .doesNotContain("--merge-organization");
    assertThat(VendorTools.importArgs(plain.withOrganization("org1", true)))
        .containsSequence("--organization", "org1", "--merge-organization");
  }

  @Test
  void should_build_all_import_args_when_every_flag_set() {
    ImportRequest r =
        new ImportRequest(
            Path.of("in.zip"),
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            Optional.empty(),
            Optional.empty());

    assertThat(VendorTools.importArgs(r))
        .containsExactly(
            "--input-zip",
            "in.zip",
            "--update",
            "--skip-user-update",
            "--include-access-events",
            "--include-audit-events",
            "--include-monitoring-events",
            "--include-server-settings",
            "--skip-themes");
  }

  @Test
  void should_pass_broken_dependencies_to_js_import_when_not_the_default() {
    ImportRequest skip =
        new ImportRequest(
            Path.of("in.zip"),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.empty(),
            Optional.empty(),
            BrokenDependencies.SKIP);
    ImportRequest include =
        new ImportRequest(
            Path.of("in.zip"),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.empty(),
            Optional.empty(),
            BrokenDependencies.INCLUDE);

    assertThat(VendorTools.importArgs(skip))
        .containsExactly("--input-zip", "in.zip", "--broken-dependencies", "skip");
    assertThat(VendorTools.importArgs(include))
        .containsExactly("--input-zip", "in.zip", "--broken-dependencies", "include");
  }

  @Test
  void should_build_minimal_import_args_when_no_flag_set() {
    ImportRequest r =
        new ImportRequest(
            Path.of("in.zip"),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.empty(),
            Optional.empty());

    assertThat(VendorTools.importArgs(r)).containsExactly("--input-zip", "in.zip");
  }

  @Test
  void should_append_keystore_args_when_source_keystore_and_password_present() {
    ImportRequest r =
        new ImportRequest(
            Path.of("in.zip"),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.of(Path.of("src.jrsks")),
            Optional.empty());
    try (Secret pw = Secret.fromString("st0re-pass")) {
      assertThat(VendorTools.importArgs(r, Optional.of(pw)))
          .containsExactly(
              "--input-zip", "in.zip", "--keystore", "src.jrsks", "--storepass", "st0re-pass");
    }
    assertThat(VendorTools.keystoreArgs(Path.of("k.jrsks"), Optional.empty()))
        .containsExactly("--keystore", "k.jrsks");
  }

  /** Issue #39: js-export.bat never passes js.cache.provider; the wrappers append JAVA_OPTS. */
  @Test
  void should_add_cache_provider_to_java_opts_when_inherited_java_opts_lacks_it() {
    assertThat(VendorTools.javaOptsWith(Map.of(), "infinispan"))
        .isEqualTo("-Djs.cache.provider=infinispan");
    assertThat(VendorTools.javaOptsWith(Map.of("JAVA_OPTS", " -Xmx2g "), "infinispan"))
        .isEqualTo("-Xmx2g -Djs.cache.provider=infinispan");
  }

  @Test
  void should_keep_inherited_java_opts_when_it_already_names_the_cache_provider() {
    assertThat(
            VendorTools.javaOptsWith(
                Map.of("JAVA_OPTS", "-Djs.cache.provider=ehcache -Xmx1g"), "infinispan"))
        .isEqualTo("-Djs.cache.provider=ehcache -Xmx1g");
  }

  /** Issue #47: jrsctl's own variables and configured env: secrets never reach buildomatic. */
  @Test
  void should_withhold_jrsctl_and_configured_secret_variables_when_running_a_vendor_tool() {
    Map<String, String> inherited =
        Map.of(
            "JRSCTL_PASSPHRASE", "p",
            "jrsctl_home", "h",
            "JRS_PASSWORD", "s",
            "ANT_OPTS", "-Xmx1g",
            "PATH", "/bin");

    assertThat(VendorTools.withheldNames(inherited, Set.of("jrs_password")))
        .containsExactlyInAnyOrder("JRSCTL_PASSPHRASE", "jrsctl_home", "JRS_PASSWORD");
  }

  @Test
  void should_pass_java_home_and_buildomatic_working_dir_when_running_export() {
    runner.exit(0, "Export finished");
    Path out = tmp.resolve("x.zip");
    ExportRequest r =
        export(
            ExportRequest.Scope.REPOSITORY,
            Set.of("/a"),
            false,
            false,
            false,
            false,
            false,
            false,
            out);

    VendorRun run = tools().export(buildomatic, r, out, Optional.of(javaHome), sink, scope);

    assertThat(run).isInstanceOf(VendorRun.Completed.class);
    assertThat(((VendorRun.Completed) run).ok()).isTrue();
    ProcessRunner.Request req = runner.last();
    assertThat(req.command().get(0))
        .isEqualTo(buildomatic.dir().resolve("js-export.sh").toString());
    assertThat(req.command().subList(1, req.command().size()))
        .isEqualTo(VendorTools.exportArgs(r, out));
    assertThat(req.workingDir()).contains(buildomatic.dir());
    assertThat(req.environment()).containsEntry("JAVA_HOME", javaHome.toString());
    assertThat(req.environment().get("PATH")).startsWith(javaHome.resolve("bin").toString());
    assertThat(req.environment().get("JAVA_OPTS")).contains("-Djs.cache.provider=");
    assertThat(req.unset()).noneMatch(n -> n.equalsIgnoreCase("PATH"));
    assertThat(req.timeout()).isEqualTo(VendorTools.DEFAULT_TIMEOUT);
    assertThat(sink.logMessages()).contains("Export finished");
    assertThat(sink.of(Event.Log.class))
        .allSatisfy(
            l -> {
              assertThat(l.runId()).isEqualTo("run-1");
              assertThat(l.stepId()).contains("export.js-export");
              assertThat(l.phase()).isEqualTo("export");
            });
  }

  /**
   * Review §3.4 (issue #111): the run names the buildomatic log it wrote, the vendor's first clue.
   */
  @Test
  void should_name_the_buildomatic_log_the_run_wrote_when_one_appears() throws IOException {
    runner.exit(0, "Export finished");
    Path logs = Files.createDirectories(buildomatic.dir().resolve("logs"));
    Path stale = Files.writeString(logs.resolve("js-export-pro_2026-09-01.log"), "old");
    Files.setLastModifiedTime(
        stale, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000L));
    Path fresh = Files.writeString(logs.resolve("js-export-pro_2026-09-19.log"), "new");
    Path out = tmp.resolve("x.zip");
    ExportRequest r =
        export(
            ExportRequest.Scope.REPOSITORY,
            Set.of("/a"),
            false,
            false,
            false,
            false,
            false,
            false,
            out);

    tools().export(buildomatic, r, out, Optional.of(javaHome), sink, scope);

    assertThat(sink.logMessages()).contains(VendorTools.BUILDOMATIC_LOG_PREFIX + fresh);
    assertThat(sink.logMessages()).noneMatch(m -> m.contains(stale.toString()));
  }

  @Test
  void should_name_no_log_when_the_run_wrote_none() {
    runner.exit(0, "Export finished");
    Path out = tmp.resolve("x.zip");
    ExportRequest r =
        export(
            ExportRequest.Scope.REPOSITORY,
            Set.of("/a"),
            false,
            false,
            false,
            false,
            false,
            false,
            out);

    tools().export(buildomatic, r, out, Optional.of(javaHome), sink, scope);

    assertThat(sink.logMessages()).noneMatch(m -> m.startsWith(VendorTools.BUILDOMATIC_LOG_PREFIX));
  }

  /**
   * Review finding 2.8: {@code js-export.bat} re-reads its arguments as {@code %1} tokens, where a
   * comma, a semicolon or an equals sign splits, so {@code --uris /a,/b} arrived as three
   * arguments. On a batch wrapper such a value is wrapped in double quotes, which cmd keeps as one
   * token and strips before the importer sees it.
   */
  @Test
  void should_quote_arguments_that_cmd_would_split_when_the_wrapper_is_a_batch_file()
      throws IOException {
    CapturingRunner windowsRunner = new CapturingRunner().exit(0, "Export finished");
    Platform windows = new FakePlatform(Platform.OsFamily.WINDOWS, windowsRunner);
    Path dir = tmp.resolve("win").resolve("buildomatic");
    Files.createDirectories(dir);
    for (String name : List.of("js-export.bat", "js-import.bat", "js-ant.bat")) {
      Files.writeString(dir.resolve(name), "@echo off\r\n");
    }
    Buildomatic bat = new BuildomaticLocator(windows).locate(tmp.resolve("win")).orElseThrow();
    Path out = tmp.resolve("x&y.zip");
    ExportRequest r =
        export(
            ExportRequest.Scope.REPOSITORY,
            Set.of("/b", "/a"),
            false,
            false,
            false,
            false,
            false,
            false,
            out);

    new VendorTools(windowsRunner, windows.files(), redactor)
        .export(bat, r, out, Optional.of(javaHome), sink, scope);

    List<String> command = windowsRunner.last().command();
    assertThat(command).containsSequence(VendorFlags.URIS, "\"/a,/b\"");
    assertThat(command).doesNotContain("/a,/b");
    assertThat(command)
        .as("an ampersand ends a cmd command unless quoted")
        .containsSequence(VendorFlags.OUTPUT_ZIP, "\"" + out + "\"")
        .doesNotContain(out.toString());
  }

  @Test
  void should_report_a_failure_when_ant_says_build_failed_but_the_wrapper_exits_zero() {
    runner.exit(0, "some noise", "BUILD FAILED", "js-import.sh returns anyway");

    VendorRun run =
        tools()
            .ant(buildomatic, "validate-keystore", List.of(), Optional.of(javaHome), sink, scope);

    VendorRun.Completed c = (VendorRun.Completed) run;
    assertThat(c.exitCode()).isZero();
    assertThat(c.reported()).isEqualTo(VendorRun.Reported.FAILED);
    assertThat(c.ok()).isFalse();
  }

  @Test
  void should_report_success_when_buildomatic_prints_its_validation_banner() {
    runner.exit(0, "VALIDATION COMPLETED", "Import finished");

    VendorRun run =
        tools()
            .ant(buildomatic, "validate-keystore", List.of(), Optional.of(javaHome), sink, scope);

    VendorRun.Completed c = (VendorRun.Completed) run;
    assertThat(c.reported()).isEqualTo(VendorRun.Reported.SUCCEEDED);
    assertThat(c.ok()).isTrue();
  }

  @Test
  void should_report_silence_when_the_tool_prints_no_build_banner() {
    runner.exit(0, "nothing to say");

    VendorRun run =
        tools()
            .ant(buildomatic, "validate-keystore", List.of(), Optional.of(javaHome), sink, scope);

    VendorRun.Completed c = (VendorRun.Completed) run;
    assertThat(c.reported()).isEqualTo(VendorRun.Reported.SILENT);
    assertThat(c.processing()).isEqualTo(VendorRun.Processing.NOT_STARTED);
    assertThat(c.ok()).isTrue();
    assertThat(c.processed()).isFalse();
  }

  private ImportRequest plainImport() {
    return new ImportRequest(
        tmp.resolve("in.zip"),
        true,
        false,
        false,
        false,
        false,
        false,
        false,
        Optional.empty(),
        Optional.empty());
  }

  private VendorRun.Completed importPrinting(String... lines) {
    runner.exit(0, lines);
    return (VendorRun.Completed)
        tools()
            .importArchive(
                buildomatic, plainImport(), Optional.empty(), Optional.of(javaHome), sink, scope);
  }

  /**
   * Review §1.4: buildomatic's setup.xml prints this line (interactive: as a y/n prompt; otherwise
   * as a warning) and then creates a keystore. A run that did so is a failure whatever it exited
   * with: the repository's passwords are now encrypted with a key nobody has.
   */
  @Test
  void should_report_a_created_keystore_when_the_script_announces_one() {
    VendorRun.Completed c =
        importPrinting(
            "WARNING: A new encryption key and a new keystore are about to be created.",
            "BUILD SUCCESSFUL",
            "Processing started",
            "Done");

    assertThat(c.reported()).isEqualTo(VendorRun.Reported.CREATED_KEYSTORE);
    assertThat(c.ok()).isFalse();
    assertThat(c.processed()).isFalse();
    assertThat(c.summary()).contains("created a new keystore");
  }

  /**
   * Issue #40: Ant's validation succeeded, the import command then threw, and the wrapper still
   * exited 0, so the import was recorded as done.
   */
  @Test
  void should_report_a_failed_command_when_the_import_throws_after_a_successful_validation() {
    VendorRun.Completed c =
        importPrinting(
            "BUILD SUCCESSFUL",
            "Processing started",
            "2026-09-14T17:33:15,565 ERROR BaseExportImportCommand:45 -"
                + " java.lang.NullPointerException: entry",
            "Done");

    assertThat(c.exitCode()).isZero();
    assertThat(c.reported()).isEqualTo(VendorRun.Reported.SUCCEEDED);
    assertThat(c.processing()).isEqualTo(VendorRun.Processing.FAILED);
    assertThat(c.ok()).isFalse();
    assertThat(c.processed()).isFalse();
    assertThat(c.summary()).contains("reported an error");
    assertThat(sink.logMessages()).anyMatch(m -> m.contains("treating it as a failure"));
  }

  @Test
  void should_report_a_processed_run_when_done_follows_processing_started() {
    VendorRun.Completed plain = importPrinting("BUILD SUCCESSFUL", "Processing started", "Done");
    VendorRun.Completed prefixed =
        importPrinting("Processing started", "2026-09-14T17:40:00,001 INFO  command:1 - Done");

    assertThat(plain.processing()).isEqualTo(VendorRun.Processing.COMPLETED);
    assertThat(plain.processed()).isTrue();
    assertThat(plain.summary()).isEqualTo("completed");
    assertThat(prefixed.processing()).isEqualTo(VendorRun.Processing.COMPLETED);
  }

  @Test
  void should_not_count_the_run_as_processed_when_done_is_missing_or_comes_before_the_start() {
    VendorRun.Completed unfinished = importPrinting("BUILD SUCCESSFUL", "Processing started");
    VendorRun.Completed early = importPrinting("Done", "Processing started");
    VendorRun.Completed never = importPrinting("BUILD SUCCESSFUL", "Done");

    assertThat(unfinished.processing()).isEqualTo(VendorRun.Processing.UNFINISHED);
    assertThat(unfinished.ok()).isFalse();
    assertThat(early.processing()).isEqualTo(VendorRun.Processing.UNFINISHED);
    assertThat(never.processing()).isEqualTo(VendorRun.Processing.NOT_STARTED);
    assertThat(never.ok()).isTrue();
    assertThat(never.processed()).isFalse();
    assertThat(never.summary()).contains("never printed Processing started");
  }

  @Test
  void should_redact_streamed_lines_when_they_contain_registered_secret() {
    redactor.register("s3cretValue");
    runner.answer(
        (request, onLine) -> {
          onLine.accept(
              new ProcessRunner.OutputLine(
                  ProcessRunner.OutputLine.Stream.STDERR, "dbPassword=s3cretValue failed"));
          return new ProcessRunner.Result(1, false, Duration.ofMillis(3));
        });

    VendorRun run =
        tools()
            .ant(buildomatic, "import-minimal-ce", List.of(), Optional.of(javaHome), sink, scope);

    assertThat(run).isInstanceOf(VendorRun.Completed.class);
    VendorRun.Completed c = (VendorRun.Completed) run;
    assertThat(c.ok()).isFalse();
    assertThat(c.exitCode()).isEqualTo(1);
    assertThat(c.tail()).hasSize(1);
    assertThat(String.join("\n", sink.logMessages()) + String.join("\n", c.tail()))
        .doesNotContain("s3cretValue")
        .contains(Redactor.MASK);
    assertThat(sink.of(Event.Log.class).get(1).level()).isEqualTo(Event.Log.Level.WARN);
  }

  /**
   * Issue #200: js-export.bat tees only its validate-keystore step into the buildomatic log, so the
   * export's own output reached nothing but the console and a 20-line tail. Every line now goes to
   * the JSON log, redacted and tagged with the run id, which is what a support bundle collects.
   */
  @Test
  void should_write_every_output_line_to_the_log_with_the_run_id_when_a_vendor_tool_runs() {
    redactor.register("s3cretValue");
    runner.answer(
        (request, onLine) -> {
          for (int i = 1; i <= 30; i++) {
            onLine.accept(
                new ProcessRunner.OutputLine(ProcessRunner.OutputLine.Stream.STDOUT, "line " + i));
          }
          onLine.accept(
              new ProcessRunner.OutputLine(
                  ProcessRunner.OutputLine.Stream.STDERR, "dbPassword=s3cretValue failed"));
          return new ProcessRunner.Result(1, false, Duration.ofMillis(3));
        });
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger logger = (Logger) LoggerFactory.getLogger(VendorTools.OUTPUT_LOGGER);
    logger.addAppender(appender);
    MDC.put(Runner.MDC_RUN_ID, "run-1");
    try {
      tools().ant(buildomatic, "import-minimal-ce", List.of(), Optional.of(javaHome), sink, scope);

      assertThat(MDC.get(Runner.MDC_RUN_ID))
          .as("the calling thread keeps its run id")
          .isEqualTo("run-1");
    } finally {
      MDC.remove(Runner.MDC_RUN_ID);
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .hasSize(31)
        .allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO))
        .allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry("runId", "run-1"));
    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .startsWith("js-ant: line 1")
        .contains("js-ant: line 30")
        .last()
        .satisfies(
            m ->
                assertThat(m)
                    .startsWith("js-ant stderr: ")
                    .contains(Redactor.MASK)
                    .doesNotContain("s3cretValue"));
  }

  @Test
  void should_not_leave_a_run_id_behind_when_the_calling_thread_had_none() {
    runner.exit(0, "BUILD SUCCESSFUL");

    tools().ant(buildomatic, "import-minimal-ce", List.of(), Optional.of(javaHome), sink, scope);

    assertThat(MDC.get(Runner.MDC_RUN_ID)).isNull();
  }

  @Test
  void should_mask_storepass_on_logged_command_line_when_importing_keystore() {
    runner.exit(0);
    try (Secret pw = Secret.fromString("ks-pass-123")) {
      ImportRequest withKeystore =
          new ImportRequest(
              tmp.resolve("in.zip"),
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              Optional.of(tmp.resolve("src.jrsks")),
              Optional.empty());
      redactor.register(pw);
      tools()
          .importArchive(
              buildomatic, withKeystore, Optional.of(pw), Optional.of(javaHome), sink, scope);
    }

    assertThat(runner.last().command()).contains("--storepass", "ks-pass-123");
    assertThat(String.join("\n", sink.logMessages())).doesNotContain("ks-pass-123");
  }

  @Test
  void should_not_start_when_java_home_absent() {
    VendorRun run =
        tools().ant(buildomatic, "deploy-webapp-ce", List.of(), Optional.empty(), sink, scope);

    assertThat(run).isInstanceOf(VendorRun.NotStarted.class);
    assertThat(((VendorRun.NotStarted) run).reason()).contains("vendor.javaHome");
    assertThat(runner.requests()).isEmpty();
  }

  @Test
  void should_not_start_when_script_missing() {
    Buildomatic incomplete =
        new Buildomatic(
            buildomatic.dir(), java.util.Map.of(), Optional.empty(), java.util.Map.of());

    VendorRun run =
        tools().ant(incomplete, "deploy-webapp-ce", List.of(), Optional.of(javaHome), sink, scope);

    assertThat(run).isInstanceOf(VendorRun.NotStarted.class);
    assertThat(((VendorRun.NotStarted) run).reason()).contains("js-ant");
    assertThat(runner.requests()).isEmpty();
  }

  @Test
  void should_report_timeout_when_runner_kills_process() {
    runner.timeOut();
    VendorTools tools = new VendorTools(runner, platform.files(), redactor, Duration.ofMinutes(3));

    VendorRun run =
        tools.ant(buildomatic, "deploy-webapp-ce", List.of(), Optional.of(javaHome), sink, scope);

    assertThat(run).isInstanceOf(VendorRun.TimedOut.class);
    assertThat(((VendorRun.TimedOut) run).timeout()).isEqualTo(Duration.ofMinutes(3));
    assertThat(runner.last().timeout()).isEqualTo(Duration.ofMinutes(3));
    assertThat(sink.logMessages()).anyMatch(m -> m.contains("killed after 180s"));
  }
}
