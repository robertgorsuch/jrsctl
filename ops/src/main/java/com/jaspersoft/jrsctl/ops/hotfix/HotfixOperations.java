package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Entry points of the hotfix subsystem (spec §8). Planning methods never mutate anything; they
 * verify, validate and return a {@link Plan} whose execution is the {@code Runner}'s job.
 * Invariant: a plan returned here has already passed signature verification (unless {@code
 * allowUnsigned}) and manifest validation, so {@code --plan} output is trustworthy.
 */
public interface HotfixOperations {

  /**
   * Why a bundle without a jrsctl signature may be applied (#159): not at all, because the operator
   * confirmed an official package's checksum against the support portal (ADR-0027), or because
   * {@code --allow-unsigned} was given. The plan, the verify step and the audit name which one.
   */
  enum UnsignedAcceptance {
    REFUSED,
    CHECKSUM_CONFIRMED,
    ALLOW_UNSIGNED
  }

  /** Options for {@code hotfix apply}. */
  record ApplyOptions(UnsignedAcceptance unsigned) {

    public ApplyOptions {
      java.util.Objects.requireNonNull(unsigned, "unsigned");
    }

    /** {@code true} is {@code --allow-unsigned}, {@code false} refuses an unsigned bundle. */
    public ApplyOptions(boolean allowUnsigned) {
      this(allowUnsigned ? UnsignedAcceptance.ALLOW_UNSIGNED : UnsignedAcceptance.REFUSED);
    }

    /** Whether a bundle without a signature may be applied at all. */
    public boolean allowUnsigned() {
      return unsigned != UnsignedAcceptance.REFUSED;
    }
  }

  /** Options for {@code hotfix rollback}. */
  record RollbackOptions(boolean cascade) {}

  /**
   * What verification found. {@code official} is true for an official Jaspersoft package
   * (ADR-0024), which carries no jrsctl signature to fail, so it is {@link #ok()} on hashes and
   * applicability alone and apply asks the operator to confirm {@code sha256}, the hex SHA-256 of
   * the file as given, against the support portal (ADR-0027).
   */
  record VerifyReport(
      boolean signatureValid,
      Optional<String> signedBy,
      boolean hashesValid,
      List<String> hashProblems,
      boolean applicable,
      List<String> applicabilityProblems,
      String manifestId,
      String title,
      boolean official,
      String sha256) {
    public boolean ok() {
      return (signatureValid || official) && hashesValid && applicable;
    }
  }

  /** Builds a signed bundle from a directory holding manifest.json, payload/, sql/, checks/. */
  Path build(Path bundleDir, SecretRef privateKeyRef, Path out);

  /** Result of {@code hotfix verify}: signature, hashes and applicability only. */
  VerifyReport verify(Path bundle);

  Plan planApply(Path bundle, ApplyOptions options);

  Plan planRollback(String hotfixId, RollbackOptions options);

  List<HotfixInstalled> list();

  /**
   * Records an official package the operator applied by hand (ADR-0030, issue #99): an {@code
   * INSTALLED} row with {@link HotfixInstalled.Origin#RECORDED}, the id and title the package's
   * readme gives it, no file ownership and no snapshot. Refused when the id is already in the
   * ledger or the file is not an official package. Writes the state store and an audit row only;
   * nothing on the server is touched.
   */
  HotfixInstalled record(Path officialPackage);
}
