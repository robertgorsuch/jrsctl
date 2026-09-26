package com.jaspersoft.jrsctl.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.engine.LockHeldException;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunIds;
import com.jaspersoft.jrsctl.core.engine.RunLock;
import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.engine.Transition;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.state.AuditActor;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StoredPlan;
import com.jaspersoft.jrsctl.ops.PlanRegistry;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.retention.RetentionPruner;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl runs list|show|recover|prune|support-bundle} (spec §5.5, §5.6, §6.6, §12.4).
 * Invariants: {@code list} and {@code show} read the journal only; {@code recover} rebuilds the
 * pending run's plan from its stored arguments through {@link PlanRegistry} (never from the
 * serialised plan, which cannot carry step code) and then resumes or rolls back through {@link
 * PlanExecutor}, so it holds the run lock and journals every transition; a run that is not pending,
 * has no stored plan, or whose plan cannot be rebuilt exits 2 without touching anything; {@code
 * prune} holds the run lock while it removes snapshots (exit 9 when a run holds it) and {@code
 * --dry-run} changes nothing; {@code support-bundle} refuses a bad {@code --out} before touching
 * the state store and never leaves a partial zip behind.
 */
@Command(
    name = "runs",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Inspect past runs and recover interrupted ones.",
    subcommands = {
      RunsCommand.ListRuns.class,
      RunsCommand.Show.class,
      RunsCommand.Recover.class,
      RunsCommand.Prune.class,
      RunsCommand.SupportBundleCommand.class
    })
