package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.ops.Idempotency;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.RollbackOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 8 idempotency of every hotfix step (spec §6.1): a step executed a second time after a
 * complete first execution (what {@code runs recover --resume} does after a crash) leaves the
 * install tree, the snapshots, the run directory, the state store rows and the service exactly as
 * one execution did; the same holds for every compensation, and for {@code atomic-swap} after a
 * crash that landed only some of its files. SQL steps re-run their scripts by contract (the
 * manifest declares them idempotent); audit rows are an append-only log and are not part of the
 * compared state.
 */
class HotfixStepIdempotencyTest {

  private static final ApplyOptions SIGNED = new ApplyOptions(false);
  private static final RollbackOptions PLAIN = new RollbackOptions(false);
  private static final String FORWARD =
      "-- create the fix table\nCREATE TABLE fix (id int);\nINSERT INTO fix VALUES (1);\n";
  private static final String ROLLBACK = "DROP TABLE fix;\n";
  private static final String SQL_MANIFEST =
      """
      {
        "id": "%s",
        "version": "1",
        "title": "Schema fix",
        "applies": { "versions": [">=8.0.0 <9.0.0"] },
        "files": [ { "action": "add", "path": "%s" } ],
        "sql": [ { "db": "postgresql", "file": "sql/postgresql/001.sql", "idempotent": true,
                   "rollbackFile": "sql/postgresql/001-rollback.sql" } ],
        "restart": "none",
        "postchecks": [ { "type": "http", "url": "/login.html", "expect": 200 } ],
        "rollback": "snapshot"
      }
      """
          .formatted(HotfixFixture.ID, HotfixFixture.SCRIPT);

  @TempDir Path tmp;

  // ---------------------------------------------------------------- helpers

  private static Map<String, String> state(HotfixFixture f, String runId) throws IOException {
    Map<String, String> m = new TreeMap<>();
    m.putAll(Idempotency.tree("install", f.installDir));
    m.putAll(Idempotency.tree("snapshots", f.fake.home.snapshots()));
    m.putAll(Idempotency.tree("runs", f.fake.home.runs()));
    m.put("service", f.fake.platform.serviceState.name());
    m.put("hotfixes", f.store().hotfixes().toString());
    for (HotfixInstalled h : f.store().hotfixes()) {
      m.put("files:" + h.id(), f.store().hotfixFiles(h.id()).toString());
    }
    m.put("snapshot-rows", f.store().snapshots(runId).toString());
    return m;
  }

  private static Context start(HotfixFixture f, Plan plan, String runId) {
    f.store().recordRunStart(runId, plan.summary().operation(), Optional.empty(), Instant.EPOCH);
    return f.ctx(runId);
  }

  private static Plan webInf(HotfixFixture f) throws IOException {
    return f.ops().planApply(f.buildWebInf(), SIGNED);
  }

  private static Plan sql(HotfixFixture f) throws IOException {
    Map<String, String> files = new HashMap<>();
    files.put("payload/" + HotfixFixture.SCRIPT, HotfixFixture.SCRIPT_BYTES);
    files.put("sql/postgresql/001.sql", FORWARD);
    files.put("sql/postgresql/001-rollback.sql", ROLLBACK);
    return f.ops().planApply(f.build(f.bundleDir("sql", SQL_MANIFEST, files)), SIGNED);
  }

  private static HotfixFixture withDatabase(Path root) throws IOException {
    return HotfixFixture.create(root, HotfixFixture.databaseYaml(root));
  }

  /** Runs up to {@code stepId}, executes it again, and expects no change. */
  private static void assertReexecutionConverges(
      HotfixFixture f, Plan plan, String runId, String stepId) throws IOException {
    Context ctx = start(f, plan, runId);
    Idempotency.runUpTo(plan, ctx, stepId);
    Map<String, String> once = state(f, runId);
    Idempotency.executeOk(Idempotency.step(plan, stepId), ctx);
    assertThat(state(f, runId)).isEqualTo(once);
  }

