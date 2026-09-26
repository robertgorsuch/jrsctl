package com.jaspersoft.jrsctl.ops.exim;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RetryPolicy;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.ops.Idempotency;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations.ImportOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 8 idempotency of the ops-level export/import steps (spec §6.1, §9.4): the read-only
 * snapshot announcement writes nothing however often it runs, a rephased step is its delegate in
 * every respect, and the snapshot-rollback anchor's compensation re-imports the snapshot once even
 * when it is run twice, because the nested restore run keeps its own task handle.
 */
class EximStepIdempotencyTest {

  @TempDir Path tmp;

  private static Map<String, String> files(Path root) throws IOException {
    return Idempotency.tree(
        "tmp",
        root,
        p ->
            p.getFileName().toString().contains("state.db") ? Optional.of("db") : Optional.empty());
  }

  @Test
  void should_not_mutate_when_pre_import_snapshot_executes_twice() throws IOException {
    try (EximFixture fx = new EximFixture(tmp, EximFakeAdapter::new)) {
      ExportRequest request =
          new ExportRequest(
              ExportRequest.Scope.REPOSITORY,
              Set.of("/public"),
              true,
              false,
              false,
              false,
              false,
              false,
              tmp.resolve("snap").resolve("pre.zip"));
      Step step = new PreImportSnapshot(request, ExportImportStrategy.Kind.REST);
      Context ctx = fx.context("r-pre");
      Map<String, String> before = files(tmp);

      assertThat(step.mutating()).isFalse();
      Idempotency.executeOk(step, ctx);
      Idempotency.executeOk(step, ctx);
      Idempotency.compensateOk(step, ctx);

      assertThat(files(tmp)).isEqualTo(before);
    }
  }

  @Test
  void should_delegate_unchanged_when_rephased_step_executes_twice() throws IOException {
    try (EximFixture fx = new EximFixture(tmp, EximFakeAdapter::new)) {
      CountingStep inner = new CountingStep();
      Step rephased = Rephased.into("backup", inner);
      Context ctx = fx.context("r-re");

      Idempotency.executeOk(rephased, ctx);
      Idempotency.executeOk(rephased, ctx);
      Idempotency.compensateOk(rephased, ctx);

      assertThat(rephased.id()).isEqualTo("backup.inner");
      assertThat(rephased.phase()).isEqualTo("backup");
      assertThat(rephased.title()).isEqualTo("inner title");
      assertThat(rephased.detail()).isEqualTo("inner detail");
      assertThat(rephased.mutating()).isTrue();
      assertThat(rephased.irreversible()).isFalse();
      assertThat(rephased.retryPolicy()).isEqualTo(RetryPolicy.HTTP_DEFAULT);
      assertThat(inner.executions).isEqualTo(2);
      assertThat(inner.compensations).isEqualTo(1);
    }
  }

  /** Issue #185: the checksum step decides in its precheck and changes nothing, however often. */
  @Test
  void should_not_mutate_when_archive_checksum_executes_twice() throws IOException {
    EximFakeAdapter adapter = new EximFakeAdapter();
    try (EximFixture fx = new EximFixture(tmp, () -> adapter)) {
      Step step = new VerifyArchiveChecksum(tmp.resolve("public.zip"), "ab12", "AB12");
      Context ctx = fx.context(EximFixture.RUN);

      Idempotency.executeOk(step, ctx);
      Idempotency.executeOk(step, ctx);
      Idempotency.compensateOk(step, ctx);
      Idempotency.compensateOk(step, ctx);

      assertThat(step.mutating()).isFalse();
      assertThat(step.precheck(ctx).failed()).as("hex compares without case").isFalse();
      assertThat(adapter.exports).isEmpty();
      assertThat(adapter.imports).isEmpty();
      assertThat(adapter.deleted).isEmpty();
    }
  }

  /** Issue #100: the listing step reads the server and writes one file; twice is the same file. */
  @Test
  void should_not_mutate_when_pre_import_listing_executes_twice() throws IOException {
    EximFakeAdapter adapter = new EximFakeAdapter();
    adapter.trees.add(List.of("/public/b", "/public/a"));
    try (EximFixture fx = new EximFixture(tmp, () -> adapter)) {
      Step step = new RecordRepositoryListing(List.of("/public"));
      Context ctx = fx.context(EximFixture.RUN);

      Idempotency.executeOk(step, ctx);
      Path listing = RecordRepositoryListing.listingFile(ctx);
      String once = Files.readString(listing);
      Idempotency.executeOk(step, ctx);

      assertThat(step.mutating()).isFalse();
      assertThat(Files.readString(listing)).isEqualTo(once);
      assertThat(Files.readAllLines(listing)).containsExactly("/public/a", "/public/b");
      assertThat(adapter.deleted).isEmpty();
      assertThat(adapter.imports).isEmpty();
    }
  }

  /**
   * Issue #139: the step records what is new, on the server nothing changes; twice is one record.
   */
  @Test
  void should_record_the_same_new_folders_when_remove_new_content_executes_twice()
      throws IOException {
    EximFakeAdapter adapter = new EximFakeAdapter();
    adapter.existing = Optional.of(Set.of("/public"));
    try (EximFixture fx = new EximFixture(tmp, () -> adapter)) {
      Step step = new RemoveNewContent("import", List.of("/public/new/inner"));
      Context ctx = fx.context(EximFixture.RUN);

      Idempotency.executeOk(step, ctx);
      Path record = RemoveNewContent.recordFile(ctx);
      String once = Files.readString(record);
      Idempotency.executeOk(step, ctx);

      assertThat(Files.readString(record)).isEqualTo(once);
      assertThat(Files.readAllLines(record)).containsExactly("/public/new");
      assertThat(adapter.deleted).isEmpty();
      assertThat(adapter.imports).isEmpty();
    }
  }

