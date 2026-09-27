package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticResolution;
import com.jaspersoft.jrsctl.jrs.vendor.VendorRun;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs {@code js-export}, with the service stopped or running as the request asks (#67), writing to
 * {@code <output>.part} and renaming onto {@code output} when the tool exits 0, its export command
 * printed {@code Done} with no error, and it produced a non-empty archive (spec §7.4, §9.2): the
 * wrapper exits 0 when the command threw, which could leave a partial archive behind (issue #40).
 * Mutates only the local filesystem; re-execution overwrites the partial file; compensation deletes
 * the partial and the final archive.
 */
final class RunJsExport implements Step {

  static final String ID = "export.js-export";

  private final ExportRequest request;
  private final VendorAccess vendor;

  RunJsExport(ExportRequest request, VendorAccess vendor) {
    this.request = Objects.requireNonNull(request, "request");
    this.vendor = Objects.requireNonNull(vendor, "vendor");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Run js-export";
  }

  @Override
  public String phase() {
    return VendorCliStrategy.EXPORT_PHASE;
  }

  @Override
  public String detail() {
    return request.output().toString();
  }

  @Override
  public CheckResult precheck(Context ctx) {
    Optional<CheckResult> spaced = VendorAccess.refuseSpaces(ctx, request.output(), "output path");
    if (spaced.isPresent()) {
      return spaced.get();
    }
    Path parent = request.output().toAbsolutePath().getParent();
    if (parent != null && Files.exists(parent) && !Files.isDirectory(parent)) {
      return CheckResult.fail(parent + " is not a directory", "choose another output path");
    }
    if (Files.exists(request.output())) {
      return CheckResult.warn(request.output() + " exists and will be replaced");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path output = request.output();
    Path part = RunFiles.partOf(output);
    BuildomaticResolution resolved = vendor.resolve(ctx);
    if (resolved instanceof BuildomaticResolution.NotFound missing) {
      return Failures.recoverable(missing.detail(), List.of(), missing.remediation());
    }
    Optional<Buildomatic> b = resolved.located();
    try {
      Path parent = output.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.deleteIfExists(part);
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot prepare " + output + ": " + e.getMessage(),
          List.of(output),
          "check that the output directory is writable");
    }
    Config config = ctx.service(Config.class);
    VendorRun run =
        vendor
            .tools()
            .apply(ctx)
            .export(b.get(), request, part, config.vendor().javaHome(), out, Logs.scope(ctx, this));
    return switch (run) {
      case VendorRun.Completed c ->
          c.processed() ? finish(ctx, out, part, output) : failed(c, part);
      case VendorRun.TimedOut t ->
          Failures.recoverable(
              "js-export did not finish within " + t.timeout().toMinutes() + " minutes",
              List.of(part),
              "check for a hung buildomatic process, then run the export again");
      case VendorRun.NotStarted n -> Failures.recoverable(n.reason(), List.of(), n.remediation());
    };
  }

  private StepResult finish(Context ctx, EventSink out, Path part, Path output) {
    try {
      long size = Files.exists(part) ? Files.size(part) : 0L;
      if (size <= 0) {
        Files.deleteIfExists(part);
        return Failures.recoverable(
            "js-export exited 0 but wrote no archive at " + part,
            List.of(part),
            VendorTools.outputHint("js-export") + ", then run again");
      }
      RunFiles.replace(part, output);
      Logs.info(out, ctx, this, "exported " + size + " bytes to " + output);
      return StepResult.ok();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot finish writing " + output + ": " + e.getMessage(),
          List.of(part, output),
          "check disk space and permissions");
    }
  }

  private static StepResult failed(VendorRun.Completed c, Path part) {
    return Failures.recoverable(
        "js-export " + c.summary() + ": " + String.join(" | ", c.tail()),
        List.of(part),
        VendorTools.outputHint("js-export")
            + "; check the database connection in default_master.properties");
  }

  @Override
  public CheckResult postcheck(Context ctx) {
    try {
      if (!Files.isRegularFile(request.output()) || Files.size(request.output()) <= 0) {
        return CheckResult.fail(request.output() + " is missing or empty", "run again");
      }
    } catch (IOException e) {
      return CheckResult.fail("cannot inspect " + request.output(), "check the path");
    }
    Optional<String> empty = ExportArchives.problem(request.output());
    if (empty.isPresent()) {
      return CheckResult.fail(empty.get(), "check the --uri values against the repository");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    Path part = RunFiles.partOf(request.output());
    try {
      RunFiles.delete(part);
      RunFiles.delete(request.output());
      return StepResult.ok();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot delete " + request.output() + ": " + e.getMessage(),
          List.of(part, request.output()),
          "delete the file by hand");
    }
  }
}