  /**
   * Runs the whole plan, compensates {@code stepId} twice, and expects no change after the first.
   */
  private static void assertCompensationConverges(
      HotfixFixture f, Plan plan, String runId, String stepId) throws IOException {
    Context ctx = start(f, plan, runId);
    Idempotency.runAll(plan, ctx);
    Step step = Idempotency.step(plan, stepId);
    Idempotency.compensateOk(step, ctx);
    Map<String, String> once = state(f, runId);
    Idempotency.compensateOk(step, ctx);
    assertThat(state(f, runId)).isEqualTo(once);
  }

  private static Plan rollbackPlan(HotfixFixture f) throws IOException {
    assertThat(f.run(webInf(f), "r-apply")).isInstanceOf(RunOutcome.Succeeded.class);
    f.fake.platform.controller.events.clear();
    return f.ops().planRollback(HotfixFixture.ID, PLAIN);
  }

  // ---------------------------------------------------------------- apply plan

  /** Each read-only step is re-executed at its own position in the plan, as a resume would. */
  @Test
  void should_not_mutate_when_read_only_apply_steps_execute_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = webInf(f);
      Context ctx = start(f, plan, "r-ro");
      List<String> readOnly =
          List.of(
              "verify-signature",
              "validate-manifest",
              "preflight",
              "run-prechecks",
              "run-postchecks",
              "wait-for-server");
      List<String> seen = new ArrayList<>();
      for (Step step : plan.steps()) {
        Idempotency.executeOk(step, ctx);
        if (readOnly.contains(step.id())) {
          assertThat(step.mutating()).as(step.id()).isFalse();
          Map<String, String> before = state(f, "r-ro");
          Idempotency.executeOk(step, ctx);
          Idempotency.compensateOk(step, ctx);
          assertThat(state(f, "r-ro")).as(step.id()).isEqualTo(before);
          seen.add(step.id());
        }
      }
      assertThat(seen).containsExactlyInAnyOrderElementsOf(readOnly);
    }
  }

  @Test
  void should_converge_when_take_snapshot_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertReexecutionConverges(f, webInf(f), "r-snap", "snapshot");
      assertThat(f.store().snapshots("r-snap")).hasSize(1);
    }
  }

  @Test
  void should_converge_when_stage_files_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertReexecutionConverges(f, webInf(f), "r-stage", "stage-files");
    }
  }

  @Test
  void should_converge_when_stage_files_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertCompensationConverges(f, webInf(f), "r-stage-c", "stage-files");
      assertThat(f.fake.home.stagingDir("r-stage-c")).doesNotExist();
    }
  }

  /**
   * A crash in the middle of the swap leaves some files replaced and some not; {@code
   * HotfixApplyTest#should_converge_when_atomic_swap_executes_twice} covers the complete case.
   */
  @Test
  void should_converge_when_atomic_swap_executes_after_a_partial_swap() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp);
        HotfixFixture reference = HotfixFixture.create(tmp.resolve("reference"))) {
      Plan plan = webInf(f);
      Context ctx = start(f, plan, "r-partial");
      Idempotency.runUpTo(plan, ctx, "stop-service");
      // the first file landed and its older sibling was deleted, then the process died
      HotfixFixture.write(f.target(HotfixFixture.FOO), HotfixFixture.NEW_FOO);
      Files.delete(f.target(HotfixFixture.FOO_OLDER));

      Idempotency.executeOk(Idempotency.step(plan, "atomic-swap"), ctx);

      Plan cleanPlan = webInf(reference);
      Idempotency.runUpTo(cleanPlan, start(reference, cleanPlan, "r-clean"), "atomic-swap");
      assertThat(Idempotency.tree("install", f.installDir))
          .isEqualTo(Idempotency.tree("install", reference.installDir));
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo(HotfixFixture.NEW_FOO);
      assertThat(f.target(HotfixFixture.FIX)).exists();
      assertThat(f.target(HotfixFixture.BAR)).doesNotExist();
      assertThat(f.target(HotfixFixture.FOO_OLDER)).doesNotExist();
    }
  }

  /**
   * Issue #184: staging now runs before the stop, so a run left pending by an older jrsctl, which
   * stopped first and crashed before staging, resumes at the swap with nothing staged. The swap's
   * precheck refuses, which leaves only rollback.
   */
  @Test
  void should_refuse_the_swap_when_the_payload_was_never_staged() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = webInf(f);
      Context ctx = start(f, plan, "r-unstaged");
      Idempotency.runUpTo(plan, ctx, "snapshot");

      CheckResult pre = Idempotency.step(plan, "atomic-swap").precheck(ctx);

      assertThat(pre.failed()).isTrue();
      assertThat(((CheckResult.Fail) pre).message())
          .contains("not staged")
          .contains(Path.of(HotfixFixture.FOO).getFileName().toString());
    }
  }

  /** A swap interrupted part-way has already moved some staged files into place. */
  @Test
  void should_pass_the_swap_precheck_when_a_file_already_landed() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = webInf(f);
      Context ctx = start(f, plan, "r-landed");
      Idempotency.runUpTo(plan, ctx, "stop-service");
      Path staged = ctx.home().stagingDir("r-landed").resolve(HotfixFixture.FOO);
      HotfixFixture.write(f.target(HotfixFixture.FOO), HotfixFixture.NEW_FOO);
      Files.delete(staged);

      CheckResult pre = Idempotency.step(plan, "atomic-swap").precheck(ctx);

      assertThat(pre.failed()).as(pre.toString()).isFalse();
    }
  }

  /**
   * Assessment item O4: the swap had no postcheck, so a target that changed under it (or a payload
   * deleted by a sibling rule) was recorded as installed. The postcheck re-hashes every landed file
   * and confirms every deletion.
   */
  @Test
  void should_fail_the_postcheck_when_a_swapped_file_changed_under_it() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = webInf(f);
      Context ctx = start(f, plan, "r-post");
      Idempotency.runUpTo(plan, ctx, "stop-service");
      Step swap = Idempotency.step(plan, "atomic-swap");
      Idempotency.executeOk(swap, ctx);
      assertThat(swap.postcheck(ctx).failed()).as("clean swap passes its postcheck").isFalse();

      HotfixFixture.write(f.target(HotfixFixture.FOO), "tampered after the swap");

      CheckResult after = swap.postcheck(ctx);
      assertThat(after.failed()).isTrue();
      assertThat(((CheckResult.Fail) after).message())
          .contains(Path.of(HotfixFixture.FOO).getFileName().toString())
          .contains("hash");
    }
  }

  @Test
  void should_converge_when_atomic_swap_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      String oldFoo = f.sha(HotfixFixture.FOO);
      assertCompensationConverges(f, webInf(f), "r-swap-c", "atomic-swap");
      assertThat(f.sha(HotfixFixture.FOO)).isEqualTo(oldFoo);
      assertThat(f.target(HotfixFixture.FIX)).doesNotExist();
      assertThat(f.target(HotfixFixture.BAR)).exists();
    }
  }

  @Test
  void should_rerun_idempotent_scripts_when_apply_sql_executes_twice() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Plan plan = sql(f);
      Context ctx = start(f, plan, "r-sql");
      Idempotency.runUpTo(plan, ctx, "atomic-swap");
      int before = f.jdbc.executed.size();
      Step applySql = Idempotency.step(plan, "apply-sql");
      Idempotency.executeOk(applySql, ctx);
      List<String> statements =
          List.copyOf(f.jdbc.executed.subList(before, f.jdbc.executed.size()));
      assertThat(statements).hasSize(2);
      Map<String, String> once = state(f, "r-sql");

      Idempotency.executeOk(applySql, ctx);

      assertThat(state(f, "r-sql")).isEqualTo(once);
      assertThat(f.jdbc.executed.subList(before, f.jdbc.executed.size()))
          .as("scripts are declared idempotent and run again on resume")
          .containsExactlyElementsOf(concat(statements, statements));
    }
  }

  @Test
  void should_converge_when_apply_sql_compensates_twice() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Plan plan = sql(f);
      Context ctx = start(f, plan, "r-sql-c");
      Idempotency.runAll(plan, ctx);
      Step step = Idempotency.step(plan, "apply-sql");
      Idempotency.compensateOk(step, ctx);
      Map<String, String> once = state(f, "r-sql-c");
      int executed = f.jdbc.executed.size();

      Idempotency.compensateOk(step, ctx);

      assertThat(state(f, "r-sql-c")).isEqualTo(once);
      assertThat(f.jdbc.executed).hasSize(executed + 1);
      assertThat(f.jdbc.executed.get(executed)).contains("DROP TABLE fix");
    }
  }

  @Test
  void should_converge_when_record_installed_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = webInf(f);
      Context ctx = start(f, plan, "r-rec");
      Idempotency.runAll(plan, ctx);
      Map<String, String> once = state(f, "r-rec");
      int audits = f.store().auditRows(100).size();

      Idempotency.executeOk(Idempotency.step(plan, "record-installed"), ctx);

      assertThat(state(f, "r-rec")).isEqualTo(once);
      assertThat(f.store().auditRows(100)).hasSize(audits);
      assertThat(f.store().hotfix(HotfixFixture.ID).orElseThrow().state())
          .isEqualTo(HotfixState.INSTALLED);
    }
  }

  @Test
  void should_converge_when_record_installed_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertCompensationConverges(f, webInf(f), "r-rec-c", "record-installed");
      assertThat(f.store().hotfix(HotfixFixture.ID).orElseThrow().state())
          .isEqualTo(HotfixState.ROLLED_BACK);
    }
  }

  @Test
  void should_stop_once_when_stop_service_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertReexecutionConverges(f, webInf(f), "r-stop", "stop-service");
      assertThat(f.fake.platform.controller.events).containsExactly("stop");
      assertThat(f.fake.platform.serviceState).isEqualTo(ServiceController.State.STOPPED);
    }
  }

  @Test
  void should_start_once_when_stop_service_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = webInf(f);
      Context ctx = start(f, plan, "r-stop-c");
      Idempotency.runUpTo(plan, ctx, "stop-service");
      Step stop = Idempotency.step(plan, "stop-service");
      Idempotency.compensateOk(stop, ctx);
      Idempotency.compensateOk(stop, ctx);
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start");
      assertThat(f.fake.platform.serviceState).isEqualTo(ServiceController.State.RUNNING);
    }
  }

  @Test
  void should_start_once_when_start_service_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertReexecutionConverges(f, webInf(f), "r-start", "start-service");
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start");
      assertThat(f.fake.platform.serviceState).isEqualTo(ServiceController.State.RUNNING);
    }
  }

  @Test
  void should_stop_once_when_start_service_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertCompensationConverges(f, webInf(f), "r-start-c", "start-service");
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start", "stop");
      assertThat(f.fake.platform.serviceState).isEqualTo(ServiceController.State.STOPPED);
    }
  }

  // ---------------------------------------------------------------- rollback plan

  @Test
  void should_converge_when_restore_snapshot_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      String olderFoo = f.sha(HotfixFixture.FOO_OLDER);
      Plan back = rollbackPlan(f);
      assertThat(f.target(HotfixFixture.FOO_OLDER)).as("deleted by the hotfix").doesNotExist();
      assertReexecutionConverges(f, back, "r-rb", "restore-snapshot");
      assertThat(f.sha(HotfixFixture.FOO_OLDER)).isEqualTo(olderFoo);
      assertThat(f.target(HotfixFixture.FIX)).doesNotExist();
      SnapshotStore snapshots =
          new SnapshotStore(f.fake.home, f.services.platform().files(), f.fake.clock);
      assertThat(snapshots.find("r-rb", "pre-rollback-" + HotfixFixture.ID)).isPresent();
    }
  }

  /**
   * The pre-rollback snapshot must keep the hotfix files from the first execution: a second
   * execution after a crash sees already restored files and must not overwrite that snapshot with
   * them, or the compensation could no longer re-apply the hotfix.
   */
  @Test
  void should_reapply_the_hotfix_when_restore_snapshot_compensates_after_re_execution()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan back = rollbackPlan(f);
      Context ctx = start(f, back, "r-rb2");
      Idempotency.runUpTo(back, ctx, "restore-snapshot");
      Step restore = Idempotency.step(back, "restore-snapshot");
      Idempotency.executeOk(restore, ctx);

      Idempotency.compensateOk(restore, ctx);

      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo(HotfixFixture.NEW_FOO);
      assertThat(Files.readString(f.target(HotfixFixture.FIX))).isEqualTo(HotfixFixture.FIX_BYTES);
      assertThat(f.target(HotfixFixture.BAR)).doesNotExist();
      assertThat(f.target(HotfixFixture.FOO_OLDER)).doesNotExist();
    }
  }

  @Test
  void should_converge_when_restore_snapshot_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertCompensationConverges(f, rollbackPlan(f), "r-rb-c", "restore-snapshot");
      assertThat(Files.readString(f.target(HotfixFixture.FOO))).isEqualTo(HotfixFixture.NEW_FOO);
    }
  }

  @Test
  void should_rerun_rollback_scripts_when_run_sql_rollback_executes_twice() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      assertThat(f.run(sql(f), "r-sql-apply")).isInstanceOf(RunOutcome.Succeeded.class);
      Plan back = f.ops().planRollback(HotfixFixture.ID, PLAIN);
      Context ctx = start(f, back, "r-sql-rb");
      Idempotency.runUpTo(back, ctx, "run-sql-rollback");
      Map<String, String> once = state(f, "r-sql-rb");
      int executed = f.jdbc.executed.size();

      Idempotency.executeOk(Idempotency.step(back, "run-sql-rollback"), ctx);

      assertThat(state(f, "r-sql-rb")).isEqualTo(once);
      assertThat(f.jdbc.executed).hasSize(executed + 1);
      assertThat(f.jdbc.executed.get(executed)).contains("DROP TABLE fix");
    }
  }

  @Test
  void should_converge_when_run_sql_rollback_compensates_twice() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      assertThat(f.run(sql(f), "r-sql-apply")).isInstanceOf(RunOutcome.Succeeded.class);
      Plan back = f.ops().planRollback(HotfixFixture.ID, PLAIN);
      Context ctx = start(f, back, "r-sql-rb-c");
      Idempotency.runAll(back, ctx);
      Step step = Idempotency.step(back, "run-sql-rollback");
      Idempotency.compensateOk(step, ctx);
      Map<String, String> once = state(f, "r-sql-rb-c");
      int executed = f.jdbc.executed.size();

      Idempotency.compensateOk(step, ctx);

      assertThat(state(f, "r-sql-rb-c")).isEqualTo(once);
      assertThat(f.jdbc.executed).hasSize(executed + 2);
    }
  }

  @Test
  void should_converge_when_record_rolled_back_executes_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan back = rollbackPlan(f);
      Context ctx = start(f, back, "r-rrb");
      Idempotency.runAll(back, ctx);
      Map<String, String> once = state(f, "r-rrb");
      int audits = f.store().auditRows(100).size();

      Idempotency.executeOk(Idempotency.step(back, "record-rolled-back"), ctx);

      assertThat(state(f, "r-rrb")).isEqualTo(once);
      assertThat(f.store().auditRows(100)).hasSize(audits);
    }
  }

  @Test
  void should_converge_when_record_rolled_back_compensates_twice() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      assertCompensationConverges(f, rollbackPlan(f), "r-rrb-c", "record-rolled-back");
      assertThat(f.store().hotfix(HotfixFixture.ID).orElseThrow().state())
          .isEqualTo(HotfixState.INSTALLED);
    }
  }

  private static List<String> concat(List<String> a, List<String> b) {
    List<String> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }
}