  @Test
  void should_delete_the_new_folders_once_when_remove_new_content_compensates_twice()
      throws IOException {
    EximFakeAdapter adapter = new EximFakeAdapter();
    adapter.existing = Optional.of(Set.of("/public"));
    try (EximFixture fx = new EximFixture(tmp, () -> adapter)) {
      Step step = new RemoveNewContent("import", List.of("/public/new"));
      Context ctx = fx.context(EximFixture.RUN);
      Idempotency.executeOk(step, ctx);
      adapter.existing = Optional.of(Set.of("/public", "/public/new"));

      Idempotency.compensateOk(step, ctx);
      Idempotency.compensateOk(step, ctx);

      assertThat(adapter.deleted)
          .as("the second compensation finds it gone")
          .containsExactly("/public/new");
    }
  }

  /** ADR-0040: the --no-snapshot anchor writes nothing however often it executes. */
  @Test
  void should_not_mutate_when_remove_import_additions_executes_twice() throws IOException {
    EximFakeAdapter adapter = new EximFakeAdapter();
    try (EximFixture fx = new EximFixture(tmp, () -> adapter)) {
      Step step = new RemoveImportAdditions("import", List.of("/public"));
      Context ctx = fx.context(EximFixture.RUN);
      Map<String, String> before = files(tmp);

      Idempotency.executeOk(step, ctx);
      Idempotency.executeOk(step, ctx);

      assertThat(step.mutating()).as("the Runner compensates mutating steps only").isTrue();
      assertThat(files(tmp)).isEqualTo(before);
      assertThat(adapter.deleted).isEmpty();
      assertThat(adapter.imports).isEmpty();
    }
  }

  @Test
  void should_delete_the_additions_once_when_remove_import_additions_compensates_twice()
      throws IOException {
    EximFakeAdapter adapter = new EximFakeAdapter();
    adapter.trees.add(List.of("/public/kept"));
    adapter.trees.add(List.of("/public/kept", "/public/new", "/public/new/r"));
    try (EximFixture fx = new EximFixture(tmp, () -> adapter)) {
      Context ctx = fx.context(EximFixture.RUN);
      Idempotency.executeOk(new RecordRepositoryListing(List.of("/public")), ctx);
      Step step = new RemoveImportAdditions("import", List.of("/public"));
      Idempotency.executeOk(step, ctx);

      Idempotency.compensateOk(step, ctx);
      Idempotency.compensateOk(step, ctx);

      assertThat(adapter.deleted)
          .as("deepest first, and the second compensation finds nothing new")
          .containsExactly("/public/new/r", "/public/new");
      assertThat(adapter.imports).as("no snapshot to re-import").isEmpty();
    }
  }

  @Test
  void should_reimport_the_snapshot_once_when_restore_from_pre_import_snapshot_compensates_twice()
      throws IOException {
    EximFakeAdapter adapter = new EximFakeAdapter();
    try (EximFixture fx = new EximFixture(tmp, () -> adapter)) {
      Path archive = tmp.resolve("in").resolve("public.zip");
      Files.createDirectories(archive.getParent());
      Files.write(archive, new byte[] {'P', 'K', 3, 4, 5, 6});
      Sidecar.write(
          Sidecar.pathFor(archive),
          new Sidecar(
              Instant.parse("2026-09-01T00:00:00Z"),
              "srv",
              "8.2.0",
              Optional.of(EximFakeAdapter.FINGERPRINT),
              new Sidecar.Flags(
                  ExportRequest.Scope.REPOSITORY,
                  List.of("/public"),
                  true,
                  false,
                  false,
                  false,
                  false,
                  false),
              EximFixture.sha256(archive),
              ExportImportStrategy.Kind.REST));
      Plan plan =
          fx.ops()
              .planImport(
                  new ImportOptions(
                      archive,
                      false,
                      false,
                      false,
                      false,
                      false,
                      false,
                      false,
                      Optional.empty(),
                      Optional.empty(),
                      Optional.empty()));
      assertThat(fx.run(plan, EximFixture.RUN)).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(adapter.imports).hasSize(1);
      Step anchor = Idempotency.step(plan, RestoreFromPreImportSnapshot.ID);
      Context ctx = fx.context(EximFixture.RUN);
      Path snapshot = plan.summary().backupLocations().get(0);

      Idempotency.compensateOk(anchor, ctx);
      Idempotency.compensateOk(anchor, ctx);

      assertThat(adapter.imports)
          .as("one restore import, reused by the second compensation")
          .hasSize(2);
      assertThat(adapter.imports.get(1).archive()).isEqualTo(snapshot);
      assertThat(adapter.imports.get(1).request().update()).isTrue();
      assertThat(
              fx.services.home().runDir(EximFixture.RUN + "-restore").resolve("import-handle.txt"))
          .hasContent("imp-2");
    }
  }

  /** Counts executions and compensations. */
  private static final class CountingStep implements Step {
    int executions;
    int compensations;

    @Override
    public String id() {
      return "inner";
    }

    @Override
    public String title() {
      return "inner title";
    }

    @Override
    public String phase() {
      return "export";
    }

    @Override
    public String detail() {
      return "inner detail";
    }

    @Override
    public RetryPolicy retryPolicy() {
      return RetryPolicy.HTTP_DEFAULT;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      executions++;
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      compensations++;
      return StepResult.ok();
    }
  }
}
