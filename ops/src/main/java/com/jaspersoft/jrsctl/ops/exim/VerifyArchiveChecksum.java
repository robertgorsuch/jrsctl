package com.jaspersoft.jrsctl.ops.exim;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Compares the SHA-256 an export's sidecar recorded with the archive's own (issue #185), first in
 * the import's precheck phase, so a truncated or altered archive is refused before the pre-import
 * snapshot, the keystore check or any service stop. Invariants: non-mutating, and the whole
 * decision is in {@link #precheck}; it reads no file, because the archive's hash was computed when
 * the plan was built and the plan fingerprint covers it, so the runner refuses an archive that
 * changed after planning before this step runs; the comparison ignores case, since both sides are
 * hex; it is a step rather than a refusal at planning time so that {@code runs recover} still
 * rebuilds the plan of a run that an earlier jrsctl started (the step is missing from that run's
 * journal, so resume skips it and rollback has nothing of it to undo).
 */
final class VerifyArchiveChecksum implements Step {

  static final String ID = DefaultExportImportOperations.PRECHECK_PHASE + ".archive-checksum";

  private final Path archive;
  private final String recorded;
  private final String actual;

  VerifyArchiveChecksum(Path archive, String recorded, String actual) {
    this.archive = Objects.requireNonNull(archive, "archive");
    this.recorded = Objects.requireNonNull(recorded, "recorded");
    this.actual = Objects.requireNonNull(actual, "actual");
  }

  /** True when the archive is the one its sidecar describes. */
  static boolean matches(String recorded, String actual) {
    return recorded.toLowerCase(Locale.ROOT).equals(actual.toLowerCase(Locale.ROOT));
  }

  /** The operator-facing sentence for a mismatch, shared by the plan warning and the precheck. */
  static String mismatch(Path archive, String recorded, String actual) {
    return archive.getFileName()
        + " hashes to SHA-256 "
        + shortHash(actual)
        + ", but its sidecar recorded "
        + shortHash(recorded)
        + " when it was exported: the archive is truncated, damaged or not the one exported";
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Verify the archive against its sidecar";
  }

  @Override
  public String phase() {
    return DefaultExportImportOperations.PRECHECK_PHASE;
  }

  @Override
  public String detail() {
    return "SHA-256 " + shortHash(recorded);
  }

  @Override
  public boolean mutating() {
    return false;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    if (matches(recorded, actual)) {
      return CheckResult.pass();
    }
    return CheckResult.fail(
        mismatch(archive, recorded, actual),
        "copy the archive again from where it was exported, or take the export again; nothing"
            + " was changed on this server");
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return StepResult.ok();
  }

  private static String shortHash(String hash) {
    return hash.length() > 12 ? hash.substring(0, 12) + "…" : hash;
  }
}
