package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.ops.Idempotency;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations.ApplyOptions;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class PreflightLockTest {

  @TempDir Path tmp;

  /**
   * Review finding 3.3: on a host where jrsctl cannot read other accounts' open handles, "no locked
   * file found" is a warning, not a pass, in both the preflight notes and the swap check.
   */
  @Test
  void should_warn_when_lock_detection_cannot_see_other_accounts() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.fake.platform.lockInspectionLimit =
          java.util.Optional.of("cannot inspect open handles of 42 of 90 processes as jrsctl");
      Plan plan = f.ops().planApply(f.buildWebInf(), new ApplyOptions(false));
      Context ctx = f.ctx("r-blind");
      HotfixFixture.step(plan, "verify-signature").precheck(ctx);
      stage(f, plan, ctx);
      f.fake.platform.serviceState = ServiceController.State.STOPPED;

      CheckResult preflight = HotfixFixture.step(plan, "preflight").precheck(ctx);
      CheckResult swap = HotfixFixture.step(plan, "atomic-swap").precheck(ctx);

      assertThat(preflight).isInstanceOf(CheckResult.Warn.class);
      assertThat(((CheckResult.Warn) preflight).message()).contains("lock detection limited");
      assertThat(swap).isInstanceOf(CheckResult.Warn.class);
      assertThat(((CheckResult.Warn) swap).message()).contains("cannot inspect open handles");
    }
  }

  @Test
  void should_fail_preflight_when_target_locked_and_service_stopped() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildWebInf(), new ApplyOptions(false));
      Context ctx = f.ctx("r-lock");
      assertThat(HotfixFixture.step(plan, "verify-signature").precheck(ctx))
          .isInstanceOf(CheckResult.Pass.class);
      stage(f, plan, ctx);
      f.fake.platform.serviceState = ServiceController.State.STOPPED;
      try (RandomAccessFile held =
          new RandomAccessFile(f.target(HotfixFixture.FOO).toFile(), "rw")) {
        CheckResult result = HotfixFixture.step(plan, "preflight").precheck(ctx);
        assertThat(result).isInstanceOf(CheckResult.Fail.class);
        assertThat(((CheckResult.Fail) result).message())
            .contains("foo-1.2.3.jar")
            .contains("locked");
        CheckResult swap = HotfixFixture.step(plan, "atomic-swap").precheck(ctx);
        assertThat(swap).isInstanceOf(CheckResult.Fail.class);
        assertThat(((CheckResult.Fail) swap).message()).contains("still locked");
      }
      assertThat(HotfixFixture.step(plan, "preflight").precheck(ctx))
          .isInstanceOf(CheckResult.Pass.class);
    }
  }

  @Test
  void should_report_lock_as_detail_when_service_running_and_restart_required() throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      Plan plan = f.ops().planApply(f.buildWebInf(), new ApplyOptions(false));
      Context ctx = f.ctx("r-lock2");
      HotfixFixture.step(plan, "verify-signature").precheck(ctx);
      try (RandomAccessFile held =
          new RandomAccessFile(f.target(HotfixFixture.FOO).toFile(), "rw")) {
        CheckResult result = HotfixFixture.step(plan, "preflight").precheck(ctx);
        assertThat(result).isInstanceOf(CheckResult.Warn.class);
        assertThat(((CheckResult.Warn) result).message())
            .contains("locked")
            .contains("service will be stopped");
      }
    }
  }

  /** The swap's precheck expects the payload staged first (issue #184). */
  private static void stage(HotfixFixture f, Plan plan, Context ctx) {
    f.store()
        .recordRunStart(ctx.runId(), plan.summary().operation(), Optional.empty(), Instant.EPOCH);
    Idempotency.runUpTo(plan, ctx, "stage-files");
  }
}
