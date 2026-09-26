package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.ops.PlanRegistry;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.upgrade.DefaultUpgradeOperations;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeException;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl upgrade} (spec §10.4): plans and runs a vendor upgrade, and {@code upgrade rollback
 * <runId> --to-point B|C [--restore-database]} restores the backups of an earlier upgrade run.
 * Invariants: {@code --mode newdb} is the default; {@code --test} rehearses with the vendor's own
 * validation and changes nothing, exiting 2 when it fails; samedb without {@code
 * --db-backup-confirmed} exits 2 before anything is planned and prints the spec §10.1 gate
 * (ADR-0012, ADR-0029: samedb migrates the schema in place, which no export undoes, while newdb's
 * own full export is what {@code upgrade rollback --restore-database} rebuilds the database from);
 * the plan is always shown and confirmed through {@link PlanExecutor} like every mutating command;
 * an unsupported upgrade path exits 6.
 */
@Command(
    name = "upgrade",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Upgrade JasperReports Server with the vendor scripts of a target package: doctor, full"
            + " backup (rollback point B), vendor upgrade, hotfix and customization reconcile,"
            + " smoke test. Backups and the full export go under the jrsctl home (--home,"
            + " JRSCTL_HOME); the plan says how much room they need.",
    subcommands = {UpgradeCommand.Rollback.class})
