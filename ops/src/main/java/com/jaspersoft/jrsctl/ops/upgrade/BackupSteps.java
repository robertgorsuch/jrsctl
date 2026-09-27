package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.DiskSpace;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import com.jaspersoft.jrsctl.jrs.strategy.ExportArchives;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.VendorRun;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase B of spec §10.2, rollback point B: full vendor export, keystore, webapp and buildomatic
 * archives, configuration files. Invariants: every step is additive under {@code
 * snapshots/<runId>/} and therefore reports {@code mutating() == false} like the hotfix snapshot
 * step (the server and the installation are untouched); every step converges on re-execution (an
 * artefact that already exists and verifies is reused); compensations keep the backups in place,
 * because a rolled-back run's backups are still the operator's safety net.
 */
final class BackupSteps {

  /** A full export's size is unknown before it is taken: the larger of 1 GB and the webapp tree. */
  static long exportEstimate(long webappTreeBytes) {
    return Math.max(1L << 30, webappTreeBytes);
  }

  static final String ADOPT_FULL_EXPORT = "adopt-full-export";

  /**
   * Takes an export the operator supplied (ADR-0028) as the point-B full export of a newdb run:
   * records its path and SHA-256 under {@code snapshots/<runId>/} so {@code run-vendor-upgrade},
   * {@code record-upgrade} and the rollback plan find it, without copying a file that may not fit
   * under the home (field test 2, U3). Invariants: the archive itself is never written to; the
   * precheck refuses a file that is not a JasperReports Server export (no {@code index.xml});
   * re-execution rewrites the same record; compensation removes it; the hash recorded here is
   * checked again right before the vendor run.
   */
  static final class AdoptFullExport implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    AdoptFullExport(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    private Path export() {
      return in.options().existingExport().orElseThrow();
    }

    @Override
    public String id() {
      return ADOPT_FULL_EXPORT;
    }

    @Override
    public String title() {
      return "adopt the export taken earlier as the point-B full export";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return export()
          + " (recorded under "
          + SnapshotSet.placeholder(rt.home())
          + "/"
          + SnapshotSet.EXTERNAL_RECORD
          + "; not copied)";
    }

    @Override
    public boolean mutating() {
      return true;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!Files.isRegularFile(export())) {
        return CheckResult.fail(
            "--export " + export() + " does not exist", "point --export at the export archive");
      }
      Optional<String> problem = ExportArchives.problem(export());
      if (problem.isPresent()) {
        return CheckResult.fail(
            problem.get(),
            "pass an export made with js-export --everything or jrsctl export --full-server");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      SnapshotSet set = in.snapshots(ctx);
      try {
        String sha = rt.files().sha256(export());
        Files.createDirectories(set.dir());
        Files.writeString(
            set.externalRecord(), export() + "\n" + sha + "\n", StandardCharsets.UTF_8);
        Logs.info(rt, ctx, out, this, "adopted " + export() + " (sha256 " + sha + ")");
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot record the adopted export: " + e.getMessage(),
            "check read access to " + export() + " and write access under " + set.dir());
      }
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      return Files.isRegularFile(in.snapshots(ctx).externalRecord())
          ? CheckResult.pass()
          : CheckResult.fail("the adopted export was not recorded", "run again");
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        Files.deleteIfExists(in.snapshots(ctx).externalRecord());
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot remove " + in.snapshots(ctx).externalRecord() + ": " + e.getMessage(),
            "delete it by hand");
      }
    }
  }

  static final String FULL_EXPORT = "full-export";
  static final String BACKUP_KEYSTORE = "backup-keystore";
  static final String BACKUP_WEBAPP = "backup-webapp";
  static final String BACKUP_CONFIG = "backup-config";
  static final String REFERENCED_BY = "upgrade";

  private BackupSteps() {}

  abstract static class Additive implements Step {
    final UpgradeRuntime rt;
    final UpgradeInput in;

    Additive(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String phase() {
      return Phases.BACKUP;
    }

    @Override
    public boolean mutating() {
      return false;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      Logs.info(rt, ctx, out, this, "backup kept under " + in.snapshots(ctx).dir());
      return StepResult.ok();
    }

    void record(Context ctx, String stepId, Snapshot snapshot) throws IOException {
      rt.store()
          .recordSnapshot(
              new SnapshotRecord(
                  ctx.runId() + "/" + stepId,
                  ctx.runId(),
                  stepId,
                  snapshot.dir(),
                  rt.files().sha256(snapshot.manifestFile()),
                  Optional.of(REFERENCED_BY)));
    }
  }

  /** Step 4: {@code js-export --everything} through the installed buildomatic. */
  static final class FullExport extends Additive {

    private final String phase;

    FullExport(UpgradeRuntime rt, UpgradeInput in) {
      this(rt, in, Phases.BACKUP);
    }

    /**
     * In newdb mode the export belongs to the vendor phase, taken right after the stop that the
     * vendor run needs anyway: the database is rebuilt from it, so nothing may change in the
     * repository between the export and the run (review §1.6, ADR-0025).
     */
    FullExport(UpgradeRuntime rt, UpgradeInput in, String phase) {
      super(rt, in);
      this.phase = Objects.requireNonNull(phase, "phase");
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public String id() {
      return FULL_EXPORT;
    }

    @Override
    public String title() {
      return "full repository export with js-export (vendor strategy)";
    }

    @Override
    public String detail() {
      return "--everything -> "
          + SnapshotSet.placeholder(rt.home())
          + "/"
          + SnapshotSet.FULL_EXPORT;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      Optional<Buildomatic> b = rt.locator().at(in.installedBuildomatic());
      if (b.isEmpty()) {
        return CheckResult.fail(
            "no buildomatic directory at " + in.installedBuildomatic(),
            "set server.buildomaticDir to the installed buildomatic directory; another volume or"
                + " a network share is fine");
      }
      if (b.get().scriptFor(Buildomatic.EXPORT_SCRIPT).isEmpty()) {
        return CheckResult.fail(
            "vendor script " + Buildomatic.EXPORT_SCRIPT + " not found in " + b.get().dir(),
            "restore the vendor scripts of the installed version");
      }
      if (rt.config().vendor().javaHome().isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set; js-export needs a JDK",
            "set vendor.javaHome in config.yaml");
      }
      // field test 2, U3: the export is the largest file of the run and had no space check
      long trees;
      try {
        trees = DiskSpace.treeBytes(in.webappDir());
      } catch (IOException e) {
        trees = 0;
      }
      long estimate = exportEstimate(trees);
      Path under = rt.home().snapshots();
      List<String> problems =
          DiskSpace.problems(
              rt.files(), List.of(new DiskSpace.Need("full export (estimate)", under, estimate)));
      if (!problems.isEmpty()) {
        String free;
        try {
          free = DiskSpace.human(rt.files().freeSpaceBytes(rt.home().snapshots()));
        } catch (IOException e) {
          free = "an unknown amount";
        }
        return CheckResult.fail(
            "the full export needs about "
                + DiskSpace.human(estimate)
                + " free under "
                + rt.home().snapshots()
                + "; "
                + free
                + " is free",
            "free space there, prune old runs with jrsctl runs prune, or run with --home <dir> or"
                + " JRSCTL_HOME pointing at a directory on a larger volume");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      SnapshotSet set = in.snapshots(ctx);
      Path output = set.fullExport();
      try {
        if (Files.isRegularFile(output) && Files.size(output) > 0) {
          String sha = rt.files().sha256(output);
          PointB.verifyArchive(output, sha);
          Logs.info(rt, ctx, out, this, "export already present at " + output + "; reusing it");
          return StepResult.ok();
        }
        Files.createDirectories(output.getParent());
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot prepare " + output + ": " + e.getMessage(),
            "check free space and permissions under " + rt.home().snapshots());
      }
      Optional<Buildomatic> b = rt.locator().at(in.installedBuildomatic());
      if (b.isEmpty()) {
        return Failures.recoverable(
            "no buildomatic directory at " + in.installedBuildomatic(),
            "set server.buildomaticDir to the installed buildomatic directory");
      }
      Path part = output.resolveSibling(output.getFileName() + ".part");
      ExportRequest request =
          new ExportRequest(
              ExportRequest.Scope.EVERYTHING, Set.of(), true, true, true, true, true, true, part);
      VendorRun run =
          rt.tools()
              .export(
                  b.get(),
                  request,
                  part,
                  rt.config().vendor().javaHome(),
                  out,
                  Logs.scope(ctx, this));
      return switch (run) {
        case VendorRun.Completed c ->
            c.processed() ? finish(ctx, out, part, output) : failed(c, part);
        case VendorRun.TimedOut t ->
            Failures.recoverable(
                "js-export did not finish within " + t.timeout().toMinutes() + " minutes",
                "check for a hung buildomatic process, then run again");
        case VendorRun.NotStarted n -> Failures.recoverable(n.reason(), n.remediation());
      };
    }

    private StepResult finish(Context ctx, EventSink out, Path part, Path output) {
      try {
        long size = Files.isRegularFile(part) ? Files.size(part) : 0L;
        if (size <= 0) {
          Files.deleteIfExists(part);
          return Failures.recoverable(
              "js-export exited 0 but wrote no archive at " + part,
              VendorTools.outputHint("js-export") + ", then run again");
        }
        Files.move(part, output, StandardCopyOption.REPLACE_EXISTING);
        PointB.writeSha(output, rt.files().sha256(output));
        Logs.info(rt, ctx, out, this, "exported " + size + " bytes to " + output);
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot finish writing " + output + ": " + e.getMessage(),
            "check disk space and permissions");
      }
    }

    private static StepResult failed(VendorRun.Completed c, Path part) {
      return Failures.recoverable(
          "js-export " + c.summary() + ": " + String.join(" | ", c.tail()),
          VendorTools.outputHint("js-export")
              + "; check the database connection in default_master.properties",
          List.of(part),
          List.of());
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      Path output = in.snapshots(ctx).fullExport();
      try {
        if (!Files.isRegularFile(output) || Files.size(output) <= 0) {
          return CheckResult.fail(output + " is missing or empty", "run again");
        }
      } catch (IOException e) {
        return CheckResult.fail("cannot inspect " + output, "check the path");
      }
      return CheckResult.pass();
    }
  }

  /** Step 5: the {@code .jrsks} / {@code .jrsksp} pair reported by the adapter. */
  static final class BackupKeystore extends Additive {

    BackupKeystore(UpgradeRuntime rt, UpgradeInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return BACKUP_KEYSTORE;
    }

    @Override
    public String title() {
      return "back up the keystore (.jrsks, .jrsksp)";
    }

    @Override
    public String detail() {
      return SnapshotSet.placeholder(rt.home()) + "/" + SnapshotSet.KEYSTORE_STEP;
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      KeystoreInfo info;
      try {
        info = rt.services().adapter().get().keystore();
      } catch (JrsUnreachableException | RestException e) {
        return Failures.recoverable(
            "cannot inspect the keystore: " + e.getMessage(), "check the server and runAsUser");
      }
      if (!info.present() || info.keystoreFile().isEmpty()) {
        Logs.warn(
            rt,
            ctx,
            out,
            this,
            "no keystore to back up: " + info.reason().orElse("keystore not found"));
        return StepResult.ok();
      }
      List<Path> files = new ArrayList<>();
      files.add(info.keystoreFile().get());
      info.propertiesFile().ifPresent(files::add);
      Path base = info.keystoreFile().get().toAbsolutePath().getParent();
      try {
        Snapshot snapshot =
            rt.snapshots().create(ctx.runId(), SnapshotSet.KEYSTORE_STEP, files, base);
        record(ctx, SnapshotSet.KEYSTORE_STEP, snapshot);
        Logs.info(
            rt, ctx, out, this, files.size() + " keystore file(s) saved to " + snapshot.dir());
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot snapshot the keystore: " + Failures.describe(e),
            "check that " + files.get(0) + " is readable by jrsctl");
      }
    }
  }

  /** Step 6: archives of the webapp and of the installed buildomatic directory. */
  static final class BackupWebapp extends Additive {

    BackupWebapp(UpgradeRuntime rt, UpgradeInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return BACKUP_WEBAPP;
    }

    @Override
    public String title() {
      return "archive the webapp and the installed buildomatic directory";
    }

    @Override
    public String detail() {
      return in.webappDir()
          + " and "
          + in.installedBuildomatic()
          + " -> "
          + SnapshotSet.placeholder(rt.home())
          + "/"
          + SnapshotSet.ARCHIVES_DIR
          + " ("
          + Archives.extension(rt.services().platform().os()).substring(1)
          + ")";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!Files.isDirectory(in.webappDir())) {
        return CheckResult.fail(
            "webapp directory " + in.webappDir() + " does not exist",
            "check server.tomcatDir and server.webappName");
      }
      // Review finding 1.16: the archives are at most as large as the trees they hold; the
      // snapshot volume must have that much room, plus the margin, before archiving starts.
      long bytes;
      try {
        bytes = DiskSpace.treeBytes(in.webappDir());
        if (Files.isDirectory(in.installedBuildomatic())) {
          bytes += DiskSpace.treeBytes(in.installedBuildomatic());
        }
      } catch (IOException e) {
        return CheckResult.fail(
            "cannot size the trees to archive: " + e.getMessage(),
            "check read access under " + in.webappDir());
      }
      List<String> problems =
          DiskSpace.problems(
              rt.files(),
              List.of(new DiskSpace.Need("webapp backup", in.snapshots(ctx).archivesDir(), bytes)));
      if (!problems.isEmpty()) {
        return CheckResult.fail(String.join("; ", problems), DiskSpace.remedy(rt.home().root()));
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      SnapshotSet set = in.snapshots(ctx);
      try {
        archive(ctx, out, in.webappDir(), set.webappArchive());
        if (Files.isDirectory(in.installedBuildomatic())) {
          archive(ctx, out, in.installedBuildomatic(), set.buildomaticArchive());
        } else {
          Logs.warn(rt, ctx, out, this, "no installed buildomatic directory to archive");
        }
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot archive: " + e.getMessage(),
            "check free space and permissions under " + rt.home().snapshots(),
            List.of(),
            List.of(set.dir()));
      }
    }

    private void archive(Context ctx, EventSink out, Path source, Path archive) throws IOException {
      if (Files.isRegularFile(archive)) {
        PointB.verifyArchive(archive, rt.files().sha256(archive));
        Logs.info(rt, ctx, out, this, archive + " already present and verified; reusing it");
        return;
      }
      long entries = Archives.create(rt.services().platform().os(), source, archive, ctx.cancel());
      PointB.writeSha(archive, rt.files().sha256(archive));
      Logs.info(
          rt, ctx, out, this, entries + " entries from " + source + " archived to " + archive);
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      Path archive = in.snapshots(ctx).webappArchive();
      return Files.isRegularFile(archive)
          ? CheckResult.pass()
          : CheckResult.fail(archive + " was not written", "run again");
    }
  }

  /** Step 7: default_master.properties, Tomcat context files, JNDI/JDBC properties. */
  static final class BackupConfig extends Additive {

    BackupConfig(UpgradeRuntime rt, UpgradeInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return BACKUP_CONFIG;
    }

    @Override
    public String title() {
      return "snapshot configuration files (default_master.properties, context.xml, JNDI)";
    }

    @Override
    public String detail() {
      return in.configFiles().size()
          + " file(s) -> "
          + SnapshotSet.placeholder(rt.home())
          + "/"
          + SnapshotSet.CONFIG_STEP;
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      List<Path> files = in.configFiles();
      if (files.isEmpty()) {
        Logs.warn(rt, ctx, out, this, "no configuration files found to snapshot");
        return StepResult.ok();
      }
      try {
        Snapshot snapshot =
            rt.snapshots()
                .create(ctx.runId(), SnapshotSet.CONFIG_STEP, files, in.paths().commonBase());
        record(ctx, SnapshotSet.CONFIG_STEP, snapshot);
        Logs.info(rt, ctx, out, this, files.size() + " file(s) saved to " + snapshot.dir());
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot snapshot configuration: " + Failures.describe(e),
            "check free space and permissions under " + rt.home().snapshots());
      }
    }
  }
}
