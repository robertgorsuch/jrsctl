package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.Trees;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.ops.db.JdbcSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The apply phase of the apply plan (spec §8.2): staging, the swap itself and the SQL. Invariants:
 * every step re-checks the state on disk before it acts, so re-execution after a crash converges;
 * the swap restores from the run's snapshot when compensated and the SQL runs its rollback scripts
 * in reverse.
 *
 * <p>One phase of the plan per file, as the upgrade package does it (roadmap item 17). The ids, the
 * phase names and the helpers every phase shares stay in {@link ApplySteps}.
 */
final class HotfixApplyPhaseSteps {

  private HotfixApplyPhaseSteps() {}

  /** Step 7: copy payload files into the run's staging directory and verify their hashes. */
  static final class StageFiles extends ApplySteps.ReadOnly {
    /**
     * Review finding 1.19: staging writes a tree under the run directory, so it is a mutation the
     * runner must compensate; as a read-only step its clean-up was never called and staging trees
     * accumulated after every rolled-back run.
     */
    @Override
    public boolean mutating() {
      return true;
    }

    StageFiles(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return ApplySteps.STAGE_FILES;
    }

    @Override
    public String title() {
      return "stage payload files";
    }

    @Override
    public String phase() {
      return ApplySteps.APPLY;
    }

