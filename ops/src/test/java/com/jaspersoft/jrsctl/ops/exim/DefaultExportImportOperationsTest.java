package com.jaspersoft.jrsctl.ops.exim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.jrs.api.Capability;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations.ExportOptions;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations.ImportOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultExportImportOperationsTest {

  @TempDir Path tmp;

  private EximFakeAdapter adapter;
  private EximFixture fx;
  private Path archive;

  @BeforeEach
  void setUp() throws IOException {
    adapter = new EximFakeAdapter();
    fx = new EximFixture(tmp, () -> adapter);
    archive = tmp.resolve("in").resolve("public.zip");
    Files.createDirectories(archive.getParent());
    Files.write(archive, new byte[] {'P', 'K', 3, 4, 1, 2, 3});
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private static ExportOptions export(Set<String> uris, boolean fullServer, Path out) {
    return export(uris, fullServer, out, Optional.empty());
  }

  private static ExportOptions export(
      Set<String> uris, boolean fullServer, Path out, Optional<ExportImportStrategy.Kind> kind) {
    return new ExportOptions(uris, true, false, false, false, false, fullServer, out, kind);
  }

  private static ImportOptions importOf(Path archive, boolean update) {
    return importOf(archive, update, Optional.empty());
  }

  private static ImportOptions importOf(
      Path archive, boolean update, Optional<ExportImportStrategy.Kind> kind) {
    return new ImportOptions(
        archive,
        update,
        false,
        false,
        false,
        false,
        false,
        false,
        Optional.empty(),
        Optional.empty(),
        kind);
  }

  private void sidecar(List<String> uris, boolean fullServer) throws IOException {
    sidecar(uris, fullServer, "8.2.0");
  }

  private void sidecar(List<String> uris, boolean fullServer, String sourceVersion)
      throws IOException {
    sidecar(uris, fullServer, sourceVersion, EximFixture.sha256(archive));
  }

  private void sidecar(List<String> uris, boolean fullServer, String sourceVersion, String sha256)
      throws IOException {
    Sidecar.write(
        Sidecar.pathFor(archive),
        new Sidecar(
            Instant.parse("2026-09-01T00:00:00Z"),
            "srv",
            sourceVersion,
            Optional.of(EximFakeAdapter.FINGERPRINT),
            new Sidecar.Flags(
                fullServer ? ExportRequest.Scope.EVERYTHING : ExportRequest.Scope.REPOSITORY,
                uris,
                true,
                false,
                false,
                false,
                false,
                fullServer),
            sha256,
            ExportImportStrategy.Kind.REST));
  }

  private static List<String> ids(Plan plan) {
    return plan.steps().stream().map(Step::id).toList();
  }

  private static List<String> phases(Plan plan) {
    return plan.byPhase().keySet().stream().toList();
  }

  // ---- export ---------------------------------------------------------------------------------

  @Test
  void should_choose_rest_and_compose_export_steps_when_export_probe_passes() {
    Path out = tmp.resolve("out").resolve("x.zip");

    Plan plan = fx.ops().planExport(export(Set.of("/public", "/adhoc"), false, out));

    assertThat(ids(plan))
        .containsExactly("export.start", "export.poll", "export.download", "export.sidecar");
    assertThat(phases(plan)).containsExactly("export");
    assertThat(plan.summary().operation()).isEqualTo("export");
    assertThat(plan.summary().target()).isEqualTo("/adhoc, /public");
    assertThat(plan.summary().strategy())
        .startsWith("rest (")
        .contains("EXPORT_ASYNC probe passed");
    assertThat(plan.summary().serviceRestart()).isFalse();
    assertThat(plan.summary().filesTouched())
        .containsExactly(out.toAbsolutePath().normalize(), Sidecar.pathFor(out.toAbsolutePath()));
    assertThat(plan.summary().warnings()).isEmpty();
    assertThat(plan.fingerprint().inputs()).containsKeys("server", "out", "config", "request");
    assertThat(adapter.exports).as("planning must not export").isEmpty();
  }

  @Test
  void should_choose_vendor_and_warn_about_service_stop_when_full_server_requested() {
    Plan plan = fx.ops().planExport(export(Set.of(), true, tmp.resolve("full.zip")));

    assertThat(ids(plan))
        .containsExactly(
            "export.locate-vendor-tools",
            "export.stop-service",
            "export.js-export",
            "export.start-service",
            "export.wait-for-server",
            "export.sidecar");
    assertThat(plan.summary().target()).isEqualTo("full server");
    assertThat(plan.summary().strategy()).startsWith("vendor (").contains("full-server");
    assertThat(plan.summary().serviceRestart()).isTrue();
    assertThat(plan.summary().warnings()).anyMatch(w -> w.contains("service will be stopped"));
  }

  /**
   * Issue #116: the plan says what {@code --everything} already carries, and that events are not in
   * it.
   */
  @Test
  void should_say_what_a_full_server_export_covers_and_that_users_roles_is_redundant() {
    Plan plan = fx.ops().planExport(export(Set.of(), true, tmp.resolve("full.zip")));

    assertThat(plan.summary().warnings())
        .anyMatch(
            w ->
                w.contains("already carries")
                    && w.contains("users, roles")
                    && w.contains("events are left out")
                    && w.contains("--audit-events"))
        .anyMatch(w -> w.contains("--users-roles is redundant with --full-server"));
  }

  @Test
  void should_not_call_users_roles_redundant_when_it_was_not_given_with_full_server() {
    Plan plan =
        fx.ops()
            .planExport(
                new ExportOptions(
                    Set.of(),
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    tmp.resolve("full.zip"),
                    Optional.empty(),
                    false));

    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("already carries"))
        .noneMatch(w -> w.contains("redundant"));
  }

  @Test
  void should_add_no_full_server_note_when_exporting_uris() {
    Plan plan = fx.ops().planExport(export(Set.of("/public"), false, tmp.resolve("p.zip")));

    assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("already carries"));
  }

  /** Issue #67: a full-server export runs js-export against the running server by default. */
  @Test
  void should_keep_the_service_running_and_say_so_when_a_full_server_export_does_not_ask_to_stop() {
    Plan plan =
        fx.ops()
            .planExport(
                new ExportOptions(
                    Set.of(),
                    true,
                    false,
                    false,
                    false,
                    false,
                    true,
                    tmp.resolve("full.zip"),
                    Optional.empty(),
                    false));

    assertThat(ids(plan))
        .containsExactly("export.locate-vendor-tools", "export.js-export", "export.sidecar");
    assertThat(plan.summary().serviceRestart()).isFalse();
    assertThat(plan.summary().warnings())
        .noneMatch(w -> w.contains("service will be stopped"))
        .anyMatch(w -> w.contains("keeps running") && w.contains("--stop-service"));
  }

  @Test
  void should_choose_vendor_when_strategy_forced() {
    Plan plan =
        fx.ops()
            .planExport(
                export(
                    Set.of("/public"),
                    false,
                    tmp.resolve("v.zip"),
                    Optional.of(ExportImportStrategy.Kind.VENDOR_CLI)));

    assertThat(plan.summary().strategy())
        .isEqualTo("vendor (vendor CLI forced by --strategy vendor)");
    assertThat(plan.summary().serviceRestart()).isTrue();
    assertThat(plan.fingerprint().inputs()).containsEntry("strategy", "VENDOR_CLI");
  }

  @Test
  void should_choose_vendor_when_export_probe_fails() {
    adapter.capabilities = EnumSet.of(Capability.IMPORT_ASYNC);

    Plan plan = fx.ops().planExport(export(Set.of("/public"), false, tmp.resolve("p.zip")));

    assertThat(plan.summary().strategy()).startsWith("vendor (").contains("probe failed");
    assertThat(ids(plan)).contains("export.js-export");
  }

  @Test
  void should_default_to_repository_root_when_no_uri_given() {
    Plan plan = fx.ops().planExport(export(Set.of(), false, tmp.resolve("root.zip")));

    assertThat(plan.summary().target()).isEqualTo("/");
    assertThat(plan.summary().resourcesTouched()).containsExactly("/");
  }

  // ---- import ---------------------------------------------------------------------------------

  /** Field test 2, I1: the sidecar remembers the alias, so the import needs no flag. */
  @Test
  void should_adopt_the_key_alias_recorded_in_the_sidecar_when_the_import_names_none()
      throws IOException {
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
                false,
                Optional.of(ExportRequest.PORTABLE_KEY_ALIAS)),
            EximFixture.sha256(archive),
            ExportImportStrategy.Kind.REST));

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(plan.fingerprint().inputs().get("request"))
        .contains(";keyAlias=" + ExportRequest.PORTABLE_KEY_ALIAS);
    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("exported with key alias " + ExportRequest.PORTABLE_KEY_ALIAS));
  }

  /**
   * Field test 2, I4: an organisation import snapshots that organisation's folder, never the root.
   */
  @Test
  void should_scope_the_pre_import_snapshot_to_the_organisation_folder() {
    adapter.existing = Optional.of(Set.of("/organizations/org1"));
    ImportOptions base = importOf(archive, true);
    ImportOptions options =
        new ImportOptions(
            base.archive(),
            base.update(),
            base.skipUserUpdate(),
            base.accessEvents(),
            base.auditEvents(),
            base.monitoring(),
            base.settings(),
            base.skipThemes(),
            base.sourceKeystore(),
            base.sourceKeystorePassword(),
            base.strategy(),
            base.brokenDependencies(),
            Optional.empty(),
            Optional.of("org1"),
            true);

    Plan plan = fx.ops().planImport(options);

    assertThat(plan.summary().resourcesTouched()).containsExactly("/organizations/org1");
    assertThat(plan.steps().get(1).detail()).startsWith("/organizations/org1 -> ");
    assertThat(plan.fingerprint().inputs().get("request")).contains(";organization=org1;merge");
    assertThat(adapter.imports).as("planning must not import").isEmpty();
  }

  /** Issue #185: the sidecar's SHA-256 is compared first, before the snapshot and any stop. */
  @Test
  void should_verify_the_archive_checksum_first_when_the_archive_has_a_sidecar()
      throws IOException {
    sidecar(List.of("/public"), false);

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(ids(plan).get(0)).isEqualTo("precheck.archive-checksum");
    assertThat(plan.steps().get(0).phase()).isEqualTo("precheck");
    assertThat(plan.steps().get(0).mutating()).isFalse();
    assertThat(plan.steps().get(0).precheck(fx.context(EximFixture.RUN)).failed()).isFalse();
    assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("SHA-256"));
  }

  @Test
  void should_refuse_at_the_checksum_step_when_the_archive_differs_from_its_sidecar()
      throws IOException {
    sidecar(List.of("/public"), false, "8.2.0", "0".repeat(64));

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("SHA-256") && w.contains("precheck.archive-checksum"));
    CheckResult pre = plan.steps().get(0).precheck(fx.context(EximFixture.RUN));
    assertThat(pre.failed()).isTrue();
    assertThat(((CheckResult.Fail) pre).message())
        .contains("public.zip")
        .contains("0".repeat(12))
        .contains(EximFixture.sha256(archive).substring(0, 12));
    assertThat(((CheckResult.Fail) pre).remediation()).contains("export");
  }

  @Test
  void should_stop_before_the_snapshot_when_the_archive_differs_from_its_sidecar()
      throws IOException {
    sidecar(List.of("/public"), false, "8.2.0", "0".repeat(64));

    RunOutcome outcome = fx.run(fx.ops().planImport(importOf(archive, false)), EximFixture.RUN);

    assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
    assertThat(((RunOutcome.PrecheckFailed) outcome).stepId())
        .isEqualTo("precheck.archive-checksum");
    assertThat(adapter.exports).as("no pre-import snapshot").isEmpty();
    assertThat(adapter.imports).as("nothing imported").isEmpty();
  }

  @Test
  void should_plan_no_checksum_step_when_the_archive_has_no_sidecar() {
    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(ids(plan)).doesNotContain("precheck.archive-checksum");
  }

  @Test
  void should_order_precheck_backup_then_import_when_planning_a_rest_import() throws IOException {
    sidecar(List.of("/public"), false);

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(phases(plan)).containsExactly("precheck", "backup", "import");
    assertThat(ids(plan))
        .containsExactly(
            "precheck.archive-checksum",
            "precheck.import.check-keystore",
            "backup.pre-import-snapshot",
            "backup.pre-import-listing",
            "backup.export.start",
            "backup.export.poll",
            "backup.export.download",
            "backup.export.sidecar",
            "import.snapshot-rollback",
            "import.new-content-rollback",
            "import.start",
            "import.poll",
            "import.verify");
    Path snapshot = plan.summary().backupLocations().get(0);
    assertThat(snapshot.toString())
        .startsWith(fx.services.home().snapshots().resolve("pre-import").toString())
        .endsWith(".zip");
    assertThat(plan.summary().operation()).isEqualTo("import");
    assertThat(plan.summary().target()).isEqualTo("public.zip");
    assertThat(plan.summary().resourcesTouched()).containsExactly("/public");
    assertThat(plan.summary().warnings())
        .contains(DefaultExportImportOperations.ROLLBACK_WARNING)
        .noneMatch(w -> w.contains("no sidecar"));
    assertThat(plan.summary().strategy()).startsWith("rest (").contains("IMPORT_ASYNC");
    assertThat(plan.summary().serviceRestart()).isFalse();
    assertThat(plan.steps().get(2).detail()).startsWith("/public -> ");
    assertThat(plan.steps().get(3).detail()).startsWith("every URI under /public -> ");
    assertThat(plan.steps().get(4).detail()).isEqualTo("uris /public");
    assertThat(plan.steps().get(8).mutating()).isTrue();
    assertThat(adapter.imports).as("planning must not import").isEmpty();
  }

  @Test
  void should_snapshot_full_server_when_update_targets_root_without_sidecar() {
    Plan plan = fx.ops().planImport(importOf(archive, true));

    assertThat(plan.summary().warnings())
        .contains(DefaultExportImportOperations.ROLLBACK_WARNING)
        .anyMatch(w -> w.contains("no sidecar"))
        .anyMatch(w -> w.contains("--update"));
    assertThat(plan.summary().resourcesTouched()).containsExactly("/");
    assertThat(plan.steps().get(1).detail()).startsWith("full server -> ");
    // issue #100: the listing of the root follows the snapshot announcement
    assertThat(plan.steps().get(2).detail()).startsWith("every URI under / -> ");
    assertThat(plan.steps().get(3).detail()).isEqualTo("everything");
  }

  @Test
  void should_hoist_vendor_prechecks_and_keep_service_steps_when_strategy_forced_to_vendor()
      throws IOException {
    sidecar(List.of("/public"), false);
    ImportOptions options =
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
            Optional.of(ExportImportStrategy.Kind.VENDOR_CLI));

    Plan plan = fx.ops().planImport(options);

    assertThat(phases(plan)).containsExactly("precheck", "backup", "import");
    // ADR-0040: js-export reads the repository with the server up; the import is the one outage
    assertThat(ids(plan))
        .containsExactly(
            "precheck.archive-checksum",
            "precheck.import.check-keystore",
            "precheck.import.locate-vendor-tools",
            "backup.pre-import-snapshot",
            "backup.pre-import-listing",
            "backup.export.locate-vendor-tools",
            "backup.export.js-export",
            "backup.export.sidecar",
            "import.snapshot-rollback",
            "import.new-content-rollback",
            "import.stop-service",
            "import.js-import",
            "import.start-service",
            "import.wait-for-server");
    assertThat(ids(plan)).filteredOn(id -> id.endsWith("stop-service")).hasSize(1);
    assertThat(plan.summary().serviceRestart()).isTrue();
    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("service will be stopped for the vendor import"))
        .anyMatch(w -> w.contains("snapshot") && w.contains("server running"))
        .noneMatch(w -> w.contains("vendor snapshot and import"));
  }

  /**
   * ADR-0040: arguments an earlier jrsctl stored rebuild the plan it journaled, whose vendor
   * snapshot stopped and started the service, so {@code runs recover} still matches the run.
   */
  @Test
  void should_stop_the_service_around_the_snapshot_when_stored_arguments_predate_adr_0040()
      throws IOException {
    sidecar(List.of("/public"), false);
    ImportOptions old =
        com.jaspersoft.jrsctl.ops.PlanRegistry.importOptions(
            com.jaspersoft.jrsctl.core.json.Json.mapper()
                .createObjectNode()
                .put("archive", archive.toString())
                .put("strategy", "vendor"));

    Plan plan = fx.ops().planImport(old);

    assertThat(ids(plan))
        .containsSubsequence(
            "backup.export.locate-vendor-tools",
            "backup.export.stop-service",
            "backup.export.js-export",
            "backup.export.start-service",
            "backup.export.wait-for-server",
            "backup.export.sidecar",
            "import.stop-service");
    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("stopped for the vendor snapshot and import"));
  }

  /**
   * ADR-0040: forced to the vendor tools on a server that takes REST imports, say what it costs.
   */
  @Test
  void should_say_rest_needs_no_outage_when_vendor_is_forced_on_a_rest_capable_server()
      throws IOException {
    sidecar(List.of("/public"), false);
    ImportOptions forced =
        importOf(archive, false, Optional.of(ExportImportStrategy.Kind.VENDOR_CLI));

    Plan plan = fx.ops().planImport(forced);

    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("REST") && w.contains("no outage") && w.contains("--strategy"));
  }

  @Test
  void should_not_offer_rest_when_the_server_cannot_import_over_rest() throws IOException {
    sidecar(List.of("/public"), false);
    adapter.capabilities = EnumSet.of(Capability.EXPORT_ASYNC);

    Plan auto = fx.ops().planImport(importOf(archive, false));
    Plan forced =
        fx.ops()
            .planImport(
                importOf(archive, false, Optional.of(ExportImportStrategy.Kind.VENDOR_CLI)));

    assertThat(auto.summary().strategy()).startsWith("vendor (").contains("probe failed");
    assertThat(auto.summary().warnings()).noneMatch(w -> w.contains("no outage"));
    assertThat(forced.summary().warnings()).noneMatch(w -> w.contains("no outage"));
  }

  @Test
  void should_not_offer_rest_when_the_archive_is_above_the_rest_limit() throws IOException {
    sidecar(List.of("/public"), false);

    Plan big =
        new DefaultExportImportOperations(fx.services, fx.strategies, 4)
            .planImport(importOf(archive, false));
    Plan forcedBig =
        new DefaultExportImportOperations(fx.services, fx.strategies, 4)
            .planImport(
                importOf(archive, false, Optional.of(ExportImportStrategy.Kind.VENDOR_CLI)));

    assertThat(big.summary().strategy()).startsWith("vendor");
    assertThat(big.summary().warnings()).noneMatch(w -> w.contains("no outage"));
    assertThat(forcedBig.summary().warnings()).noneMatch(w -> w.contains("no outage"));
  }

  @Test
  void should_not_offer_rest_when_a_source_keystore_needs_the_vendor_tools() throws IOException {
    sidecar(List.of("/public"), false);
    ImportOptions withKeystore =
        new ImportOptions(
            archive,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.of(tmp.resolve("source.jrsks")),
            Optional.empty(),
            Optional.of(ExportImportStrategy.Kind.VENDOR_CLI));

    Plan plan = fx.ops().planImport(withKeystore);

    assertThat(plan.summary().strategy()).contains("source keystore");
    assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("no outage"));
  }

  @Test
  void should_not_offer_rest_when_rest_is_the_strategy() throws IOException {
    sidecar(List.of("/public"), false);

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(plan.summary().strategy()).startsWith("rest (");
    assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("no outage"));
  }

  /**
   * ADR-0040: {@code --no-snapshot} leaves out the snapshot export and its re-import; the listing
   * and the new-content step stay, so what the failed import created is still deleted.
   */
  @Test
  void should_plan_no_snapshot_steps_and_say_what_is_lost_when_no_snapshot_is_given()
      throws IOException {
    sidecar(List.of("/public"), false);
    ImportOptions options =
        importOf(archive, false, Optional.of(ExportImportStrategy.Kind.VENDOR_CLI))
            .withNoSnapshot(true);

    Plan plan = fx.ops().planImport(options);

    assertThat(ids(plan))
        .containsExactly(
            "precheck.archive-checksum",
            "precheck.import.check-keystore",
            "precheck.import.locate-vendor-tools",
            "backup.pre-import-listing",
            "import.additions-rollback",
            "import.new-content-rollback",
            "import.stop-service",
            "import.js-import",
            "import.start-service",
            "import.wait-for-server");
    assertThat(plan.summary().backupLocations()).isEmpty();
    assertThat(plan.summary().warnings())
        .doesNotContain(DefaultExportImportOperations.ROLLBACK_WARNING)
        .contains(DefaultExportImportOperations.NO_SNAPSHOT_WARNING);
    assertThat(DefaultExportImportOperations.NO_SNAPSHOT_WARNING)
        .contains("--no-snapshot")
        .contains("cannot put back what it overwrote");
    assertThat(plan.summary().rollbackPointsByPhase().get("import"))
        .contains("delete what the import created")
        .contains("not put back");
    assertThat(plan.summary().rollbackPointsByPhase()).doesNotContainKey("backup");
    assertThat(fx.services.stateStore().get().auditRows(10))
        .anyMatch(
            a ->
                a.action().equals("--no-snapshot") && a.detail().orElse("").contains("public.zip"));
    assertThat(plan.fingerprint().inputs()).containsEntry("snapshot", "none");
  }

  /** ADR-0040: the fingerprint names a live snapshot, and a stopping one keeps its old inputs. */
  @Test
  void should_name_the_snapshot_choice_in_the_fingerprint_only_when_it_differs_from_before()
      throws IOException {
    sidecar(List.of("/public"), false);

    Plan live = fx.ops().planImport(importOf(archive, false));
    Plan stopping = fx.ops().planImport(importOf(archive, false).withSnapshotStopsService(true));

    assertThat(live.fingerprint().inputs()).containsEntry("snapshot", "live");
    assertThat(stopping.fingerprint().inputs()).doesNotContainKey("snapshot");
  }

  @Test
  void should_change_fingerprint_when_archive_content_changes() throws IOException {
    Plan before = fx.ops().planImport(importOf(archive, false));
    Files.write(archive, new byte[] {'P', 'K', 9, 9, 9});
    Plan after = fx.ops().planImport(importOf(archive, false));

    assertThat(before.fingerprint().matches(after.fingerprint())).isFalse();
    assertThat(before.fingerprint().changedKeys(after.fingerprint())).contains("archiveSha256");
    assertThat(before.summary().backupLocations())
        .as("snapshot name follows the archive hash")
        .isNotEqualTo(after.summary().backupLocations());
  }

  /**
   * Issue #107, release notes 10.1 p.6: "Resources exported from version 10.1.0 cannot be imported
   * into older versions". The fake server is 8.2.0.
   */
  @Test
  void should_refuse_planning_when_the_archive_comes_from_10_1_and_this_server_is_older()
      throws IOException {
    sidecar(List.of("/public"), false, "10.1.0");

    assertThatThrownBy(() -> fx.ops().planImport(importOf(archive, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("10.1.0")
        .hasMessageContaining("8.2.0")
        .hasMessageContaining("cannot be imported into older versions")
        .hasMessageContaining("--force-version");
    assertThat(adapter.imports).isEmpty();
  }

  @Test
  void should_plan_with_a_warning_and_an_audit_row_when_force_version_is_given()
      throws IOException {
    sidecar(List.of("/public"), false, "10.1.0");
    ImportOptions options = importOf(archive, false).withForceVersion(true);

    Plan plan = fx.ops().planImport(options);

    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("--force-version") && w.contains("10.1.0"));
    assertThat(fx.services.stateStore().get().auditRows(10))
        .anyMatch(
            a -> a.action().equals("--force-version") && a.detail().orElse("").contains("10.1.0"));
  }

  @Test
  void should_not_refuse_an_archive_from_a_version_at_most_this_servers() throws IOException {
    sidecar(List.of("/public"), false, "8.2.0");

    assertThat(ids(fx.ops().planImport(importOf(archive, false)))).isNotEmpty();
  }

  // ---- issue #115: size and themes ------------------------------------------------------------

  /** An import above the REST limit leaves REST for the vendor tools when this machine has them. */
  @Test
  void should_choose_the_vendor_tools_when_the_archive_is_above_the_rest_limit()
      throws IOException {
    sidecar(List.of("/public"), false);

    Plan plan =
        new DefaultExportImportOperations(fx.services, fx.strategies, 4)
            .planImport(importOf(archive, false));

    assertThat(plan.summary().strategy()).startsWith("vendor").contains("2 GB");
    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("public.zip") && w.contains("vendor import tools are used"));
  }

  @Test
  void should_keep_rest_when_the_archive_is_within_the_limit() throws IOException {
    sidecar(List.of("/public"), false);

    Plan plan =
        new DefaultExportImportOperations(fx.services, fx.strategies, 7)
            .planImport(importOf(archive, false));

    assertThat(plan.summary().strategy()).startsWith("rest (");
    assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("2 GB"));
  }

  @Test
  void should_only_warn_when_rest_is_forced_above_the_limit() throws IOException {
    sidecar(List.of("/public"), false);
    ImportOptions forced =
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
            Optional.of(ExportImportStrategy.Kind.REST));

    Plan plan = new DefaultExportImportOperations(fx.services, fx.strategies, 4).planImport(forced);

    assertThat(plan.summary().strategy()).startsWith("rest (");
    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("--strategy rest was given") && w.contains("attempted anyway"));
  }

  @Test
  void should_refuse_a_large_archive_when_no_vendor_tools_are_available_here() throws IOException {
    Path remote = tmp.resolve("remote");
    Files.createDirectories(remote);
    try (EximFixture noTools =
        new EximFixture(
            remote,
            () -> adapter,
            """
            server:
              baseUrl: http://localhost:8080/jasperserver-pro
              auth:
                username: jasperadmin
                passwordRef: env:JRS_PASSWORD
            network:
              mode: public
            """)) {
      sidecar(List.of("/public"), false);

      assertThatThrownBy(
              () ->
                  new DefaultExportImportOperations(noTools.services, noTools.strategies, 4)
                      .planImport(importOf(archive, false)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("public.zip")
          .hasMessageContaining("--strategy rest");
    }
  }

  @Test
  void should_skip_themes_and_say_so_when_the_archive_is_from_another_major_version()
      throws IOException {
    sidecar(List.of("/public"), false, "9.0.0");

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(plan.summary().warnings())
        .anyMatch(
            w ->
                w.contains("themes are not imported")
                    && w.contains("9.0.0")
                    && w.contains("--themes"));
    fx.run(plan, EximFixture.RUN);
    assertThat(adapter.imports).isNotEmpty();
    assertThat(adapter.imports.get(adapter.imports.size() - 1).request().skipThemes()).isTrue();
  }

  @Test
  void should_import_themes_across_a_major_version_when_asked_to() throws IOException {
    sidecar(List.of("/public"), false, "9.0.0");

    Plan plan = fx.ops().planImport(importOf(archive, false).withKeepThemes(true));

    assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("themes are not imported"));
    fx.run(plan, EximFixture.RUN);
    assertThat(adapter.imports.get(adapter.imports.size() - 1).request().skipThemes()).isFalse();
  }

  @Test
  void should_not_change_the_themes_choice_within_a_major_version() throws IOException {
    sidecar(List.of("/public"), false, "8.1.0");

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("themes are not imported"));
  }

  @Test
  void should_not_judge_the_themes_when_there_is_no_sidecar() {
    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("themes are not imported"));
  }

  // ---- issue #139: a failed import of new content ---------------------------------------------

  /**
   * No folder of the archive exists on the server: there is no snapshot to re-import, and the
   * rollback deletes what the import creates, so the plan says that instead of "nothing to put
   * back".
   */
  @Test
  void should_plan_to_delete_what_the_import_creates_when_no_folder_exists_yet()
      throws IOException {
    sidecar(List.of("/public/batch"), false);
    adapter.existing = Optional.of(Set.of("/public"));

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(ids(plan))
        .contains("import.new-content-rollback")
        .noneMatch(id -> id.startsWith("backup."))
        .doesNotContain("import.snapshot-rollback");
    assertThat(plan.summary().rollbackPointsByPhase().get("import"))
        .contains("delete /public/batch")
        .contains("does not exist yet")
        .doesNotContain("nothing to put back");
    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("/public/batch does not exist") && w.contains("is deleted"))
        .anyMatch(
            w -> w.contains("no pre-import snapshot") && w.contains("deleting what it created"))
        .noneMatch(w -> w.contains("nothing to put back"));
  }

  /** The topmost missing folder is the one to delete, not the archive's own folder. */
  @Test
  void should_name_the_topmost_missing_ancestor_in_the_rollback() throws IOException {
    sidecar(List.of("/e2e/batch"), false);
    adapter.existing = Optional.of(Set.of());

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(plan.summary().rollbackPointsByPhase().get("import")).contains("delete /e2e with");
  }

  /**
   * The step is in the plan whenever the sidecar names folders, existing or not, so a plan rebuilt
   * by {@code runs recover} after the import created them has the same steps.
   */
  @Test
  void should_keep_the_same_steps_whether_or_not_the_folders_exist_at_plan_time()
      throws IOException {
    sidecar(List.of("/public"), false);
    adapter.existing = Optional.of(Set.of());
    List<String> absent = ids(fx.ops().planImport(importOf(archive, false)));

    adapter.existing = Optional.of(Set.of("/public"));
    Plan present = fx.ops().planImport(importOf(archive, false));

    assertThat(absent).contains("import.new-content-rollback");
    assertThat(ids(present)).contains("import.new-content-rollback", "import.snapshot-rollback");
    assertThat(present.summary().rollbackPointsByPhase().get("import"))
        .doesNotContain("also delete");
  }

  @Test
  void should_add_no_new_content_step_when_the_archive_holds_the_whole_repository()
      throws IOException {
    sidecar(List.of(), true);

    Plan plan = fx.ops().planImport(importOf(archive, true));

    assertThat(ids(plan)).doesNotContain("import.new-content-rollback");
  }

  @Test
  void should_scope_the_new_content_folders_to_the_sidecar_and_the_organisation() {
    Sidecar.Flags repository =
        new Sidecar.Flags(
            ExportRequest.Scope.REPOSITORY,
            List.of("/b", "/a"),
            true,
            false,
            false,
            false,
            false,
            false);
    Sidecar.Flags whole =
        new Sidecar.Flags(
            ExportRequest.Scope.EVERYTHING, List.of(), true, false, false, false, false, true);
    java.util.function.Function<Sidecar.Flags, Optional<Sidecar>> of =
        f ->
            Optional.of(
                new Sidecar(
                    Instant.parse("2026-09-01T00:00:00Z"),
                    "srv",
                    "8.2.0",
                    Optional.empty(),
                    f,
                    EximFixture.sha256(archive),
                    ExportImportStrategy.Kind.REST));

    assertThat(DefaultExportImportOperations.newContentUris(of.apply(repository), Optional.empty()))
        .containsExactly("/a", "/b");
    assertThat(DefaultExportImportOperations.newContentUris(of.apply(whole), Optional.empty()))
        .isEmpty();
    assertThat(DefaultExportImportOperations.newContentUris(Optional.empty(), Optional.empty()))
        .isEmpty();
    assertThat(DefaultExportImportOperations.newContentUris(Optional.empty(), Optional.of("acme")))
        .containsExactly("/organizations/acme");
  }

  @Test
  void should_refuse_planning_when_archive_is_missing() {
    assertThatThrownBy(() -> fx.ops().planImport(importOf(tmp.resolve("nope.zip"), false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope.zip");
    assertThat(adapter.imports).isEmpty();
  }

  /** Field test 2, E3: a mistyped --uri is refused before anything runs. */
  @Test
  void should_refuse_planning_when_a_uri_does_not_exist_on_the_server() {
    adapter.existing = Optional.of(Set.of("/public"));

    assertThatThrownBy(
            () ->
                fx.ops()
                    .planExport(export(Set.of("/public", "/typo"), false, tmp.resolve("x.zip"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/typo does not exist on the server");
    assertThat(adapter.existenceChecks).containsExactlyInAnyOrder("/public", "/typo");
  }

  @Test
  void should_not_ask_the_server_about_the_root_or_a_full_server_export() {
    adapter.existing = Optional.of(Set.of());

    fx.ops().planExport(export(Set.of(), false, tmp.resolve("root.zip")));
    fx.ops().planExport(export(Set.of(), true, tmp.resolve("full.zip")));

    assertThat(adapter.existenceChecks).isEmpty();
  }

  /**
   * Importing new content is the ordinary case: a folder the archive holds need not exist on the
   * target yet, so the snapshot covers what does exist and says what does not.
   */
  @Test
  void should_snapshot_only_the_sidecar_uris_that_exist_when_planning_an_import()
      throws IOException {
    sidecar(List.of("/public/a", "/public/b"), false);
    adapter.existing = Optional.of(Set.of("/public/a"));

    Plan plan = fx.ops().planImport(importOf(archive, true));

    assertThat(plan.summary().resourcesTouched()).containsExactly("/public/a");
    assertThat(plan.summary().warnings())
        .anyMatch(w -> w.contains("/public/b does not exist on this server yet"));
  }

  @Test
  void should_skip_the_snapshot_when_none_of_the_sidecar_uris_exist_yet() throws IOException {
    sidecar(List.of("/public/new"), false);
    adapter.existing = Optional.of(Set.of());

    Plan plan = fx.ops().planImport(importOf(archive, false));

    assertThat(plan.steps().stream().map(Step::id))
        .noneMatch(id -> id.startsWith("snapshot") || id.contains("restore"));
    assertThat(plan.summary().warnings()).anyMatch(w -> w.contains("no pre-import snapshot"));
    assertThat(plan.summary().backupLocations()).isEmpty();
  }

  @Test
  void should_use_sidecar_uris_for_snapshot_when_sidecar_is_a_subtree_export() throws IOException {
    sidecar(List.of("/organizations/org_1", "/public/reports"), false);

    Plan plan = fx.ops().planImport(importOf(archive, true));

    assertThat(plan.summary().resourcesTouched())
        .containsExactly("/organizations/org_1", "/public/reports");
    // issue #100: the listing names the same folders, then the export step follows
    assertThat(plan.steps().get(3).detail())
        .startsWith("every URI under ")
        .contains("/organizations/org_1")
        .contains("/public/reports");
    assertThat(plan.steps().get(4).detail())
        .startsWith("uris ")
        .contains("/organizations/org_1")
        .contains("/public/reports");
  }
}
