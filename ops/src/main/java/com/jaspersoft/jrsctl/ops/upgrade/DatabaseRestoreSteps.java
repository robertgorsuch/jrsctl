package com.jaspersoft.jrsctl.ops.upgrade;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.VendorRun;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import com.jaspersoft.jrsctl.ops.db.JdbcConnector;
import com.jaspersoft.jrsctl.ops.db.JdbcException;
import com.jaspersoft.jrsctl.ops.db.JdbcSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR-0029: {@code upgrade rollback --restore-database} after a newdb run. {@code js-upgrade-newdb}
 * dropped the repository database and rebuilt it from the point-B export; these two steps run the
 * same procedure backwards with the restored buildomatic: {@code js-ant init-js-db-<ce|pro>} drops
 * and initialises the old schema, then {@code js-import} reloads the point-B export into it.
 * Invariants: both steps are {@code irreversible()} (a dropped database has no compensation; the
 * export is the only copy of the old data and the import that follows is what puts it back); each
 * runs its vendor command at most once per rollback run through a done marker, so a resume never
 * drops a database it already rebuilt; both refuse to plan unless the vendor script of the upgrade
 * run actually started ({@link SnapshotSet#vendorScriptStarted()}), since an untouched database
 * must never be rebuilt; the export is hash-verified before the import;; the database named in the
 * configuration is asked once when jrsctl can, a warning when it cannot.
 */
final class DatabaseRestoreSteps {

  static final String REBUILD_DATABASE = "rebuild-database";
  static final String REIMPORT_FULL_EXPORT = "reimport-full-export";
  static final String INIT_TARGET_PREFIX = "init-js-db-";

  private DatabaseRestoreSteps() {}

  static String edition(RestoreSteps.Input in) {
    return in.webappName().endsWith("-pro") ? "pro" : "ce";
  }

  abstract static class DatabaseStep implements Step {
    final UpgradeRuntime rt;
    final RestoreSteps.Input in;

    DatabaseStep(UpgradeRuntime rt, RestoreSteps.Input in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String phase() {
      return Phases.ROLLBACK;
    }

    // Dropping a database, or importing into one just initialised, has nothing to put back: the
    // point-B export is the only copy of the old repository and the import that follows the drop
    // is its restore (ADR-0029).
    @Override
    public boolean irreversible() {
      return true;
    }

    Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(id() + ".done");
    }

    Optional<Buildomatic> buildomatic() {
      return rt.locator().at(in.installedBuildomatic());
    }

    /** What both steps need before either vendor command may run. */
    CheckResult common(Context ctx) {
      Optional<String> script;
      try {
        script = in.set().vendorScriptStarted();
      } catch (IOException e) {
        return CheckResult.fail(
            "cannot read " + in.set().vendorStarted() + ": " + e.getMessage(),
            "check the snapshot set of run " + in.upgradeRunId());
      }
      if (script.isEmpty()) {
        return CheckResult.fail(
            "the vendor script never started in run "
                + in.upgradeRunId()
                + ", so the repository database is still the old one",
            "run the rollback without --restore-database");
      }
      if (!script.get().endsWith(UpgradeOperations.Mode.NEWDB.vendorSuffix())) {
        return CheckResult.fail(
            script.get() + " migrated the schema in place; an export cannot undo that",
            "restore the database from your own backup and run the rollback without"
                + " --restore-database");
      }
      if (rt.config().vendor().javaHome().isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set", "set vendor.javaHome to the JDK the old version needs");
      }
      Optional<Buildomatic> b = buildomatic();
      if (b.isEmpty()) {
        return CheckResult.fail(
            "no buildomatic at " + in.installedBuildomatic(),
            "restore-buildomatic puts the old one back; check the archive of run "
                + in.upgradeRunId());
      }
      List<String> missing = b.get().missingScripts();
      if (!missing.isEmpty()) {
        return CheckResult.fail(
            in.installedBuildomatic() + " lacks " + String.join(", ", missing),
            "the restored buildomatic must ship js-ant and js-import");
      }
      Path export = in.set().resolveFullExport();
      if (!Files.isRegularFile(export)) {
        return CheckResult.fail(
            "the point-B export " + export + " is missing",
            "without it the old repository cannot be rebuilt; restore the database from your own"
                + " backup");
      }
      try {
        Optional<SnapshotSet.ExternalExport> external = in.set().externalExport();
        if (external.isPresent()) {
          if (!rt.files().sha256(external.get().path()).equals(external.get().sha256())) {
            return CheckResult.fail(
                external.get().path() + " has changed since the upgrade adopted it",
                "do not rebuild the database from a changed export; restore it from your own"
                    + " backup");
          }
        } else {
          PointB.verifyArchive(export, rt.files().sha256(export));
        }
      } catch (IOException e) {
        return CheckResult.fail(
            "export verification failed: " + e.getMessage(),
            "do not rebuild the database from a changed export; restore it from your own backup");
      }
      return database();
    }

    /** The database named in the configuration must answer, when jrsctl can ask it at all. */
    private CheckResult database() {
      Optional<JdbcSettings> settings = JdbcSettings.from(rt.config(), rt.services().platform());
      if (settings.isEmpty()) {
        return CheckResult.pass();
      }
      try (JdbcConnector.Session session =
          settings.get().open(rt.jdbc(), rt.services().secrets())) {
        Objects.requireNonNull(session.product());
        return CheckResult.pass();
      } catch (JdbcException | SecretException e) {
        // the vendor scripts bring their own driver and refuse on their own when the database
        // does not answer; jrsctl's inability to ask (no driver directory, an unresolved secret,
        // a refused connection) is worth a line, not a stop, since nothing has been dropped yet
        return CheckResult.warn(
            "cannot confirm that the repository database answers ("
                + e.getMessage()
                + "); the vendor scripts will refuse if it does not");
      }
    }

    StepResult done(Context ctx, EventSink out, String message) {
      try {
        Files.createDirectories(marker(ctx).getParent());
        Files.writeString(marker(ctx), "done", UTF_8);
      } catch (IOException e) {
        Logs.warn(rt, ctx, out, this, "cannot write " + marker(ctx) + ": " + e.getMessage());
      }
      Logs.info(rt, ctx, out, this, message);
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      Logs.info(rt, ctx, out, this, "irreversible; nothing to put back");
      return StepResult.ok();
    }
  }

  /** {@code js-ant init-js-db-<edition>}: drop, create and initialise the old schema. */
  static final class RebuildDatabase extends DatabaseStep {
    RebuildDatabase(UpgradeRuntime rt, RestoreSteps.Input in) {
      super(rt, in);
    }

    String target() {
      return INIT_TARGET_PREFIX + edition(in);
    }

    @Override
    public String id() {
      return REBUILD_DATABASE;
    }

    @Override
    public String title() {
      return "rebuild the old repository database (js-ant " + target() + ")";
    }

    @Override
    public String detail() {
      return "drops the database js-upgrade-newdb created and initialises the old schema from "
          + in.installedBuildomatic()
          + "; the point-B export is re-imported next";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return common(ctx);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (Files.isRegularFile(marker(ctx))) {
        Logs.info(rt, ctx, out, this, "database already rebuilt in this run; skipping");
        return StepResult.ok();
      }
      Buildomatic b = buildomatic().orElseThrow();
      VendorRun run =
          rt.tools()
              .ant(
                  b,
                  target(),
                  List.of(),
                  rt.config().vendor().javaHome(),
                  out,
                  Logs.scope(ctx, this));
      return switch (run) {
        case VendorRun.Completed c ->
            c.ok() && c.reported() == VendorRun.Reported.SUCCEEDED
                ? done(ctx, out, "old schema initialised with js-ant " + target())
                : Failures.recoverable(
                    "js-ant " + target() + " " + c.summary() + ": " + String.join(" | ", c.tail()),
                    "read the buildomatic log under "
                        + in.installedBuildomatic()
                        + "; the database may be half initialised, so restore it from your own"
                        + " backup or re-run the rollback once the cause is fixed");
        case VendorRun.TimedOut t ->
            Failures.recoverable(
                "js-ant "
                    + target()
                    + " did not finish within "
                    + t.timeout().toMinutes()
                    + " minutes",
                "check for a hung buildomatic process before re-running the rollback");
        case VendorRun.NotStarted n -> Failures.recoverable(n.reason(), n.remediation());
      };
    }
  }

  /** {@code js-import} of the point-B export into the schema the previous step initialised. */
  static final class ReimportFullExport extends DatabaseStep {
    ReimportFullExport(UpgradeRuntime rt, RestoreSteps.Input in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return REIMPORT_FULL_EXPORT;
    }

    @Override
    public String title() {
      return "re-import the point-B export into the rebuilt database (js-import)";
    }

    @Override
    public String detail() {
      return "js-import --input-zip "
          + in.set().resolveFullExport()
          + " --update --include-access-events --include-audit-events"
          + " --include-monitoring-events --include-server-settings, through "
          + in.installedBuildomatic();
    }

    ImportRequest request() {
      return new ImportRequest(
          in.set().resolveFullExport(),
          true,
          false,
          true,
          true,
          true,
          true,
          false,
          Optional.empty(),
          Optional.empty(),
          BrokenDependencies.FAIL);
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return common(ctx);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (Files.isRegularFile(marker(ctx))) {
        Logs.info(rt, ctx, out, this, "export already re-imported in this run; skipping");
        return StepResult.ok();
      }
      Buildomatic b = buildomatic().orElseThrow();
      VendorRun run =
          rt.tools()
              .importArchive(
                  b,
                  request(),
                  Optional.empty(),
                  rt.config().vendor().javaHome(),
                  out,
                  Logs.scope(ctx, this));
      return switch (run) {
        case VendorRun.Completed c ->
            c.processed() && c.reported() == VendorRun.Reported.SUCCEEDED
                ? done(ctx, out, "point-B export re-imported into the rebuilt database")
                : Failures.recoverable(
                    "js-import "
                        + c.summary()
                        + ", so the export may not have been re-imported: "
                        + String.join(" | ", c.tail()),
                    VendorTools.outputHint("js-import")
                        + "; the database holds whatever the import got to,"
                        + " and re-running the rollback repeats the import with --update");
        case VendorRun.TimedOut t ->
            Failures.recoverable(
                "js-import did not finish within " + t.timeout().toMinutes() + " minutes",
                "check for a hung buildomatic process before re-running the rollback");
        case VendorRun.NotStarted n -> Failures.recoverable(n.reason(), n.remediation());
      };
    }
  }
}
