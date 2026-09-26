package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.TerminalState;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.ops.PlanJson;
import com.jaspersoft.jrsctl.ops.PlanRegistry;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunsCommandTest {

  private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

  @TempDir Path tmp;

  private FakeHotfixOperations fake;
  private Path home;
  private Path bundle;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeHotfixOperations();
    HotfixOps.factory = services -> fake;
    home = Files.createDirectories(tmp.resolve("home"));
    bundle = tmp.resolve("hf.zip");
    Files.writeString(bundle, "zip");
  }

  @AfterEach
  void tearDown() {
    HotfixOps.factory = HotfixOps.DEFAULT_FACTORY;
    TestAdapterFactory.unreachable = false;
  }

  private StateStore open() {
    return StateStore.open(new JrsctlHome(home), Clock.systemUTC());
  }

  /** Stores the fake apply plan under {@code planId} and returns it. */
  private Plan storePlan(StateStore store, String planId, Optional<String> consumedBy) {
    Plan plan = fake.planApply(bundle, new HotfixOperations.ApplyOptions(false));
    store.savePlan(
        new StoredPlan(
            planId,
            PlanRegistry.HOTFIX_APPLY,
            PlanRegistry.applyArgs(bundle, false),
            PlanJson.toJson(plan),
            plan.fingerprint().value(),
            T0,
            T0.plus(Duration.ofMinutes(30)),
            consumedBy));
    return plan;
  }

  private void seedFinishedRun(StateStore store) {
    storePlan(store, "plan-1", Optional.of("r-1"));
    store.recordRunStart("r-1", "hotfix.apply", Optional.of("plan-1"), T0);
    store.appendTransition(
        "r-1", "verify-signature", "verify", Optional.empty(), "PENDING", Optional.empty());
    store.appendTransition(
        "r-1", "verify-signature", "verify", Optional.of("PENDING"), "RUNNING", Optional.empty());
    store.appendTransition(
        "r-1",
        "verify-signature",
        "verify",
        Optional.of("RUNNING"),
        "SUCCEEDED",
        Optional.of("key customer"));
    store.recordRunEnd("r-1", T0.plusSeconds(42), TerminalState.SUCCEEDED, 0);
  }

  private void seedPendingRun(StateStore store, String runId, String planId) {
    storePlan(store, planId, Optional.of(runId));
    store.recordRunStart(runId, "hotfix.apply", Optional.of(planId), T0);
    store.appendTransition(
        runId, "verify-signature", "verify", Optional.empty(), "PENDING", Optional.empty());
    store.appendTransition(
        runId, "verify-signature", "verify", Optional.of("PENDING"), "RUNNING", Optional.empty());
    store.appendTransition(
        runId, "verify-signature", "verify", Optional.of("RUNNING"), "SUCCEEDED", Optional.empty());
    store.appendTransition(
        runId, "validate-manifest", "verify", Optional.empty(), "PENDING", Optional.empty());
    store.appendTransition(
        runId, "validate-manifest", "verify", Optional.of("PENDING"), "RUNNING", Optional.empty());
  }

  private static final String OLD_APPLY = "r-20260901-100000-ab12";
  private static final String FAILED_IMPORT = "r-20260910-100000-cd34";
  private static final String ROLLED_BACK = "r-20260920-100000-ef56";
  private static final String PENDING_UPGRADE = "r-20260921-100000-ab99";

  /** Four runs of different operations, outcomes and ages (issue #186). */
  private void seedVariedRuns() {
    try (StateStore store = open()) {
      store.recordRunStart(
          OLD_APPLY, "hotfix.apply", Optional.empty(), Instant.parse("2026-09-01T10:00:00Z"));
      store.recordRunEnd(
          OLD_APPLY, Instant.parse("2026-09-01T10:01:00Z"), TerminalState.SUCCEEDED, 0);
      store.recordRunStart(
          FAILED_IMPORT, "import", Optional.empty(), Instant.parse("2026-09-10T10:00:00Z"));
      store.recordRunEnd(
          FAILED_IMPORT, Instant.parse("2026-09-10T10:01:00Z"), TerminalState.FAILED, 4);
      store.recordRunStart(
          ROLLED_BACK, "hotfix.rollback", Optional.empty(), Instant.parse("2026-09-20T10:00:00Z"));
      store.recordRunEnd(
          ROLLED_BACK, Instant.parse("2026-09-20T10:01:00Z"), TerminalState.ROLLED_BACK, 3);
      store.recordRunStart(
          PENDING_UPGRADE, "upgrade", Optional.empty(), Instant.parse("2026-09-21T10:00:00Z"));
    }
  }

  private List<String> listed(String... filters) throws Exception {
    List<String> args = new java.util.ArrayList<>(List.of("runs", "list", "--json", "--home"));
    args.add(home.toString());
    args.addAll(List.of(filters));
    InitCommandTest.Run run = InitCommandTest.run(args.toArray(String[]::new));
    assertThat(run.code()).as(run.err()).isZero();
    List<String> ids = new java.util.ArrayList<>();
    Json.mapper().readTree(run.out()).forEach(r -> ids.add(r.get("runId").asText()));
    return ids;
  }

  @Test
  void should_filter_runs_by_status_when_status_is_given() throws Exception {
    seedVariedRuns();

    assertThat(listed("--status", "failed")).containsExactly(FAILED_IMPORT);
    assertThat(listed("--status", "rolled-back,pending"))
        .containsExactly(PENDING_UPGRADE, ROLLED_BACK);
    assertThat(listed("--status", "succeeded", "--status", "failed"))
        .containsExactly(FAILED_IMPORT, OLD_APPLY);
  }

  @Test
  void should_filter_runs_by_operation_prefix_when_operation_is_given() throws Exception {
    seedVariedRuns();

    assertThat(listed("--operation", "hotfix")).containsExactly(ROLLED_BACK, OLD_APPLY);
    assertThat(listed("--operation", "hotfix.apply")).containsExactly(OLD_APPLY);
    assertThat(listed("--operation", "hot")).as("a dotted prefix, not any prefix").isEmpty();
  }

  @Test
  void should_filter_by_start_time_before_applying_the_limit_when_since_is_given()
      throws Exception {
    seedVariedRuns();

    assertThat(listed("--since", "2026-09-15")).containsExactly(PENDING_UPGRADE, ROLLED_BACK);
    assertThat(listed("--since", "2026-09-15T00:00:00Z", "--limit", "1"))
        .containsExactly(PENDING_UPGRADE);
    assertThat(listed("--since", "36500d")).hasSize(4);
    assertThat(listed("--operation", "hotfix", "--limit", "1")).containsExactly(ROLLED_BACK);
  }

  @Test
  void should_exit_1_when_a_filter_value_is_not_understood() {
    InitCommandTest.Run since =
        InitCommandTest.run("runs", "list", "--since", "yesterday", "--home", home.toString());
    InitCommandTest.Run status =
        InitCommandTest.run("runs", "list", "--status", "done", "--home", home.toString());

    assertThat(since.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(since.err()).contains("yesterday");
    assertThat(status.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(status.err()).contains("done").contains("rolled-back");
  }

  @Test
  void should_show_a_run_named_by_part_of_its_id() throws Exception {
    seedVariedRuns();

    InitCommandTest.Run run =
        InitCommandTest.run("runs", "show", "ef56", "--home", home.toString(), "--no-color");

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).contains("Run  " + ROLLED_BACK);
  }

  @Test
  void should_exit_1_and_list_the_candidates_when_part_of_an_id_matches_several_runs() {
    seedVariedRuns();

    InitCommandTest.Run show =
        InitCommandTest.run("runs", "show", "202609", "--home", home.toString());
    InitCommandTest.Run bundle =
        InitCommandTest.run("runs", "support-bundle", "2026092", "--home", home.toString());

    assertThat(show.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(show.err()).contains("matches 4 runs").contains(OLD_APPLY).contains(PENDING_UPGRADE);
    assertThat(bundle.code()).isEqualTo(ExitCodes.USAGE);
  }

  @Test
  void should_list_runs_with_duration_and_outcome_when_runs_recorded() throws Exception {
    try (StateStore store = open()) {
      seedFinishedRun(store);
    }

    InitCommandTest.Run text =
        InitCommandTest.run("runs", "list", "--home", home.toString(), "--no-color", "--ascii");
    InitCommandTest.Run json =
        InitCommandTest.run("runs", "list", "--json", "--home", home.toString());

    assertThat(text.code()).isZero();
    assertThat(text.out()).contains("r-1").contains("hotfix.apply").contains("SUCCEEDED (exit 0)");
    assertThat(text.out()).contains("42.0s");
    JsonNode rows = Json.mapper().readTree(json.out());
    assertThat(rows.get(0).get("runId").asText()).isEqualTo("r-1");
    assertThat(rows.get(0).get("terminalState").asText()).isEqualTo("SUCCEEDED");
    assertThat(rows.get(0).get("durationMillis").asLong()).isEqualTo(42_000L);
  }

  @Test
  void should_show_plan_summary_transitions_and_backups_when_run_exists() throws Exception {
    try (StateStore store = open()) {
      seedFinishedRun(store);
    }

    InitCommandTest.Run text =
        InitCommandTest.run(
            "runs", "show", "r-1", "--home", home.toString(), "--no-color", "--ascii");
    InitCommandTest.Run json =
        InitCommandTest.run("runs", "show", "r-1", "--json", "--home", home.toString());

    assertThat(text.code()).as(text.err()).isZero();
    assertThat(text.out())
        .contains("Run  r-1  hotfix.apply  SUCCEEDED (exit 0)")
        .contains("Plan")
        .contains("target")
        .contains(FakeHotfixOperations.ID)
        .contains("Steps")
        .contains("verify-signature")
        .contains("RUNNING -> SUCCEEDED")
        .contains("key customer")
        .contains("Backups");
    JsonNode root = Json.mapper().readTree(json.out());
    assertThat(root.get("run").get("runId").asText()).isEqualTo("r-1");
    assertThat(root.get("plan").get("summary").get("target").asText())
        .isEqualTo(FakeHotfixOperations.ID);
    assertThat(root.get("transitions")).hasSize(3);
  }

  @Test
  void should_exit_2_when_run_is_unknown() {
    InitCommandTest.Run show =
        InitCommandTest.run("runs", "show", "r-nope", "--home", home.toString());
    InitCommandTest.Run recover =
        InitCommandTest.run("runs", "recover", "r-nope", "--resume", "--home", home.toString());

    assertThat(show.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(show.err()).contains("unknown run r-nope");
    assertThat(recover.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(recover.err()).contains("unknown run r-nope");
  }

  @Test
  void should_exit_1_when_neither_resume_nor_rollback_given() {
    InitCommandTest.Run run =
        InitCommandTest.run("runs", "recover", "r-1", "--home", home.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.USAGE);
  }

  @Test
  void should_exit_2_when_run_already_ended() {
    try (StateStore store = open()) {
      seedFinishedRun(store);
    }

    InitCommandTest.Run run =
        InitCommandTest.run("runs", "recover", "r-1", "--rollback", "--home", home.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("already ended").contains("SUCCEEDED");
  }

  @Test
  void should_resume_from_interrupted_step_when_resume_flag_given() {
    try (StateStore store = open()) {
      seedPendingRun(store, "r-2", "plan-2");
    }

    InitCommandTest.Run run =
        InitCommandTest.run(
            "runs",
            "recover",
            "r-2",
            "--resume",
            "--yes",
            "--home",
            home.toString(),
            "--no-color",
            "--ascii");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(fake.executed)
        .containsExactly(
            "validate-manifest", "snapshot", "stage-files", "atomic-swap", "record-installed");
    assertThat(run.out()).contains("run r-2").contains("OK    02  Validate manifest");
    try (StateStore store = open()) {
      assertThat(store.run("r-2").get().terminalState()).contains(TerminalState.SUCCEEDED);
      assertThat(store.auditRows(5)).anyMatch(a -> a.action().equals("runs.recover"));
    }
  }

  @Test
  void should_compensate_succeeded_steps_and_exit_3_when_rollback_flag_given() {
    try (StateStore store = open()) {
      seedPendingRun(store, "r-3", "plan-3");
    }

    InitCommandTest.Run run =
        InitCommandTest.run(
            "runs",
            "recover",
            "r-3",
            "--rollback",
            "--yes",
            "--home",
            home.toString(),
            "--no-color",
            "--ascii");

    assertThat(run.code()).as(run.out() + run.err()).isEqualTo(ExitCodes.FAILED_ROLLED_BACK);
    assertThat(fake.executed).isEmpty();
    assertThat(fake.compensated).containsExactly("validate-manifest", "verify-signature");
    assertThat(run.out()).contains("UNDO  01  Verify signature");
    try (StateStore store = open()) {
      assertThat(store.run("r-3").get().terminalState()).contains(TerminalState.ROLLED_BACK);
    }
  }

  @Test
  void should_exit_2_when_pending_run_has_no_stored_plan() {
    try (StateStore store = open()) {
      store.recordRunStart("r-4", "hotfix.apply", Optional.empty(), T0);
    }

    InitCommandTest.Run run =
        InitCommandTest.run("runs", "recover", "r-4", "--resume", "--home", home.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("no stored plan");
  }

  @Test
  void should_write_a_redacted_zip_and_report_its_entries_when_the_run_exists() throws Exception {
    String secret = "hunter2-support";
    try (StateStore store = open()) {
      seedFinishedRun(store);
    }
    Redactor.global().register(secret);
    Files.createDirectories(home.resolve("logs"));
    Files.writeString(
        home.resolve("logs").resolve("jrsctl.log"),
        "{\"message\":\"login password=" + secret + "\"}\n",
        StandardCharsets.UTF_8);
    // review §3.4 (issue #111): the vendor's own files, each carrying the secret, come along too
    Path tomcat = tmp.resolve("apache-tomcat");
    Files.createDirectories(tomcat.resolve("logs"));
    Files.writeString(tomcat.resolve("logs").resolve("catalina.out"), "start " + secret + "\n");
    Path webInf = tomcat.resolve("webapps").resolve("jasperserver-pro").resolve("WEB-INF");
    Files.createDirectories(webInf.resolve("logs"));
    Files.writeString(webInf.resolve("logs").resolve("jasperserver.log"), "js " + secret + "\n");
    Files.writeString(tmp.resolve("installation.log"), "installer " + secret + "\n");
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8089/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          tomcatDir: %s/apache-tomcat
        """
            .formatted(tmp.toString().replace("\\", "/"), tmp.toString().replace("\\", "/")),
        StandardCharsets.UTF_8);
    TestAdapterFactory.unreachable = true; // no real server in this fixture
    Path out = tmp.resolve("r-1.zip");

    InitCommandTest.Run r =
        InitCommandTest.run(
            "runs",
            "support-bundle",
            "r-1",
            "--out",
            out.toString(),
            "--json",
            "--home",
            home.toString());

    assertThat(r.code()).as(r.out() + r.err()).isZero();
    JsonNode doc = Json.mapper().readTree(r.out());
    assertThat(doc.get("runId").asText()).isEqualTo("r-1");
    assertThat(Path.of(doc.get("path").asText())).isEqualTo(out.toAbsolutePath());
    assertThat(doc.get("bytes").asLong()).isEqualTo(Files.size(out));
    List<String> entries = new ArrayList<>();
    Map<String, String> contents = new LinkedHashMap<>();
    try (ZipInputStream in =
        new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(out)))) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        entries.add(e.getName());
        contents.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    assertThat(entries)
        .as("#161: run.json already carries the stored plan")
        .doesNotContain("plan.json");
    assertThat(entries)
        .contains(
            "run.json",
            "transitions.jsonl",
            "server.json",
            "doctor.json",
            "config-redacted.yaml",
            "logs/jrsctl.log",
            "vendor/catalina.out",
            "vendor/jasperserver.log",
            "vendor/installation.log");
    assertThat(doc.get("entries")).extracting(JsonNode::asText).containsExactlyElementsOf(entries);
    String everything = String.join("", contents.values());
    assertThat(everything).contains("[redacted]");
    assertThat(everything).doesNotContain(secret);
    assertThat(everything)
        .doesNotContain(
            Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
    assertThat(contents.get("server.json")).contains("\"baseUrl\"").contains("\"reachable\"");
  }

  @Test
  void should_refuse_with_exit_2_and_write_nothing_when_the_run_is_unknown() throws Exception {
    Path out = tmp.resolve("r-nope.zip");

    InitCommandTest.Run r =
        InitCommandTest.run(
            "runs",
            "support-bundle",
            "r-nope",
            "--out",
            out.toString(),
            "--json",
            "--home",
            home.toString());

    assertThat(r.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(Files.exists(out)).isFalse();
  }

  @Test
  void should_refuse_with_exit_2_when_the_output_file_already_exists() throws Exception {
    try (StateStore store = open()) {
      seedFinishedRun(store);
    }
    Path out = tmp.resolve("taken.zip");
    Files.writeString(out, "old");

    InitCommandTest.Run r =
        InitCommandTest.run(
            "runs", "support-bundle", "r-1", "--out", out.toString(), "--home", home.toString());

    assertThat(r.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(Files.readString(out)).isEqualTo("old");
    assertThat(r.err()).contains("already exists");
  }

  @Test
  void should_refuse_with_exit_2_before_opening_bootstrap_when_the_out_parent_is_missing() {
    Path parent = tmp.resolve("nope");
    Path out = parent.resolve("b.zip");

    InitCommandTest.Run r =
        InitCommandTest.run(
            "runs", "support-bundle", "r-1", "--out", out.toString(), "--home", home.toString());

    assertThat(r.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(r.err()).contains(parent.toString());
    assertThat(Files.exists(out)).isFalse();
    // no Bootstrap side effect: the run lookup, and the state store it needs, never happened
    assertThat(Files.exists(home.resolve("state.db"))).isFalse();
  }

  @Test
  void should_refuse_with_exit_2_when_the_out_path_names_an_existing_directory() throws Exception {
    Path dir = Files.createDirectory(tmp.resolve("adir"));

    InitCommandTest.Run r =
        InitCommandTest.run(
            "runs", "support-bundle", "r-1", "--out", dir.toString(), "--home", home.toString());

    assertThat(r.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(r.err()).contains(dir.toString()).contains("is a directory");
    assertThat(Files.isDirectory(dir)).isTrue();
  }

  @Test
  void should_delete_the_partial_zip_when_writing_the_bundle_fails_midway() throws Exception {
    try (StateStore store = open()) {
      seedFinishedRun(store);
    }
    // jrsctl.log with a byte sequence that is not valid UTF-8: SupportBundle.write tails this
    // file after the zip is already open, so decoding failure here fails the write partway
    // through, once the target file already exists on disk.
    Path log = Files.createDirectories(home.resolve("logs")).resolve("jrsctl.log");
    Files.write(log, new byte[] {(byte) 0x80, '\n'});
    Path out = tmp.resolve("partial.zip");

    // pin the log file this run reads: SupportBundle.logFile() prefers jrsctl.log.file when it
    // names a regular file, and another test in a full app-module run can leave that property
    // set, which would make it skip the corrupted <home>/logs/jrsctl.log above (#151 PR2 finding C)
    String savedLogFile = System.getProperty(LogFile.PROPERTY);
    System.setProperty(LogFile.PROPERTY, log.toString());
    try {
      InitCommandTest.Run r =
          InitCommandTest.run(
              "runs", "support-bundle", "r-1", "--out", out.toString(), "--home", home.toString());

      assertThat(r.code()).as(r.out() + r.err()).isNotZero();
      assertThat(Files.exists(out)).as("no partial zip left behind").isFalse();
    } finally {
      if (savedLogFile == null) {
        System.clearProperty(LogFile.PROPERTY);
      } else {
        System.setProperty(LogFile.PROPERTY, savedLogFile);
      }
    }
  }
}
