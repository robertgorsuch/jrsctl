package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.secrets.SecretResolver;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
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
 * Runs {@code js-import} with the service stopped (spec §7.4, §9.2). Repository-mutating; the
 * request's keystore options ({@code --keystore}, {@code --storepass}) ride on this invocation, the
 * preceding {@code ImportSourceKeystore} step only holding the backup of the server keystore for
 * rollback (review finding 2.6). Compensation is a logged no-op: the repository is restored by the
 * ops layer's pre-import snapshot (spec §9.4), which this step cannot do itself. Invariant: this
 * step reports success only on positive evidence from the tool's own output. {@code js-import.sh}
 * guards the import with {@code if [ $? -eq 0 ]} after {@code js-ant validate-database
 * validate-keystore} and has no else branch, so a validation failure imports nothing and exits 0;
 * and Ant's banner belongs to that validation only, while the import command that follows can throw
 * and the wrapper still exit 0 (issue #40). A run is therefore recorded as an import only when the
 * validation banner appeared and the import command printed {@code Done} after {@code Processing
 * started} with no error; this matters most inside the rollback, whose re-import of the pre-import
 * snapshot must fail loudly rather than report the run as rolled back.
 */
final class RunJsImport implements Step {

  static final String ID = "import.js-import";

  private final ImportRequest request;
  private final VendorAccess vendor;

  RunJsImport(ImportRequest request, VendorAccess vendor) {
    this.request = Objects.requireNonNull(request, "request");
    this.vendor = Objects.requireNonNull(vendor, "vendor");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Run js-import";
  }

  @Override
  public String phase() {
    return VendorCliStrategy.IMPORT_PHASE;
  }

  @Override
  public String detail() {
    return request.archive() + (request.update() ? " (update)" : "");
  }

  @Override
  public CheckResult precheck(Context ctx) {
    Path archive = request.archive();
    Optional<CheckResult> spaced = VendorAccess.refuseSpaces(ctx, archive, "archive path");
    if (spaced.isPresent()) {
      return spaced.get();
    }
    try {
      if (!Files.isRegularFile(archive) || Files.size(archive) <= 0) {
        return CheckResult.fail(
            "archive " + archive + " is missing or empty", "pass an export archive to import");
      }
    } catch (IOException e) {
      return CheckResult.fail("cannot inspect " + archive + ": " + e.getMessage(), "check path");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    BuildomaticResolution resolved = vendor.resolve(ctx);
    if (resolved instanceof BuildomaticResolution.NotFound missing) {
      return Failures.recoverable(missing.detail(), List.of(), missing.remediation());
    }
    Optional<Buildomatic> b = resolved.located();
    Config config = ctx.service(Config.class);
    // Review finding 2.6: the keystore options are options of the archive import itself, so
    // they ride on this invocation; the preceding ImportSourceKeystore step only holds the backup.
    Optional<Secret> storepass;
    try {
      storepass =
          request
              .sourceKeystorePassword()
              .map(ref -> ctx.service(SecretResolver.class).resolve(ref));
    } catch (SecretException e) {
      return Failures.recoverable(
          "cannot resolve the source keystore password: " + e.getMessage(),
          List.of(),
          "check --source-keystore-password-ref");
    }
    try {
      VendorRun run =
          vendor
              .tools()
              .apply(ctx)
              .importArchive(
                  b.get(),
                  request,
                  storepass,
                  config.vendor().javaHome(),
                  out,
                  Logs.scope(ctx, this));
      return switch (run) {
        case VendorRun.Completed c -> completed(c);
        case VendorRun.TimedOut t ->
            Failures.recoverable(
                "js-import did not finish within " + t.timeout().toMinutes() + " minutes",
                List.of(request.archive()),
                "check for a hung buildomatic process; the pre-import snapshot is re-imported by"
                    + " rollback");
        case VendorRun.NotStarted n -> Failures.recoverable(n.reason(), List.of(), n.remediation());
      };
    } finally {
      storepass.ifPresent(Secret::close);
    }
  }

  /**
   * Turns a finished {@code js-import} into a step result. Anything other than a clean exit with a
   * completed validation and a finished import command is a failure: the alternative is recording
   * an import that never touched the repository as verified.
   */
  private StepResult completed(VendorRun.Completed c) {
    if (!c.processed()) {
      return Failures.recoverable(
          "js-import "
              + c.summary()
              + ", so the archive may not have been imported: "
              + String.join(" | ", c.tail()),
          List.of(request.archive()),
          VendorTools.outputHint("js-import")
              + "; the pre-import snapshot is re-imported by rollback");
    }
    if (c.reported() != VendorRun.Reported.SUCCEEDED) {
      return Failures.recoverable(
          "js-import exited 0 without reporting a completed validation, so the archive may not have"
              + " been imported: "
              + String.join(" | ", c.tail()),
          List.of(request.archive()),
          "check the buildomatic log for the validate-database and validate-keystore result; the"
              + " pre-import snapshot is re-imported by rollback");
    }
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    Logs.info(out, ctx, this, StartImport.ROLLBACK_NOTE);
    return StepResult.ok();
  }
}