    @Override
    public String detail() {
      return "runs/{runId}/staging, sha256 verified";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      FileOps files = rt.files();
      for (FileTarget t : in.targets()) {
        if (t.action() == Manifest.Action.DELETE) {
          continue;
        }
        ctx.cancel().checkpoint();
        Path staged = in.staged(ctx, t);
        String expected = t.after().orElse("");
        try {
          if (FileTarget.hashOf(files, staged).map(expected::equals).orElse(false)) {
            continue;
          }
          Files.createDirectories(staged.getParent());
          Files.copy(in.payload(ctx, t), staged, StandardCopyOption.REPLACE_EXISTING);
          String actual = files.sha256(staged);
          if (!actual.equals(expected)) {
            return Failures.recoverable(
                "staged " + t.manifestPath() + " hashes to " + actual + ", expected " + expected,
                "the bundle is corrupt; obtain it again");
          }
        } catch (IOException | UncheckedIOException e) {
          return Failures.recoverable(
              "cannot stage " + t.manifestPath() + ": " + e.getMessage(),
              "check free space under " + in.stagingDir(ctx));
        }
      }
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        Trees.deleteRecursively(in.stagingDir(ctx));
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot remove staging: " + e.getMessage(), "delete " + in.stagingDir(ctx));
      }
    }
  }

  /** Step 8: rename staged files into place, delete listed files; compensation restores. */
  static final class AtomicSwap implements Step {
    private final HotfixRuntime rt;
    private final ApplyInput in;

    AtomicSwap(HotfixRuntime rt, ApplyInput in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return ApplySteps.ATOMIC_SWAP;
    }

    @Override
    public String title() {
      return "swap " + in.targets().size() + " file(s) into place";
    }

    @Override
    public String phase() {
      return ApplySteps.APPLY;
    }

    @Override
    public String detail() {
      return "per-file rename, ACLs preserved; files already at the target hash are skipped";
    }

    /**
     * Every add or replace must have its staged copy, or already be in place from a swap that was
     * interrupted part-way (issue #184): a run left pending by a jrsctl that stopped the service
     * before staging can resume here with nothing staged. Existence only, so the outage is not
     * spent re-reading the payload: staging verified each hash and the postcheck re-hashes what
     * landed.
     */
    @Override
    public CheckResult precheck(Context ctx) {
      FileOps files = rt.files();
      List<String> unstaged = new ArrayList<>();
      for (FileTarget t : in.targets()) {
        if (t.action() == Manifest.Action.DELETE || Files.isRegularFile(in.staged(ctx, t))) {
          continue;
        }
        String expected = t.after().orElse("");
        if (!FileTarget.hashOf(files, t.target()).map(expected::equals).orElse(false)) {
          unstaged.add(t.manifestPath());
        }
      }
      if (!unstaged.isEmpty()) {
        return CheckResult.fail(
            "not staged and not in place: " + String.join(", ", unstaged),
            "stage-files never ran for this run (a run left pending by an older jrsctl, which"
                + " stopped the service before staging, or a staging tree removed by hand)");
      }
      List<String> locked = new ArrayList<>();
      for (Path p : in.touched()) {
        if (Files.isRegularFile(p) && files.isLocked(p)) {
          locked.add(p + files.lockHolder(p).map(h -> " (held by " + h + ")").orElse(""));
        }
      }
      if (!locked.isEmpty()) {
        return CheckResult.fail(
            "still locked after the service stop: " + String.join(", ", locked),
            "end the process holding the file, then re-run");
      }
      // review 3.3: no holder found is not the same as no holder when the scan is blind
      return files
          .lockInspectionLimit()
          .map(limit -> CheckResult.warn("no locked file found, but " + limit))
          .orElseGet(CheckResult::pass);
    }

    /**
     * Assessment item O4: after the swap every landed file must still hash to what the manifest
     * promised and every deletion must have happened, so a payload removed by a sibling rule or
     * changed under the swap is never recorded as installed.
     */
    @Override
    public CheckResult postcheck(Context ctx) {
      FileOps files = rt.files();
      List<String> wrong = new ArrayList<>();
      for (FileTarget t : in.targets()) {
        switch (t.action()) {
          case ADD, REPLACE -> {
            String expected = t.after().orElse("");
            Optional<String> actual = FileTarget.hashOf(files, t.target());
            if (actual.isEmpty()) {
              wrong.add(t.target() + " is missing after the swap");
            } else if (!actual.get().equals(expected)) {
              wrong.add(
                  t.target()
                      + " hash is "
                      + actual.get()
                      + " after the swap, expected "
                      + expected);
            }
            for (FileTarget.Sibling s : t.replaces()) {
              if (Files.exists(s.path())) {
                wrong.add(s.path() + " should have been replaced but still exists");
              }
            }
          }
          case DELETE -> {
            if (Files.exists(t.target())) {
              wrong.add(t.target() + " should have been deleted but still exists");
            }
          }
        }
      }
      return wrong.isEmpty()
          ? CheckResult.pass()
          : CheckResult.fail(String.join("; ", wrong), "the run is rolled back from the snapshot");
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      FileOps files = rt.files();
      for (FileTarget t : in.targets()) {
        ctx.cancel().checkpoint();
        try {
          switch (t.action()) {
            case ADD, REPLACE -> {
              String expected = t.after().orElse("");
              if (!FileTarget.hashOf(files, t.target()).map(expected::equals).orElse(false)) {
                Path staged = in.staged(ctx, t);
                if (!Files.isRegularFile(staged)) {
                  return Failures.recoverable(
                      "staged copy of " + t.manifestPath() + " is missing",
                      "re-run; stage-files recreates it",
                      List.of(t.target()),
                      List.of());
                }
                Files.createDirectories(t.target().getParent());
                files.atomicReplace(staged, t.target());
                String actual = files.sha256(t.target());
                if (!actual.equals(expected)) {
                  return Failures.recoverable(
                      t.target() + " hashes to " + actual + " after the swap, expected " + expected,
                      "the run is rolled back from the snapshot",
                      List.of(t.target()),
                      backups(ctx));
                }
              }
              for (FileTarget.Sibling s : t.replaces()) {
                Files.deleteIfExists(s.path());
              }
            }
            case DELETE -> Files.deleteIfExists(t.target());
          }
        } catch (IOException | UncheckedIOException e) {
          return Failures.recoverable(
              "cannot swap " + t.target() + ": " + e.getMessage(),
              "the run is rolled back from the snapshot",
              List.of(t.target()),
              backups(ctx));
        }
      }
      return StepResult.ok();
    }

    /**
     * Puts the swapped files back: whatever the snapshot holds is restored, and anything this run
     * created that the snapshot does not hold is removed. "Created" is decided by the snapshot too,
     * so a file the plan believed absent but which existed at swap time is restored rather than
     * deleted.
     */
    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      List<Path> affected = new ArrayList<>();
      try {
        PriorState before = PriorState.of(rt.snapshots(), ctx, ApplySteps.SNAPSHOT);
        for (FileTarget t : in.targets()) {
          if (t.action() == Manifest.Action.DELETE) {
            continue;
          }
          boolean existed =
              before.known() ? before.before(t.target()).isPresent() : t.existedBefore();
          if (!existed) {
            affected.add(t.target());
            Files.deleteIfExists(t.target());
          }
        }
        Optional<Snapshot> snapshot = rt.snapshots().find(ctx.runId(), ApplySteps.SNAPSHOT);
        if (snapshot.isPresent()) {
          rt.snapshots().restore(snapshot.get());
        }
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot restore the original files: " + Failures.describe(e),
            "restore the snapshot by hand",
            affected,
            backups(ctx));
      }
    }

    private List<Path> backups(Context ctx) {
      return List.of(rt.home().snapshots().resolve(ctx.runId()).resolve(ApplySteps.SNAPSHOT));
    }
  }

  /**
   * Step 9: run the manifest's SQL scripts; compensation runs, in reverse, the rollback script of
   * every script that actually started.
   */
  static final class ApplySql implements Step {
    private final HotfixRuntime rt;
    private final ApplyInput in;

    ApplySql(HotfixRuntime rt, ApplyInput in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return ApplySteps.APPLY_SQL;
    }

    @Override
    public String title() {
      return "apply " + in.sqlScripts().size() + " SQL script(s)";
    }

    @Override
    public String phase() {
      return ApplySteps.APPLY;
    }

    @Override
    public String detail() {
      String db = in.dbType().map(t -> t.yamlValue()).orElse("?");
      return irreversible()
          ? "via JDBC on " + db + "; IRREVERSIBLE: " + in.manifest().rollbackNote().orElse("")
          : "via JDBC on " + db + "; rollback scripts run in reverse on failure";
    }

    /**
     * Irreversible only when the manifest says so: the author has declared there is no rollback
     * script and supplied a rollbackNote that the plan summary shows to the operator.
     */
    @Override
    public boolean irreversible() {
      return in.manifest().rollback() == Manifest.Rollback.IRREVERSIBLE;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (in.dbType().isEmpty()
          || JdbcSettings.from(rt.config(), rt.services().platform()).isEmpty()) {
        return CheckResult.fail(
            "database.type and database.url are not configured",
            "set the database section in config.yaml");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      List<String> files = new ArrayList<>();
      for (Manifest.SqlEntry s : in.sqlScripts()) {
        files.add(s.file());
      }
      return SqlRunner.run(
          rt, ctx, out, id(), phase(), in.bundleDir(ctx), files, SqlProgress.of(ctx, id()));
    }

    /**
     * Undoes only what was done. A run that failed on its first script must not also run the
     * rollback scripts of the ones after it: those reverse changes that were never made, and
     * nothing in the manifest promises they are safe out of turn. Which scripts started is read
     * from the run's own journal, so a crash mid-script still counts as started and is undone.
     */
    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      if (irreversible()) {
        return StepResult.ok();
      }
      List<String> started;
      try {
        started = SqlProgress.of(ctx, id()).started();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot read which SQL scripts ran in run " + ctx.runId() + ": " + e.getMessage(),
            "check the run directory, then undo the SQL by hand from the bundle's rollback scripts");
      }
      List<String> files = new ArrayList<>();
      List<String> unrecoverable = new ArrayList<>();
      for (int i = in.sqlScripts().size() - 1; i >= 0; i--) {
        Manifest.SqlEntry s = in.sqlScripts().get(i);
        if (!started.contains(s.file())) {
          continue;
        }
        if (s.rollbackFile().isPresent()) {
          files.add(s.rollbackFile().get());
        } else {
          unrecoverable.add(s.file());
        }
      }
      if (!unrecoverable.isEmpty()) {
        return Failures.recoverable(
            "no rollback script for " + String.join(", ", unrecoverable) + ", which ran",
            "undo those changes by hand before re-running");
      }
      if (files.isEmpty()) {
        log(ctx, out, Event.Log.Level.INFO, "no SQL script started; nothing to undo");
        return StepResult.ok();
      }
      log(
          ctx,
          out,
          Event.Log.Level.INFO,
          "undoing " + files.size() + " of " + in.sqlScripts().size() + " SQL script(s)");
      return SqlRunner.run(
          rt, ctx, out, id(), phase(), in.bundleDir(ctx), files, SqlProgress.none());
    }

    private void log(Context ctx, EventSink out, Event.Log.Level level, String message) {
      out.emit(
          new Event.Log(
              rt.clock().instant(), ctx.runId(), Optional.of(id()), phase(), level, message));
    }
  }
}
