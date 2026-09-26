package com.jaspersoft.jrsctl.core.state;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.engine.Journal;
import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.engine.TerminalState;
import com.jaspersoft.jrsctl.core.engine.Transition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SQLite state store at {@code $JRSCTL_HOME/state.db}: the single source of truth for run
 * state, installed hotfixes, plans, snapshots and audit (spec §5.4). Invariants: opened with WAL,
 * {@code synchronous=FULL}, foreign keys on and a 5 s busy timeout; migrations run on open and are
 * idempotent; every write happens inside a {@code BEGIN IMMEDIATE ... COMMIT} transaction; {@code
 * step_transitions} and {@code audit} are append-only (enforced by triggers); every public method
 * is thread-safe and never returns {@code null}. {@code SQLException} never escapes; failures are
 * reported as {@link StateStoreException}. A damaged file is refused on open: {@code PRAGMA
 * quick_check} runs before the migrations and a result other than {@code ok} raises {@link
 * StateStoreException} naming the file and the way out ({@link #corruptionRemediation}), so
 * corruption surfaces at start rather than in whatever query first touches it. {@link #close()}
 * never throws: it runs after a run's outcome is journaled, and a failure to close is logged.
 */
public final class StateStore implements Journal, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(StateStore.class);

  /** How long a statement waits for another connection's write lock before giving up. */
  private static final int BUSY_TIMEOUT_MS = 5000;

  private static final int OPEN_ATTEMPTS = 20;
  private static final long OPEN_RETRY_MILLIS = 250;

  private final Db db;
  private final Servers servers;
  private final Hotfixes hotfixes;
  private final Customizations customizations;
  private final Plans plans;
  private final Runs runs;
  private final Transitions transitions;
  private final Snapshots snapshots;
  private final Audit audit;

  StateStore(Connection conn, Path file, Clock clock) {
    this.db = new Db(conn, file);
    this.servers = new Servers(db);
    this.hotfixes = new Hotfixes(db);
    this.customizations = new Customizations(db);
    this.plans = new Plans(db);
    this.runs = new Runs(db);
    this.transitions = new Transitions(db, clock);
    this.snapshots = new Snapshots(db);
    this.audit = new Audit(db, clock);
  }

  public static StateStore open(JrsctlHome home) {
    return open(home.stateDb(), Clock.systemUTC());
  }

  public static StateStore open(JrsctlHome home, Clock clock) {
    return open(home.stateDb(), clock);
  }

  public static StateStore open(Path dbFile, Clock clock) {
    Path abs = dbFile.toAbsolutePath().normalize();
    try {
      Path parent = abs.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
    } catch (IOException e) {
      throw new StateStoreException("cannot create directory for " + abs, e);
    }
    Connection c;
    try {
      c = DriverManager.getConnection("jdbc:sqlite:" + abs);
    } catch (SQLException e) {
      throw new StateStoreException("cannot open state store " + abs, e);
    }
    try {
      initialise(c, clock, abs);
    } catch (SQLException | RuntimeException e) {
      try {
        c.close();
      } catch (SQLException suppressed) {
        e.addSuppressed(suppressed);
      }
      if (e instanceof RuntimeException re) {
        throw re;
      }
      throw new StateStoreException("cannot initialise state store " + abs, e);
    }
    return new StateStore(c, abs, clock);
  }

  /**
   * Pragmas, integrity check and migrations, retried while another process is doing the same
   * (review 5.4). Switching a fresh database to WAL takes an exclusive lock, and two jrsctl
   * processes opening one home at the same moment - two commands, {@code runs list} and a run, say
   * - used to leave one of them with SQLITE_BUSY and no store at all. Every statement here is
   * idempotent, so the whole sequence can simply be run again.
   */
  private static void initialise(Connection c, Clock clock, Path abs) throws SQLException {
    SQLException lastBusy = null;
    for (int attempt = 1; attempt <= OPEN_ATTEMPTS; attempt++) {
      try {
        try (Statement s = c.createStatement()) {
          s.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
          s.execute("PRAGMA journal_mode=WAL");
          s.execute("PRAGMA synchronous=FULL");
          s.execute("PRAGMA foreign_keys=ON");
        }
        String check = quickCheck(c);
        if (!"ok".equals(check)) {
          throw new StateStoreException(
              "state store "
                  + abs
                  + " failed PRAGMA quick_check: "
                  + check
                  + "; "
                  + corruptionRemediation(abs));
        }
        Migrations.apply(c, clock);
        return;
      } catch (SQLException e) {
        if (!busy(e) || attempt == OPEN_ATTEMPTS) {
          throw e;
        }
        lastBusy = e;
        LOG.debug("state store {} is busy on open, attempt {}", abs, attempt);
        sleepQuietly();
      }
    }
    throw lastBusy;
  }

  /** True for the two codes SQLite uses while another connection holds the write lock. */
  // getCause() == t is the guard against a throwable that names itself as its cause
  @SuppressWarnings("ReferenceEquality")
  private static boolean busy(SQLException e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      String message = t.getMessage() == null ? "" : t.getMessage();
      if (message.contains("SQLITE_BUSY") || message.contains("SQLITE_LOCKED")) {
        return true;
      }
    }
    return false;
  }

  private static void sleepQuietly() {
    try {
      Thread.sleep(OPEN_RETRY_MILLIS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  public Path file() {
    return db.file();
  }

  /**
   * {@code PRAGMA quick_check}: {@code "ok"} for a sound database, otherwise the first problems
   * SQLite reports, joined by {@code "; "}. Read-only; {@code doctor} shows it.
   */
  public String integrity() {
    return db.read(StateStore::quickCheck);
  }

  /** What an operator does with a state store that fails its integrity check. */
  public static String corruptionRemediation(Path file) {
    return "stop every jrsctl process, move "
        + file
        + " aside (for example to state.db.corrupt-<date>), then restore state.db from the most"
        + " recent support bundle or let jrsctl create a fresh one; runs and installed hotfixes"
        + " recorded only in the damaged file are not recoverable from it";
  }

  private static String quickCheck(Connection c) throws SQLException {
    List<String> problems = new ArrayList<>();
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("PRAGMA quick_check(3)")) {
      while (rs.next()) {
        problems.add(rs.getString(1));
      }
    }
    if (problems.size() == 1 && "ok".equals(problems.get(0))) {
      return "ok";
    }
    return String.join("; ", problems);
  }

  public int schemaVersion() {
    return db.read(Migrations::currentVersion);
  }

  // ---- aggregates ----------------------------------------------------------------------------

  public void upsertServer(ServerRecord server) {
    servers.upsertServer(server);
  }

  public List<ServerRecord> servers() {
    return servers.servers();
  }

  /** Records an installed hotfix and its files in one transaction. */
  public void recordHotfixInstalled(HotfixInstalled hotfix, List<HotfixFile> files) {
    hotfixes.recordHotfixInstalled(hotfix, files);
  }

  public void updateHotfixState(String hotfixId, HotfixState state) {
    hotfixes.updateHotfixState(hotfixId, state);
  }

  /** Hotfixes currently in state {@code INSTALLED}, oldest first (the LIFO order for rollback). */
  public List<HotfixInstalled> installedHotfixes() {
    return hotfixes.installedHotfixes();
  }

  /** Every hotfix ever recorded, in any state, oldest first. */
  public List<HotfixInstalled> hotfixes() {
    return hotfixes.hotfixes();
  }

  public Optional<HotfixInstalled> hotfix(String id) {
    return hotfixes.hotfix(id);
  }

  public List<HotfixFile> hotfixFiles(String hotfixId) {
    return hotfixes.hotfixFiles(hotfixId);
  }

  /**
   * Files among {@code paths} that belong to a hotfix in state {@code INSTALLED}; used for the
   * overlap check (spec §8.4). Paths are compared by their string form.
   */
  public List<HotfixFile> filesOwnedBy(Collection<Path> paths) {
    return hotfixes.filesOwnedBy(paths);
  }

  public void registerCustomization(Customization customization) {
    customizations.registerCustomization(customization);
  }

  public boolean unregisterCustomization(Path path) {
    return customizations.unregisterCustomization(path);
  }

  public List<Customization> customizations() {
    return customizations.customizations();
  }

  public void savePlan(StoredPlan plan) {
    plans.savePlan(plan);
  }

  public Optional<StoredPlan> loadPlan(String planId) {
    return plans.loadPlan(planId);
  }

  /**
   * Claims an unexpired, unconsumed plan for {@code runId}; false when it is unknown, expired or
   * already consumed, so a plan can never execute twice.
   */
  public boolean consumePlan(String planId, String runId, Instant now) {
    return plans.consumePlan(planId, runId, now);
  }

  /** Deletes unconsumed plans whose TTL elapsed; returns how many were removed. */
  public int expirePlans(Instant now) {
    return plans.expirePlans(now);
  }

  @Override
  public void recordRunStart(
      String runId, String operation, Optional<String> planId, Instant startedAt) {
    runs.recordRunStart(runId, operation, planId, startedAt);
  }

  @Override
  public void recordRunEnd(String runId, Instant endedAt, TerminalState state, int exitCode) {
    runs.recordRunEnd(runId, endedAt, state, exitCode);
  }

  @Override
  public Optional<RunRecord> run(String runId) {
    return runs.run(runId);
  }

  /** Most recent runs first. */
  public List<RunRecord> runs(int limit) {
    return runs.runs(limit);
  }

  /** Runs without a terminal state, oldest first (spec §5.5). */
  @Override
  public List<RunRecord> pendingRuns() {
    return runs.pendingRuns();
  }

  /** Appends one journal row in its own transaction and returns it with its sequence number. */
  @Override
  public Transition appendTransition(
      String runId,
      String stepId,
      String phase,
      Optional<String> fromState,
      String toState,
      Optional<String> detail) {
    return transitions.appendTransition(runId, stepId, phase, fromState, toState, detail);
  }

  /** The journal of one run in write order. */
  @Override
  public List<Transition> transitions(String runId) {
    return transitions.transitions(runId);
  }

  public void recordSnapshot(SnapshotRecord snapshot) {
    snapshots.recordSnapshot(snapshot);
  }

  public Optional<SnapshotRecord> snapshot(String id) {
    return snapshots.snapshot(id);
  }

  public List<SnapshotRecord> snapshots(String runId) {
    return snapshots.snapshots(runId);
  }

  /** Every recorded snapshot row, ordered by run id then id. */
  public List<SnapshotRecord> snapshots() {
    return snapshots.snapshots();
  }

  /**
   * Removes the {@code snapshots} rows of {@code runId/stepId} after retention pruning deleted the
   * directory (spec §5.6); returns how many rows went. The journal ({@code step_transitions}) and
   * the audit trail are untouched, so the run's history still names the snapshot it once had.
   */
  public int deleteSnapshot(String runId, String stepId) {
    return snapshots.deleteSnapshot(runId, stepId);
  }

  public AuditEntry audit(String actor, String action, String detail) {
    return audit.audit(actor, action, detail);
  }

  /** Most recent audit rows first. */
  public List<AuditEntry> auditRows(int limit) {
    return audit.auditRows(limit);
  }

  // ---- maintenance ---------------------------------------------------------------------------

  /**
   * Removes every snapshot row of one run. Typed so callers never build SQL: two ops classes used
   * to concatenate an id into a {@code DELETE} string through the raw escape hatch below (roadmap
   * item 16).
   */
  public int deleteSnapshotsOf(String runId) {
    return snapshots.deleteSnapshotsOf(runId);
  }

  /** Removes one installed hotfix and the file rows it owns, in one transaction. */
  public int deleteHotfix(String hotfixId) {
    return hotfixes.deleteHotfix(hotfixId);
  }

  /**
   * An arbitrary statement inside a transaction. Package-private on purpose: it exists for the
   * store's own tests and for a maintenance console, and every caller outside this package has a
   * typed method instead.
   */
  int executeUpdate(String sql) {
    return db.write(
        c -> {
          try (Statement s = c.createStatement()) {
            return s.executeUpdate(sql);
          }
        });
  }

  /** Never throws (review finding 1.18): a close failure is logged, not turned into an error. */
  @Override
  public void close() {
    db.close();
  }
}