final class RunsCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  static String duration(RunRecord run, Clock clock) {
    Instant end = run.endedAt().orElseGet(clock::instant);
    Duration d = Duration.between(run.startedAt(), end);
    if (d.isNegative()) {
      d = Duration.ZERO;
    }
    String text =
        d.toHours() > 0
            ? String.format(Locale.ROOT, "%dh%02dm", d.toHours(), d.toMinutesPart())
            : d.toMinutes() > 0
                ? String.format(Locale.ROOT, "%dm%02ds", d.toMinutes(), d.toSecondsPart())
                : String.format(Locale.ROOT, "%.1fs", d.toMillis() / 1000.0);
    return run.endedAt().isPresent() ? text : text + " (running)";
  }

  static String outcome(RunRecord run) {
    return run.terminalState()
        .map(s -> s.name() + run.exitCode().map(c -> " (exit " + c + ")").orElse(""))
        .orElse("PENDING");
  }

  static Map<String, Object> runTree(RunRecord run, Clock clock) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("runId", run.runId());
    m.put("operation", run.operation());
    m.put("planId", run.planId());
    m.put("startedAt", run.startedAt());
    m.put("endedAt", run.endedAt());
    m.put(
        "durationMillis",
        Duration.between(run.startedAt(), run.endedAt().orElseGet(clock::instant)).toMillis());
    m.put("terminalState", run.terminalState());
    m.put("exitCode", run.exitCode());
    m.put("pending", run.pending());
    return m;
  }

  /** The `runs show --json` document (runs-show.schema.json); the support bundle's run.json. */
  static Map<String, Object> showTree(
      RunRecord run,
      Optional<JsonNode> plan,
      List<Transition> transitions,
      List<SnapshotRecord> snapshots,
      Clock clock) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("run", runTree(run, clock));
    root.put("plan", plan);
    root.put("transitions", transitions);
    root.put("snapshots", snapshots);
    return root;
  }

  static JsonNode parse(String json) {
    try {
      return Json.mapper().readTree(json);
    } catch (IOException e) {
      return Json.mapper().createObjectNode();
    }
  }

  /**
   * {@code jrsctl runs list [--json] [--limit N] [--status S]... [--operation OP] [--since WHEN]}:
   * the filters apply before the limit (issue #186).
   */
  @Command(
      name = "list",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "List runs, most recent first.")
  static final class ListRuns implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Option(names = "--limit", paramLabel = "<n>", description = "Maximum rows (default 50).")
    int limit = 50;

    @Option(
        names = "--status",
        paramLabel = "<status>",
        split = ",",
        converter = RunFilter.StatusConverter.class,
        description =
            "Only runs that ended this way: succeeded, failed, rolled-back, cancelled,"
                + " precheck-failed or pending. Repeat or separate with commas.")
    List<RunFilter.Status> statuses = new ArrayList<>();

    @Option(
        names = "--operation",
        paramLabel = "<name>",
        description = "Only this operation or those under it: hotfix, hotfix.apply, import, ...")
    Optional<String> operation = Optional.empty();

    @Option(
        names = "--since",
        paramLabel = "<when>",
        converter = RunFilter.SinceConverter.class,
        description =
            "Only runs started at or after a date (2026-09-20, UTC), an instant, or an age"
                + " (7d, 12h, 30m).")
    Optional<RunFilter.Since> since = Optional.empty();

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        RunFilter filter = new RunFilter(Set.copyOf(statuses), operation, since);
        int max = Math.max(1, limit);
        List<RunRecord> runs =
            filter.empty()
                ? services.stateStore().get().runs(max)
                : services.stateStore().get().runs(Integer.MAX_VALUE).stream()
                    .filter(r -> filter.matches(r, services.clock()))
                    .limit(max)
                    .toList();
        if (global.json()) {
          List<Map<String, Object>> rows = new ArrayList<>();
          for (RunRecord r : runs) {
            rows.add(runTree(r, services.clock()));
          }
          out.println(redactor.redact(JsonOut.write(rows)));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        if (runs.isEmpty()) {
          out.println("no runs recorded");
          out.flush();
          return ExitCodes.SUCCESS;
        }
        Ansi ansi = Ansi.forStdout(global, Env.vars());
        TextTable table = new TextTable(Terminal.width(Env.vars()));
        table.row(
            ansi.dim("RUN"),
            ansi.dim("OPERATION"),
            ansi.dim("STARTED"),
            ansi.dim("DURATION"),
            ansi.dim("OUTCOME"));
        for (RunRecord r : runs) {
          table.row(
              r.runId(),
              r.operation(),
              r.startedAt().truncatedTo(ChronoUnit.SECONDS).toString(),
              duration(r, services.clock()),
              outcome(r));
        }
        for (String line : table.lines()) {
          out.println(redactor.redact(line));
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrsctl runs show <id> [--json]}: plan summary, step transitions, backups. */
  @Command(
      name = "show",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Show one run: its plan summary, every step transition and its backups.")
  static final class Show implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<id>", description = "Run id from `runs list`.")
    String runId;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        StateStore store = services.stateStore().get();
        RunRef.Lookup lookup = RunRef.lookup(store, runId, out, err, global.json());
        if (lookup instanceof RunRef.Lookup.Exit exit) {
          return exit.code();
        }
        RunRecord run = ((RunRef.Lookup.Run) lookup).run();
        runId = run.runId();
        Optional<StoredPlan> plan = run.planId().flatMap(store::loadPlan);
        Optional<JsonNode> planTree = plan.map(p -> parse(p.planJson()));
        List<Transition> transitions = store.transitions(runId);
        List<SnapshotRecord> snapshots = store.snapshots(runId);
        if (global.json()) {
          out.println(
              redactor.redact(
                  JsonOut.write(
                      showTree(run, planTree, transitions, snapshots, services.clock()))));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Run  " + run.runId() + "  " + run.operation() + "  " + outcome(run));
        lines.add(
            "  started  " + run.startedAt() + "  duration  " + duration(run, services.clock()));
        lines.add("Plan");
        if (planTree.isPresent()) {
          JsonNode summary = planTree.get().path("summary");
          TextTable t = new TextTable();
          t.row("  id", plan.get().planId());
          t.row("  operation", summary.path("operation").asText());
          t.row("  target", summary.path("target").asText());
          t.row("  files", Integer.toString(summary.path("filesTouched").size()));
          t.row(
              "  service",
              summary.path("serviceRestart").asBoolean(false) ? "restart required" : "no restart");
          t.row("  strategy", summary.path("strategy").asText());
          t.row("  fingerprint", planTree.get().path("fingerprint").path("value").asText());
          lines.addAll(t.lines());
          for (JsonNode w : summary.path("warnings")) {
            lines.add("  ! " + w.asText());
          }
        } else {
          lines.add("  (no stored plan)");
        }
        lines.add("Steps");
        if (transitions.isEmpty()) {
          lines.add("  (no transitions recorded)");
        } else {
          TextTable t = new TextTable();
          for (Transition tr : transitions) {
            t.row(
                "  " + tr.ts(),
                tr.phase(),
                tr.stepId(),
                tr.fromState().orElse("-") + " -> " + tr.toState(),
                tr.detail().orElse(""));
          }
          lines.addAll(t.lines());
        }
        lines.add("Backups");
        if (snapshots.isEmpty()) {
          lines.add("  (none)");
        } else {
          for (SnapshotRecord s : snapshots) {
            lines.add("  " + s.id() + "  " + s.path() + "  (step " + s.stepId() + ")");
          }
        }
        for (String line : lines) {
          out.println(redactor.redact(line.stripTrailing()));
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrsctl runs recover <id> --resume|--rollback}. */
  @Command(
      name = "recover",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Continue an interrupted run from its interrupted step, or undo it.")
  static final class Recover implements Callable<Integer> {

    /** Exactly one of the two modes. */
    static final class Mode {
      @Option(names = "--resume", description = "Re-run the interrupted step and continue.")
      boolean resume;

      @Option(names = "--rollback", description = "Compensate every succeeded step in reverse.")
      boolean rollback;
    }

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<id>", description = "Pending run id.")
    String runId;

    @ArgGroup(exclusive = true, multiplicity = "1")
    Mode mode;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        StateStore store = services.stateStore().get();
        RunRef.Lookup lookup = RunRef.lookup(store, runId, out, err, global.json());
        if (lookup instanceof RunRef.Lookup.Exit exit) {
          return exit.code();
        }
        RunRecord run = ((RunRef.Lookup.Run) lookup).run();
        runId = run.runId();
        if (!run.pending()) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              "run "
                  + runId
                  + " already ended with state "
                  + run.terminalState().map(Enum::name).orElse("?"),
              Optional.of("nothing to recover"));
        }
        Optional<StoredPlan> stored = run.planId().flatMap(store::loadPlan);
        if (stored.isEmpty()) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              "run " + runId + " has no stored plan",
              Optional.of(
                  "restore the backups listed by `jrsctl runs show " + runId + "` manually"));
        }
        Plan plan;
        try {
          PlanRegistry registry =
              new PlanRegistry(
                  () -> HotfixOps.open(services),
                  () -> EximOps.open(services),
                  () -> new com.jaspersoft.jrsctl.ops.upgrade.DefaultUpgradeOperations(services));
          plan = registry.rebuild(stored.get().operation(), stored.get().argsJson());
        } catch (RuntimeException e) {
          return ExitCodes.reportPlanningFailure(out, err, global.json(), e);
        }
        store.audit(
            AuditActor.current(),
            "runs.recover",
            runId + (mode.resume ? " --resume" : " --rollback"));
        PlanExecutor executor = new PlanExecutor(services, global, out, err, Env.vars());
        return executor.recover(runId, plan, mode.resume);
      }
    }
  }

  /**
   * {@code jrsctl runs prune [--dry-run] [--json]} (spec §5.6): retention pruning per {@code
   * backups.retentionDays} and {@code backups.maxSnapshots}; snapshots of an installed hotfix, a
   * registered customization, the most recent successful upgrade or a pending run are never
   * removed. JSON shape: {@code {"dryRun":bool,"removed":[{"id","runId","stepId","path"}],"kept":n,
   * "protected":n}}.
   */
  @Command(
      name = "prune",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Remove snapshots beyond backups.retentionDays / backups.maxSnapshots;"
              + " referenced snapshots are always kept.")
  static final class Prune implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Option(names = "--dry-run", description = "List what would be removed and change nothing.")
    boolean dryRun;

    @Override
    public Integer call() throws IOException {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        RetentionPruner pruner = RetentionPruner.of(services);
        RetentionPruner.Result result;
        if (dryRun) {
          result = pruner.prune(true);
        } else {
          String lockId = "prune-" + RunIds.next(services.clock());
          try (RunLock unusedLock =
              new RunLock(services.home(), lockId, services.clock().instant())) {
            result = pruner.prune(false);
          } catch (LockHeldException held) {
            err.println(
                redactor.redact(
                    "error: run lock is held by run "
                        + held.holderRunId()
                        + " (pid "
                        + held.holderPid()
                        + "); wait for it to finish or check `jrsctl runs list`"));
            err.flush();
            return ExitCodes.LOCK_HELD;
          }
        }
        if (global.json()) {
          out.println(redactor.redact(JsonOut.write(tree(result))));
          out.flush();
          return ExitCodes.SUCCESS;
        }
        Ansi ansi = Ansi.forStdout(global, Env.vars());
        List<String> lines = new ArrayList<>();
        if (!result.removed().isEmpty()) {
          TextTable table = new TextTable();
          table.row(ansi.dim("SNAPSHOT"), ansi.dim("RUN"), ansi.dim("STEP"), ansi.dim("PATH"));
          for (RetentionPruner.Removed r : result.removed()) {
            table.row(r.id(), r.runId(), r.stepId(), r.path().toString());
          }
          lines.addAll(table.lines());
        }
        String verb = result.dryRun() ? "would remove " : "removed ";
        lines.add(
            (result.removed().isEmpty() ? "nothing to prune" : verb + result.removed().size())
                + (result.removed().isEmpty() ? "" : " snapshot(s)")
                + "; "
                + (result.dryRun() ? "keeping " : "kept ")
                + result.kept()
                + " ("
                + result.protectedCount()
                + " protected)"
                + (result.dryRun() ? "  [dry run, nothing has changed]" : ""));
        for (String line : lines) {
          out.println(redactor.redact(line));
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }

    static Map<String, Object> tree(RetentionPruner.Result result) {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("dryRun", result.dryRun());
      List<Map<String, Object>> removed = new ArrayList<>();
      for (RetentionPruner.Removed r : result.removed()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("runId", r.runId());
        m.put("stepId", r.stepId());
        m.put("path", r.path().toString());
        removed.add(m);
      }
      root.put("removed", removed);
      root.put("kept", result.kept());
      root.put("protected", result.protectedCount());
      return root;
    }
  }

  /**
   * {@code jrsctl runs support-bundle <id> [--out <zip>] [--json]}: the support bundle as a file.
   */
  @Command(
      name = "support-bundle",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Write one run's support bundle: run, plan, step transitions, doctor report, server"
              + " identity, redacted configuration, log tails and the vendor's own logs, all"
              + " redacted.")
  static final class SupportBundleCommand implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<id>", description = "Run id from `runs list`.")
    String runId;

    @Option(
        names = "--out",
        paramLabel = "<zip>",
        description =
            "File to write (default: <id>-support-bundle.zip in the current directory, or in the"
                + " jrsctl home when the current directory is inside the unpacked distribution).")
    Path out;

    @Override
    public Integer call() throws IOException {
      PrintWriter o = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      Redactor redactor = Redactor.global();
      // every refusal of a given --out happens before Bootstrap.open: a typo in --out must not
      // pay for a doctor run, and must not touch the state store (review finding 2)
      if (out != null) {
        Optional<Integer> refused = refuseTarget(out.toAbsolutePath(), o, err);
        if (refused.isPresent()) {
          return refused.get();
        }
      }
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        RunRef.Lookup lookup =
            RunRef.lookup(services.stateStore().get(), runId, o, err, global.json());
        if (lookup instanceof RunRef.Lookup.Exit exit) {
          return exit.code();
        }
        RunRecord run = ((RunRef.Lookup.Run) lookup).run();
        Path target =
            (out != null
                    ? out
                    : defaultOut(
                        run.runId(),
                        Path.of("").toAbsolutePath(),
                        distributionRoot(),
                        global
                            .home()
                            .map(h -> h.toAbsolutePath().normalize())
                            .orElseGet(() -> LogFile.home(new String[0], Env.vars()))))
                .toAbsolutePath();
        if (out == null) {
          // the default name needs the full run id, so its checks follow the lookup
          Optional<Integer> refused = refuseTarget(target, o, err);
          if (refused.isPresent()) {
            return refused.get();
          }
        }
        SupportBundle bundle = new SupportBundle(services);
        SupportBundle.Prepared prepared = bundle.prepare(run); // everything that can fail
        try {
          try (OutputStream zip = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            bundle.write(prepared, zip);
          }
        } catch (FileAlreadyExistsException raced) {
          // the file appeared between the precheck above and this open (review: closes the race)
          return ExitCodes.fail(
              o,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              target + " already exists",
              Optional.of("choose another --out or move the old bundle"));
        } catch (AccessDeniedException denied) {
          Files.deleteIfExists(target);
          return ExitCodes.fail(
              o,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              "cannot write " + target + ": permission denied");
        } catch (IOException | RuntimeException failed) {
          // a bundle that fails partway must not leave a truncated or zero-byte zip behind
          Files.deleteIfExists(target);
          throw failed;
        }
        List<String> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(target))) {
          for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
            entries.add(e.getName());
          }
        }
        if (global.json()) {
          Map<String, Object> doc = new LinkedHashMap<>();
          doc.put("runId", run.runId());
          doc.put("path", target.toString());
          doc.put("entries", entries);
          doc.put("bytes", Files.size(target));
          o.println(redactor.redact(JsonOut.write(doc)));
        } else {
          o.println(
              redactor.redact(
                  "Support bundle written: " + target + " (" + entries.size() + " entries)"));
        }
        o.flush();
        return ExitCodes.SUCCESS;
      }
    }

    /** The exit code when {@code target} cannot be written to, empty when it can. */
    private Optional<Integer> refuseTarget(Path target, PrintWriter o, PrintWriter err) {
      if (Files.isDirectory(target)) {
        return Optional.of(
            ExitCodes.fail(
                o,
                err,
                global.json(),
                ExitCodes.PRECHECK_FAILED,
                target + " is a directory",
                Optional.of("name a file")));
      }
      if (Files.exists(target)) {
        return Optional.of(
            ExitCodes.fail(
                o,
                err,
                global.json(),
                ExitCodes.PRECHECK_FAILED,
                target + " already exists",
                Optional.of("choose another --out or move the old bundle")));
      }
      Path parent = target.getParent();
      if (parent != null && !Files.isDirectory(parent)) {
        return Optional.of(
            ExitCodes.fail(
                o,
                err,
                global.json(),
                ExitCodes.PRECHECK_FAILED,
                parent + " is not a directory",
                Optional.of("create it or choose another --out")));
      }
      return Optional.empty();
    }
  }

  /**
   * Where {@code runs support-bundle} writes without {@code --out} (#161): the current directory,
   * unless that is inside the unpacked distribution, which jrsctl never writes to (operator guide,
   * "Installing"); then the jrsctl home.
   */
  static Path defaultOut(String runId, Path cwd, Optional<Path> distribution, Path home) {
    String name = runId + "-support-bundle.zip";
    Path here = cwd.toAbsolutePath().normalize();
    boolean inside =
        distribution.map(d -> here.startsWith(d.toAbsolutePath().normalize())).orElse(false);
    return (inside ? home : here).resolve(name);
  }

  /**
   * The unpacked distribution this process runs from: the directory above {@code lib/} holding the
   * jar. Empty when jrsctl runs from anywhere else (a build tree, a test).
   */
  static Optional<Path> distributionRoot() {
    try {
      Path jar =
          Path.of(RunsCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path lib = jar.getParent();
      if (jar.getFileName().toString().endsWith(".jar")
          && lib != null
          && lib.getFileName() != null
          && lib.getFileName().toString().equals("lib")
          && lib.getParent() != null) {
        return Optional.of(lib.getParent());
      }
    } catch (java.net.URISyntaxException | RuntimeException e) {
      // no code source to reason about
    }
    return Optional.empty();
  }
}
