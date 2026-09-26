package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.ops.Idempotency;
import com.jaspersoft.jrsctl.ops.db.JdbcException;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.RollbackOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotfixSqlTest {

  private static final ApplyOptions SIGNED = new ApplyOptions(false);
  private static final String FORWARD =
      "-- create the fix table\nCREATE TABLE fix (id int);\nINSERT INTO fix\n  VALUES (1);\n";
  private static final String ROLLBACK = "DROP TABLE fix;\n";

  @TempDir Path tmp;

  private static String sqlManifest(String rollback, String extra) {
    return """
        {
          "id": "%s",
          "version": "1",
          "title": "Schema fix",
          "applies": { "versions": [">=8.0.0 <9.0.0"] },
          "files": [ { "action": "add", "path": "%s" } ],
          "sql": [ { "db": "postgresql", "file": "sql/postgresql/001.sql", "idempotent": true%s } ],
          "restart": "none",
          "postchecks": [ { "type": "http", "url": "/login.html", "expect": 200 } ],
          "rollback": "%s"%s
        }
        """
        .formatted(
            HotfixFixture.ID,
            HotfixFixture.SCRIPT,
            rollback.equals("snapshot")
                ? ", \"rollbackFile\": \"sql/postgresql/001-rollback.sql\""
                : "",
            rollback,
            extra);
  }

  private static Map<String, String> files(boolean withRollback) {
    Map<String, String> files = new HashMap<>();
    files.put("payload/" + HotfixFixture.SCRIPT, HotfixFixture.SCRIPT_BYTES);
    files.put("sql/postgresql/001.sql", FORWARD);
    if (withRollback) {
      files.put("sql/postgresql/001-rollback.sql", ROLLBACK);
    }
    return files;
  }

  private static final String TWO_SCRIPT_MANIFEST =
      """
      {
        "id": "%s",
        "version": "1",
        "title": "Two-step schema fix",
        "applies": { "versions": [">=8.0.0 <9.0.0"] },
        "files": [ { "action": "add", "path": "%s" } ],
        "sql": [
          { "db": "postgresql", "file": "sql/postgresql/001.sql", "idempotent": true,
            "rollbackFile": "sql/postgresql/001-rollback.sql" },
          { "db": "postgresql", "file": "sql/postgresql/002.sql", "idempotent": true,
            "rollbackFile": "sql/postgresql/002-rollback.sql" }
        ],
        "restart": "none",
        "rollback": "snapshot"
      }
      """
          .formatted(HotfixFixture.ID, HotfixFixture.SCRIPT);

  private static Map<String, String> twoScriptFiles() {
    Map<String, String> files = new HashMap<>();
    files.put("payload/" + HotfixFixture.SCRIPT, HotfixFixture.SCRIPT_BYTES);
    files.put("sql/postgresql/001.sql", "CREATE TABLE first_marker (id int);\n");
    files.put("sql/postgresql/001-rollback.sql", "DROP TABLE first_marker;\n");
    files.put("sql/postgresql/002.sql", "CREATE TABLE second_marker (id int);\n");
    files.put("sql/postgresql/002-rollback.sql", "DROP TABLE second_marker;\n");
    return files;
  }

  @Test
  void should_send_a_delimiter_scoped_statement_whole_when_run_through_the_runner()
      throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      String body = "CREATE PROCEDURE p AS BEGIN UPDATE t SET a = 1; UPDATE t SET b = 2; END;";
      Map<String, String> files = files(true);
      files.put("sql/postgresql/001.sql", "-- jrsctl:delimiter //\n" + body + "\n//\n");
      Path zip = f.build(f.bundleDir("delim", sqlManifest("snapshot", ""), files));

      RunOutcome outcome = f.run(f.ops().planApply(zip, SIGNED), "r-delim");

      assertThat(outcome).as(f.events.toString()).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.jdbc.executed)
          .as("the procedure body must reach the driver as one statement")
          .containsExactly("SELECT 1", body);
    }
  }

  @Test
  void should_undo_started_scripts_and_restore_files_when_apply_sql_fails_through_the_runner()
      throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Map<String, String> files = twoScriptFiles();
      files.put(
          "sql/postgresql/002.sql",
          "CREATE TABLE second_marker (id int);\nINSERT INTO second_marker VALUES (1);\n");
      Path zip = f.build(f.bundleDir("two-sql", TWO_SCRIPT_MANIFEST, files));
      Plan plan = f.ops().planApply(zip, SIGNED);
      // fails inside 002 after its first statement; the rollback scripts never match this text
      f.jdbc.failOnStatementContaining = Optional.of("INSERT INTO second_marker");

      RunOutcome outcome = f.run(plan, "r-sql-midway");

      assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(((RunOutcome.RolledBack) outcome).cause()).contains("INSERT INTO second_marker");
      assertThat(f.jdbc.executed)
          .as("both started scripts are undone, newest first, then the files")
          .containsExactly(
              "SELECT 1",
              "CREATE TABLE first_marker (id int)",
              "CREATE TABLE second_marker (id int)",
              "DROP TABLE second_marker",
              "DROP TABLE first_marker");
      assertThat(f.target(HotfixFixture.SCRIPT)).doesNotExist();
      assertThat(f.ops().list()).isEmpty();
      assertThat(HotfixApplyTest.journal(f, "r-sql-midway"))
          .containsSubsequence(
              "apply-sql:FAILED", "apply-sql:ROLLED_BACK", "atomic-swap:ROLLED_BACK");
    }
  }

  @Test
  void should_undo_only_the_sql_scripts_that_started_when_a_later_one_never_ran()
      throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Path zip = f.build(f.bundleDir("two-sql", TWO_SCRIPT_MANIFEST, twoScriptFiles()));
      Plan plan = f.ops().planApply(zip, SIGNED);
      Context ctx = f.ctx("r-partial-sql");
      Idempotency.runUpTo(plan, ctx, "atomic-swap");
      Step sql = HotfixFixture.step(plan, "apply-sql");
      f.jdbc.failOnStatementContaining = Optional.of("first_marker");

      StepResult failed = sql.execute(ctx, f.events::add);

      assertThat(failed).isInstanceOf(StepResult.Failed.class);
      assertThat(f.jdbc.executed)
          .as("the run stopped inside 001, so 002 was never sent")
          .doesNotContain("CREATE TABLE second_marker (id int)");

      f.jdbc.failOnStatementContaining = Optional.empty();
      f.jdbc.executed.clear();
      StepResult undone = sql.compensate(ctx, f.events::add);

      assertThat(undone).isInstanceOf(StepResult.Ok.class);
      assertThat(f.jdbc.executed)
          .as("dropping second_marker would undo a table this run never created")
          .containsExactly("DROP TABLE first_marker");
    }
  }

  @Test
  void should_undo_a_script_that_started_and_then_failed_part_way() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Path zip = f.build(f.bundleDir("two-sql", TWO_SCRIPT_MANIFEST, twoScriptFiles()));
      Plan plan = f.ops().planApply(zip, SIGNED);
      Context ctx = f.ctx("r-failed-sql");
      Idempotency.runUpTo(plan, ctx, "atomic-swap");
      Step sql = HotfixFixture.step(plan, "apply-sql");
      f.jdbc.failOnStatementContaining = Optional.of("second_marker");

      assertThat(sql.execute(ctx, f.events::add)).isInstanceOf(StepResult.Failed.class);

      f.jdbc.failOnStatementContaining = Optional.empty();
      f.jdbc.executed.clear();
      StepResult undone = sql.compensate(ctx, f.events::add);

      assertThat(undone).isInstanceOf(StepResult.Ok.class);
      assertThat(f.jdbc.executed)
          .as("002 was sent and may have changed something before it failed")
          .containsExactly("DROP TABLE second_marker", "DROP TABLE first_marker");
    }
  }

  @Test
  void should_undo_nothing_when_no_sql_script_started() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Path zip = f.build(f.bundleDir("two-sql", TWO_SCRIPT_MANIFEST, twoScriptFiles()));
      Plan plan = f.ops().planApply(zip, SIGNED);
      Context ctx = f.ctx("r-no-sql");
      Idempotency.runUpTo(plan, ctx, "atomic-swap");
      Step sql = HotfixFixture.step(plan, "apply-sql");
      f.jdbc.executed.clear();

      StepResult undone = sql.compensate(ctx, f.events::add);

      assertThat(undone).isInstanceOf(StepResult.Ok.class);
      assertThat(f.jdbc.executed).isEmpty();
    }
  }

  @Test
  void should_run_scripts_through_connector_and_roll_them_back_when_rolled_back()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp, "")) {
      // no database section: validate-manifest must refuse
      Path zip = f.build(f.bundleDir("sql", sqlManifest("snapshot", ""), files(true)));
      Plan noDb = f.ops().planApply(zip, SIGNED);
      assertThat(HotfixFixture.ids(noDb)).doesNotContain("apply-sql");
      assertThat(noDb.summary().warnings()).anyMatch(w -> w.contains("database section"));
      RunOutcome refused = f.run(noDb, "r-nodb");
      assertThat(refused).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) refused).message()).contains("database section");
    }
    try (HotfixFixture f = withDatabase(tmp.resolve("with-db"))) {
      Path zip = f.build(f.bundleDir("sql", sqlManifest("snapshot", ""), files(true)));
      Plan plan = f.ops().planApply(zip, SIGNED);
      assertThat(HotfixFixture.ids(plan))
          .containsExactly(
              "verify-signature",
              "validate-manifest",
              "preflight",
              "run-prechecks",
              "snapshot",
              "stage-files",
              "atomic-swap",
              "apply-sql",
              "run-postchecks",
              "record-installed");
      assertThat(HotfixFixture.step(plan, "apply-sql").irreversible()).isFalse();
      assertThat(f.run(plan, "r-sql")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.jdbc.executed)
          .containsExactly(
              "SELECT 1", "CREATE TABLE fix (id int)", "INSERT INTO fix\n  VALUES (1)");
      assertThat(f.jdbc.connections).allMatch(c -> c.contains("jdbc:postgresql://db.example/jrs"));

      f.jdbc.executed.clear();
      Plan rollback = f.ops().planRollback(HotfixFixture.ID, new RollbackOptions(false));
      assertThat(HotfixFixture.ids(rollback))
          .containsExactly("restore-snapshot", "run-sql-rollback", "record-rolled-back");
      assertThat(f.run(rollback, "r-sql-rollback")).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.jdbc.executed).containsExactly("DROP TABLE fix");
      assertThat(f.target(HotfixFixture.SCRIPT)).doesNotExist();
    }
  }

  @Test
  void should_run_rollback_scripts_when_a_later_step_fails() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Path zip = f.build(f.bundleDir("sql", sqlManifest("snapshot", ""), files(true)));
      f.httpStatuses.put("/login.html", 503);
      RunOutcome outcome = f.run(f.ops().planApply(zip, SIGNED), "r-sql-fail");
      assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(((RunOutcome.RolledBack) outcome).cause()).contains("got 503");
      assertThat(f.jdbc.executed)
          .containsExactly(
              "SELECT 1",
              "CREATE TABLE fix (id int)",
              "INSERT INTO fix\n  VALUES (1)",
              "DROP TABLE fix");
      assertThat(f.target(HotfixFixture.SCRIPT)).doesNotExist();
      assertThat(f.ops().list()).isEmpty();
    }
  }

  @Test
  void should_mark_apply_sql_irreversible_and_warn_when_manifest_says_so() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Path zip =
          f.build(
              f.bundleDir(
                  "irreversible",
                  sqlManifest(
                      "irreversible", ", \"rollbackNote\": \"restore the database from backup\""),
                  files(false)));
      Plan plan = f.ops().planApply(zip, SIGNED);
      assertThat(HotfixFixture.step(plan, "apply-sql").irreversible()).isTrue();
      assertThat(plan.summary().warnings())
          .anyMatch(w -> w.contains("restore the database from backup"))
          .anyMatch(w -> w.contains("operator's responsibility"));
      assertThat(f.run(plan, "r-irr")).isInstanceOf(RunOutcome.Succeeded.class);
      Plan rollback = f.ops().planRollback(HotfixFixture.ID, new RollbackOptions(false));
      assertThat(HotfixFixture.ids(rollback)).doesNotContain("run-sql-rollback");
      assertThat(rollback.summary().warnings()).anyMatch(w -> w.contains("irreversible"));
    }
  }

  @Test
  void should_fail_preflight_when_database_unreachable() throws IOException {
    try (HotfixFixture f = withDatabase(tmp)) {
      Path zip = f.build(f.bundleDir("sql", sqlManifest("snapshot", ""), files(true)));
      f.jdbc.connectFailure =
          Optional.of(new JdbcException(JdbcException.Kind.CONNECT_FAILED, "refused"));
      RunOutcome outcome = f.run(f.ops().planApply(zip, SIGNED), "r-db-down");
      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) outcome).stepId()).isEqualTo("preflight");
      assertThat(((RunOutcome.PrecheckFailed) outcome).message()).contains("database: refused");
    }
  }

  private static HotfixFixture withDatabase(Path root) throws IOException {
    return HotfixFixture.create(root, HotfixFixture.databaseYaml(root));
  }
}
