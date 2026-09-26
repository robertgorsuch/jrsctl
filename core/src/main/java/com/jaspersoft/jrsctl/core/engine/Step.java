package com.jaspersoft.jrsctl.core.engine;

import com.jaspersoft.jrsctl.core.event.EventSink;

/**
 * Smallest unit of work (spec §6.1). Invariants every implementation must keep:
 *
 * <ul>
 *   <li>{@link #execute} is idempotent: re-executing after a crash converges to the same end state.
 *   <li>Every mutating step has a working {@link #compensate}, or returns {@code true} from {@link
 *       #irreversible()} with a justification comment at the declaration site.
 *   <li>Failures are classified by the step itself via {@link StepFailure}; exceptions escaping
 *       {@code execute} are treated as {@link StepFailure.Recoverable} by the Runner.
 *   <li>{@link #phase()} groups consecutive steps; every phase boundary is a rollback point.
 * </ul>
 */
public interface Step {

  String id();

  String title();

  String phase();

  /** One muted line shown under the title in plans, e.g. "timeout 180s"; empty when none. */
  default String detail() {
    return "";
  }

  /**
   * True when a failure of this step must undo the whole run, not only its phase: a step that comes
   * after the mutation it records (the state row of an applied hotfix, say) has nothing of its own
   * to compensate, so a phase-scoped rollback would leave the earlier phases' work in place while
   * reporting "rolled back" (assessment item H4).
   */
  default boolean rollbackAllOnFailure() {
    return false;
  }

  /** Must be false unless justified in a comment where the step is declared. */
  default boolean irreversible() {
    return false;
  }

  /** True when the step changes the server or filesystem; read-only steps need no compensation. */
  default boolean mutating() {
    return true;
  }

  CheckResult precheck(Context ctx);

  StepResult execute(Context ctx, EventSink out);

  default CheckResult postcheck(Context ctx) {
    return CheckResult.pass();
  }

  /**
   * Undoes whatever {@link #execute} did, converging from any partial state: the Runner calls it
   * after a successful execute, after an execute that failed part-way, after a crash that left the
   * step {@code RUNNING} or {@code FAILED} in the journal, and again if a previous compensation
   * failed. No-op only when {@link #irreversible()} or {@code !mutating()}.
   */
  StepResult compensate(Context ctx, EventSink out);

  default RetryPolicy retryPolicy() {
    return RetryPolicy.NONE;
  }
}
