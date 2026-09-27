package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.VendorRun;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The events {@code js-upgrade-newdb} leaves behind (upgrade guide 10.1 p.80: "Starting version
 * 7.9.0, the js-upgrade-newdb script does not import the access, audit, monitoring data"), brought
 * over the way the installation guide says (p.256): {@code js-import --input-zip <the point-B
 * export> --include-access-events --include-audit-events --include-monitoring-events} through the
 * target buildomatic, while the server is still down after the vendor run (issue #106). Invariants:
 * runs only in newdb mode and only when the operator asked with {@code --include-events};
 * idempotent through a once-per-run marker and because the server skips events it already holds;
 * {@code irreversible()} because a vendor-phase rollback rebuilds the whole database from the same
 * export (ADR-0029), which discards these rows with everything else; a failure is recoverable and
 * names the buildomatic log.
 */
final class EventSteps {

  static final String IMPORT_EVENTS = "import-events";

  private EventSteps() {}

  static final class ImportEvents implements Step {

    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    ImportEvents(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String id() {
      return IMPORT_EVENTS;
    }

    @Override
    public String title() {
      return "import the access, audit and monitoring events the newdb script leaves behind";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return "js-import --input-zip "
          + in.options().existingExport().map(Path::toString).orElse("<point-B full export>")
          + " --include-access-events --include-audit-events --include-monitoring-events,"
          + " through the target buildomatic";
    }

    // the rows this adds are part of the database the newdb rollback rebuilds from the same
    // export (ADR-0029); there is nothing separate to put back
    @Override
    public boolean irreversible() {
      return true;
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      // nothing separate to put back; see irreversible()
      return StepResult.ok();
    }

    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(id() + ".done");
    }

    ImportRequest request(Context ctx) {
      return new ImportRequest(
          in.snapshots(ctx).resolveFullExport().toAbsolutePath().normalize(),
          false,
          false,
          true,
          true,
          true,
          false,
          false,
          Optional.empty(),
          Optional.empty(),
          BrokenDependencies.FAIL,
          in.options().keyAlias(),
          Optional.empty(),
          false);
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (in.target().buildomatic().isEmpty()) {
        return CheckResult.fail(
            "no buildomatic directory in the target package " + in.target().dir(),
            "unpack the full distribution; the events import needs its js-import");
      }
      if (in.target().buildomatic().get().scriptFor(Buildomatic.IMPORT_SCRIPT).isEmpty()) {
        return CheckResult.fail(
            "vendor script "
                + Buildomatic.IMPORT_SCRIPT
                + " not found in "
                + in.target().buildomatic().get().dir(),
            "unpack the full distribution; the events import needs its js-import");
      }
      if (rt.config().vendor().javaHome().isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set; js-import needs a JDK",
            "set vendor.javaHome in config.yaml");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (Files.isRegularFile(marker(ctx))) {
        Logs.info(rt, ctx, out, this, "events already imported in this run; skipping");
        return StepResult.ok();
      }
      Buildomatic b = in.target().buildomatic().orElseThrow();
      VendorRun run =
          rt.tools()
              .importArchive(
                  b,
                  request(ctx),
                  Optional.empty(),
                  rt.config().vendor().javaHome(),
                  out,
                  Logs.scope(ctx, this));
      return switch (run) {
        case VendorRun.Completed c -> {
          if (c.processed() && c.reported() == VendorRun.Reported.SUCCEEDED) {
            try {
              Files.createDirectories(marker(ctx).getParent());
              Files.writeString(marker(ctx), "done", StandardCharsets.UTF_8);
            } catch (IOException e) {
              Logs.warn(rt, ctx, out, this, "cannot write " + marker(ctx) + ": " + e.getMessage());
            }
            Logs.info(rt, ctx, out, this, "access, audit and monitoring events imported");
            yield StepResult.ok();
          }
          yield Failures.recoverable(
              "js-import "
                  + c.summary()
                  + ", so the events may not have been imported: "
                  + String.join(" | ", c.tail()),
              VendorTools.outputHint("js-import")
                  + "; run the vendor's js-import with the three --include-*"
                  + " flags by hand, or run the upgrade again");
        }
        case VendorRun.TimedOut t ->
            Failures.recoverable(
                "js-import did not finish within " + t.timeout().toMinutes() + " minutes",
                "check for a hung buildomatic process, then run again");
        case VendorRun.NotStarted n -> Failures.recoverable(n.reason(), n.remediation());
      };
    }
  }
}