final class UpgradeCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  // Not `required = true`: picocli enforces a parent's required options even when the `rollback`
  // subcommand runs, so the check happens in call() instead.
  @Option(
      names = "--to",
      paramLabel = "<version>",
      description = "Target JasperReports Server version, e.g. 9.0.0 (required).")
  String to;

  @Option(
      names = "--package",
      paramLabel = "<dir>",
      description =
          "Unpacked target distribution (contains buildomatic/ and the webapp; required).")
  Path packageDir;

  @Option(
      names = "--mode",
      paramLabel = "newdb|samedb",
      defaultValue = "newdb",
      description =
          "newdb (default) drops and recreates the repository database from the full export"
              + " jrsctl takes first, and upgrade rollback --restore-database rebuilds it from"
              + " the same export; samedb migrates its schema in place, which jrsctl cannot undo.")
  String mode;

  @Option(
      names = "--db-backup-confirmed",
      description =
          "samedb only: confirm that the repository database has been backed up (audited)."
              + " newdb needs no confirmation; its own full export is the backup.")
  boolean dbBackupConfirmed;

  @Option(
      names = "--reapply-hotfixes",
      description =
          "Re-apply every installed hotfix classified REAPPLICABLE after the vendor upgrade.")
  boolean reapplyHotfixes;

  @Option(
      names = "--include-events",
      description =
          "newdb only: after the vendor run, import the access, audit and monitoring events from"
              + " the full export with the new version's js-import; js-upgrade-newdb leaves them"
              + " behind since 7.9. Without it the plan says they are left behind.")
  boolean includeEvents;

  @Option(
      names = "--migrate-passwords",
      description =
          "samedb to 10.1 or later only: after the vendor run, migrate the stored passwords to the"
              + " modern format with the new version's js-ant migrate-passwords (dry run first);"
              + " a database restore is the only way back. Refused for an older target, ignored"
              + " with a warning for newdb.")
  boolean migratePasswords;

  @Option(
      names = "--tomcat-dir",
      paramLabel = "<dir>",
      description =
          "A new Apache Tomcat to run the upgraded server in (a new generation, e.g. 10.1 or 11 for"
              + " JasperReports Server 10): the webapp is copied there before the vendor run."
              + " Needs service.kind manual.")
  Path tomcatDir;

  @Option(
      names = "--export",
      paramLabel = "<file>",
      description =
          "An export taken earlier, from this server or another one (js-export --everything or"
              + " jrsctl export --full-server), to upgrade from instead of exporting now; newdb"
              + " only. Everything changed in the repository after it was taken is lost.")
  Path export;

  @Option(
      names = "--key-alias",
      paramLabel = "<alias>",
      description =
          "Alias the export was encrypted with (the Legacy key, jrsctl export --legacy-key, is"
              + " deprecatedImportExportEncSecret); written to the target buildomatic's properties"
              + " for the import. Needs --export.")
  String keyAlias;

  @Option(
      names = "--key-password-ref",
      paramLabel = "<ref>",
      description =
          "Password of that key, as env:NAME, file:/path or enc:NAME, when the alias has one."
              + " Needs --key-alias.")
  String keyPasswordRef;

  @Option(names = "--plan", description = "Show the plan and exit without running it.")
  boolean plan;

  @Option(
      names = "--test",
      description =
          "Rehearse: run the vendor's validation of the properties, database connection and"
              + " package (js-upgrade-<mode> test) and change nothing. The service is not"
              + " stopped and nothing is backed up; a failure exits 2.")
  boolean test;

  @Option(
      names = "--rollback-all",
      description =
          "On failure compensate every step of the plan (back to point B), not just the failing"
              + " phase.")
  boolean rollbackAll;

  @Override
  public Integer call() {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    if (to == null || to.isBlank() || packageDir == null) {
      return ExitCodes.fail(
          out,
          err,
          global.json(),
          ExitCodes.USAGE,
          "--to <version> and --package <dir> are required");
    }
    UpgradeOperations.Mode parsed;
    try {
      parsed = UpgradeOperations.Mode.valueOf(mode.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return ExitCodes.fail(
          out, err, global.json(), ExitCodes.USAGE, "--mode must be newdb or samedb");
    }
    if (parsed == UpgradeOperations.Mode.SAMEDB && !dbBackupConfirmed && !test) {
      return ExitCodes.fail(
          out, err, global.json(), ExitCodes.PRECHECK_FAILED, gateMessage(parsed));
    }
    Optional<SecretRef> keyPassword;
    try {
      keyPassword = Optional.ofNullable(keyPasswordRef).map(SecretRef::parse);
    } catch (IllegalArgumentException e) {
      return ExitCodes.fail(
          out, err, global.json(), ExitCodes.USAGE, "--key-password-ref: " + e.getMessage());
    }
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      Services services = boot.services();
      UpgradeOperations.UpgradeOptions options =
          new UpgradeOperations.UpgradeOptions(
              to,
              packageDir,
              parsed,
              dbBackupConfirmed,
              reapplyHotfixes,
              Optional.ofNullable(tomcatDir),
              Optional.ofNullable(export),
              Optional.ofNullable(keyAlias),
              keyPassword,
              includeEvents,
              migratePasswords);
      Plan planned;
      try {
        DefaultUpgradeOperations ops = new DefaultUpgradeOperations(services);
        planned = test ? ops.planTest(options) : ops.planUpgrade(options);
      } catch (UpgradeException e) {
        return report(out, err, global.json(), e);
      } catch (RuntimeException e) {
        return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
      }
      PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
      int code =
          executor.execute(
              new PlanExecutor.Request(
                  planned,
                  test ? PlanRegistry.UPGRADE_TEST : PlanRegistry.UPGRADE,
                  PlanRegistry.upgradeArgs(options),
                  plan,
                  rollbackAll));
      if (test && !plan) {
        if (code == ExitCodes.SUCCESS) {
          out.println("rehearsal ok; nothing changed");
        } else if (code == ExitCodes.FAILED_ROLLED_BACK) {
          // a failed rehearsal changed nothing (the staged files were removed), which is what
          // exit 2 means; 3 would claim a rollback of the server that never happened
          return ExitCodes.PRECHECK_FAILED;
        }
      }
      return code;
    }
  }

  static String gateMessage(UpgradeOperations.Mode mode) {
    String change =
        switch (mode) {
          case SAMEDB -> "--mode samedb migrates the repository database in place";
          case NEWDB ->
              "--mode newdb drops and recreates the repository database from the full export";
        };
    return change
        + " and an export cannot undo that; back up the database yourself and pass"
        + " --db-backup-confirmed";
  }

  static int report(PrintWriter out, PrintWriter err, boolean json, UpgradeException e) {
    return ExitCodes.fail(
        out,
        err,
        json,
        e.exitCode(),
        e.getClass().getSimpleName(),
        e.getMessage(),
        Optional.of(e.remediation()),
        Map.of());
  }

  @Command(
      name = "rollback",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Restore the point-B backups of an upgrade run: webapp, buildomatic, configuration,"
              + " keystore. Files only, unless --restore-database rebuilds a newdb run's"
              + " repository database from the point-B export.")
  static final class Rollback implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<runId>", description = "Id of the upgrade run.")
    String runId;

    @Option(
        names = "--to-point",
        required = true,
        paramLabel = "B|C",
        description = "Rollback point; C restores the same point-B artefacts (spec §10.2).")
    String point;

    @Option(names = "--plan", description = "Show the plan and exit without running it.")
    boolean plan;

    @Option(
        names = "--restore-database",
        description =
            "For a newdb run: rebuild the old repository database from the point-B export with"
                + " the restored buildomatic (drops the database the upgrade created). Refused"
                + " for a samedb run.")
    boolean restoreDatabase;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      UpgradeOperations.RollbackPoint parsed;
      try {
        parsed = UpgradeOperations.RollbackPoint.valueOf(point.strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        return ExitCodes.fail(
            out, err, global.json(), ExitCodes.USAGE, "--to-point must be B or C");
      }
      UpgradeOperations.RollbackOptions options =
          new UpgradeOperations.RollbackOptions(parsed, restoreDatabase);
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        // issue #186: part of the id is enough; an unknown id reaches planRollback unchanged, so
        // its own refusal and remediation still apply
        switch (RunRef.resolve(services.stateStore().get(), runId)) {
          case RunRef.Resolved.Found f -> runId = f.run().runId();
          case RunRef.Resolved.Unknown u -> {}
          case RunRef.Resolved.Ambiguous a -> {
            return ExitCodes.fail(
                out,
                err,
                global.json(),
                ExitCodes.USAGE,
                "run id "
                    + runId
                    + " matches "
                    + a.ids().size()
                    + " runs: "
                    + String.join(", ", a.ids()),
                Optional.of("give more of the id"));
          }
        }
        Plan planned;
        try {
          planned = new DefaultUpgradeOperations(services).planRollback(runId, options);
        } catch (UpgradeException e) {
          return report(out, err, global.json(), e);
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
        return executor.execute(
            new PlanExecutor.Request(
                planned,
                PlanRegistry.UPGRADE_ROLLBACK,
                PlanRegistry.upgradeRollbackArgs(runId, options),
                plan,
                false));
      }
    }
  }
}
