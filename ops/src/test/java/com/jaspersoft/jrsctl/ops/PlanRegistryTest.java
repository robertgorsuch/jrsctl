package com.jaspersoft.jrsctl.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The arguments of every mutating operation, written to the {@code plans} table and read back when
 * {@code runs recover} rebuilds a plan. A field that is written but not read, or read under another
 * name, turns a resumed run into a different run than the one the operator confirmed, and nothing
 * else in the suite would notice.
 */
class PlanRegistryTest {

  @Test
  void should_round_trip_hotfix_apply_arguments() throws IOException {
    JsonNode args = tree(PlanRegistry.applyArgs(Path.of("bundles", "hf-1.zip"), true));

    assertThat(args.get("bundle").asText()).endsWith("hf-1.zip");
    assertThat(Path.of(args.get("bundle").asText()).isAbsolute())
        .as("a resumed run must not depend on the working directory")
        .isTrue();
    assertThat(args.get("allowUnsigned").asBoolean()).isTrue();
  }

  @Test
  void should_round_trip_hotfix_rollback_arguments() throws IOException {
    JsonNode args = tree(PlanRegistry.rollbackArgs("hf-2024-01", true));

    assertThat(args.get("id").asText()).isEqualTo("hf-2024-01");
    assertThat(args.get("cascade").asBoolean()).isTrue();
  }

  @Test
  void should_round_trip_every_export_flag() throws IOException {
    ExportImportOperations.ExportOptions options =
        new ExportImportOperations.ExportOptions(
            Set.of("/public", "/organizations"),
            true,
            false,
            true,
            false,
            true,
            false,
            Path.of("out", "export.zip"),
            Optional.of(ExportImportStrategy.Kind.REST));

    JsonNode args = tree(PlanRegistry.exportArgs(options));

    assertThat(args.get("usersRoles").asBoolean()).isTrue();
    assertThat(args.get("accessEvents").asBoolean()).isFalse();
    assertThat(args.get("auditEvents").asBoolean()).isTrue();
    assertThat(args.get("monitoring").asBoolean()).isFalse();
    assertThat(args.get("settings").asBoolean()).isTrue();
    assertThat(args.get("fullServer").asBoolean()).isFalse();
    assertThat(args.get("out").asText()).endsWith("export.zip");
    assertThat(args.get("strategy").asText()).isEqualTo("rest");
    assertThat(args.get("uris")).hasSize(2);
  }

  /**
   * Issue #67: whether the export stops the service is part of the stored arguments; arguments
   * journaled before the flag existed rebuild the plan they were made from, which always stopped.
   */
  @Test
  void should_store_the_stop_service_choice_and_read_its_absence_as_the_old_stopping_plan()
      throws IOException {
    ExportImportOperations.ExportOptions live =
        new ExportImportOperations.ExportOptions(
            Set.of("/"),
            false,
            false,
            false,
            false,
            false,
            true,
            Path.of("out", "full.zip"),
            Optional.empty(),
            false);

    JsonNode args = tree(PlanRegistry.exportArgs(live));

    assertThat(args.get("stopService").asBoolean(true)).isFalse();
    assertThat(PlanRegistry.exportOptions(args).stopService()).isFalse();
    ObjectNode legacy = (ObjectNode) args.deepCopy();
    legacy.remove("stopService");
    assertThat(PlanRegistry.exportOptions(legacy).stopService()).isTrue();
  }

  @Test
  void should_round_trip_every_import_flag_including_the_keystore() throws IOException {
    ExportImportOperations.ImportOptions options =
        new ExportImportOperations.ImportOptions(
            Path.of("in", "import.zip"),
            true,
            false,
            true,
            false,
            true,
            false,
            true,
            Optional.of(Path.of("keys", "source.jrsks")),
            Optional.of(SecretRef.parse("env:KEYSTORE_PASSWORD")),
            Optional.of(ExportImportStrategy.Kind.VENDOR_CLI),
            BrokenDependencies.INCLUDE);

    JsonNode args = tree(PlanRegistry.importArgs(options));
    ExportImportOperations.ImportOptions back = PlanRegistry.importOptions(args);

    assertThat(args.get("brokenDependencies").asText()).isEqualTo("include");
    assertThat(back.brokenDependencies()).isEqualTo(BrokenDependencies.INCLUDE);
    // arguments stored before the option existed describe a plan that used the server default
    assertThat(PlanRegistry.importOptions(tree("{\"archive\":\"a.zip\"}")).brokenDependencies())
        .isEqualTo(BrokenDependencies.FAIL);

    assertThat(args.get("archive").asText()).endsWith("import.zip");
    assertThat(args.get("update").asBoolean()).isTrue();
    assertThat(args.get("skipUserUpdate").asBoolean()).isFalse();
    assertThat(args.get("skipThemes").asBoolean()).isTrue();
    assertThat(args.get("sourceKeystore").asText()).endsWith("source.jrsks");
    assertThat(args.get("sourceKeystorePassword").asText()).isEqualTo("env:KEYSTORE_PASSWORD");
    assertThat(args.get("strategy").asText()).isEqualTo("vendor");
  }

  @Test
  void should_round_trip_the_new_tomcat_of_an_upgrade() throws IOException {
    UpgradeOperations.UpgradeOptions options =
        new UpgradeOperations.UpgradeOptions(
            "10.0.0",
            Path.of("pkg"),
            UpgradeOperations.Mode.NEWDB,
            true,
            false,
            Optional.of(Path.of("tomcat", "new")));

    JsonNode args = tree(PlanRegistry.upgradeArgs(options));
    UpgradeOperations.UpgradeOptions back = PlanRegistry.upgradeOptions(args);

    assertThat(args.get("tomcatDir").asText()).endsWith("new");
    assertThat(back.tomcatDir()).isPresent();
    assertThat(
            PlanRegistry.upgradeOptions(
                    tree("{\"to\":\"10.0.0\",\"package\":\"p\",\"mode\":\"NEWDB\"}"))
                .tomcatDir())
        .isEmpty();
  }

  /** Issue #106: the events choice survives the stored arguments; older arguments mean no. */
  @Test
  void should_round_trip_include_events_of_an_upgrade() throws IOException {
    UpgradeOperations.UpgradeOptions options =
        UpgradeOperations.UpgradeOptions.newdb("10.0.0", Path.of("pkg")).withIncludeEvents(true);

    JsonNode args = tree(PlanRegistry.upgradeArgs(options));

    assertThat(args.get("includeEvents").asBoolean()).isTrue();
    assertThat(PlanRegistry.upgradeOptions(args).includeEvents()).isTrue();
    assertThat(
            PlanRegistry.upgradeOptions(
                    tree("{\"to\":\"10.0.0\",\"package\":\"p\",\"mode\":\"NEWDB\"}"))
                .includeEvents())
        .isFalse();
  }

  /**
   * Issue #108: the password migration choice survives the stored arguments; older ones mean no.
   */
  @Test
  void should_round_trip_migrate_passwords_of_an_upgrade() throws IOException {
    UpgradeOperations.UpgradeOptions options =
        UpgradeOperations.UpgradeOptions.newdb("10.1.0", Path.of("pkg"))
            .withIncludeEvents(true)
            .withMigratePasswords(true);

    JsonNode args = tree(PlanRegistry.upgradeArgs(options));

    assertThat(args.get("migratePasswords").asBoolean()).isTrue();
    UpgradeOperations.UpgradeOptions back = PlanRegistry.upgradeOptions(args);
    assertThat(back.migratePasswords()).isTrue();
    assertThat(back.includeEvents()).as("chaining keeps the other flag").isTrue();
    assertThat(
            PlanRegistry.upgradeOptions(
                    tree("{\"to\":\"10.1.0\",\"package\":\"p\",\"mode\":\"SAMEDB\"}"))
                .migratePasswords())
        .isFalse();
  }

  /** Issue #107: the version override survives the stored arguments; older arguments mean no. */
  @Test
  void should_round_trip_force_version_of_an_import() throws IOException {
    ExportImportOperations.ImportOptions options =
        new ExportImportOperations.ImportOptions(
                Path.of("a.zip"),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
            .withForceVersion(true);

    JsonNode args = tree(PlanRegistry.importArgs(options));

    assertThat(args.get("forceVersion").asBoolean()).isTrue();
    assertThat(PlanRegistry.importOptions(args).forceVersion()).isTrue();
    assertThat(PlanRegistry.importOptions(tree("{\"archive\":\"a.zip\"}")).forceVersion())
        .isFalse();
  }

  /**
   * ADR-0040: a new import stores that its snapshot runs with the server up; arguments an earlier
   * jrsctl stored have no such key and describe the snapshot that stopped the service, so {@code
   * runs recover} rebuilds the steps that run journaled.
   */
  @Test
  void should_read_the_old_stopping_snapshot_when_stored_import_arguments_predate_the_key()
      throws IOException {
    ExportImportOperations.ImportOptions fresh =
        new ExportImportOperations.ImportOptions(
            Path.of("a.zip").toAbsolutePath().normalize(),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    JsonNode args = tree(PlanRegistry.importArgs(fresh));

    assertThat(fresh.snapshotStopsService()).isFalse();
    assertThat(args.get("snapshotStopsService").asBoolean(true)).isFalse();
    assertThat(PlanRegistry.importOptions(args).snapshotStopsService()).isFalse();
    assertThat(PlanRegistry.importOptions(args)).isEqualTo(fresh);
    ExportImportOperations.ImportOptions old =
        PlanRegistry.importOptions(tree("{\"archive\":\"a.zip\"}"));
    assertThat(old.snapshotStopsService()).isTrue();
    assertThat(
            PlanRegistry.importOptions(tree(PlanRegistry.importArgs(old))).snapshotStopsService())
        .as("a recovered old plan keeps its shape when stored again")
        .isTrue();
  }

  /** ADR-0040: {@code --no-snapshot} survives the stored arguments; older arguments mean no. */
  @Test
  void should_round_trip_no_snapshot_when_import_arguments_carry_it() throws IOException {
    ExportImportOperations.ImportOptions options =
        new ExportImportOperations.ImportOptions(
                Path.of("a.zip").toAbsolutePath().normalize(),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
            .withNoSnapshot(true)
            .withForceVersion(true)
            .withKeepThemes(true);

    JsonNode args = tree(PlanRegistry.importArgs(options));

    assertThat(args.get("noSnapshot").asBoolean()).isTrue();
    assertThat(PlanRegistry.importOptions(args)).isEqualTo(options);
    assertThat(PlanRegistry.importOptions(args).noSnapshot()).isTrue();
    assertThat(PlanRegistry.importOptions(tree("{\"archive\":\"a.zip\"}")).noSnapshot()).isFalse();
  }

  /**
   * Issue #115: {@code --themes} survives the stored arguments, and an old row means the default.
   */
  @Test
  void should_round_trip_the_keep_themes_switch_on_import_arguments() throws IOException {
    ExportImportOperations.ImportOptions options =
        new ExportImportOperations.ImportOptions(
                Path.of("in", "import.zip"),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
            .withKeepThemes(true)
            .withForceVersion(true);

    JsonNode args = tree(PlanRegistry.importArgs(options));

    assertThat(args.get("keepThemes").asBoolean()).isTrue();
    ExportImportOperations.ImportOptions back = PlanRegistry.importOptions(args);
    assertThat(back.keepThemes()).isTrue();
    assertThat(back.forceVersion()).as("withForceVersion after withKeepThemes keeps both").isTrue();
    assertThat(PlanRegistry.importOptions(tree("{\"archive\":\"a.zip\"}")).keepThemes()).isFalse();
  }

  /** Field test 2, E4 and I1: the key alias survives the stored arguments of both plans. */
  @Test
  void should_round_trip_the_key_alias_on_export_and_import_arguments() throws IOException {
    ExportImportOperations.ExportOptions export =
        new ExportImportOperations.ExportOptions(
            Set.of("/public"),
            false,
            false,
            false,
            false,
            false,
            false,
            Path.of("out.zip"),
            Optional.empty(),
            true,
            Optional.of("deprecatedImportExportEncSecret"));
    ExportImportOperations.ImportOptions imported =
        new ExportImportOperations.ImportOptions(
            Path.of("in.zip"),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            BrokenDependencies.FAIL,
            Optional.of("k1"));

    assertThat(tree(PlanRegistry.exportArgs(export)).get("keyAlias").asText())
        .isEqualTo("deprecatedImportExportEncSecret");
    assertThat(PlanRegistry.exportOptions(tree(PlanRegistry.exportArgs(export))).keyAlias())
        .contains("deprecatedImportExportEncSecret");
    assertThat(PlanRegistry.importOptions(tree(PlanRegistry.importArgs(imported))).keyAlias())
        .contains("k1");
    assertThat(
            tree(PlanRegistry.exportArgs(
                    new ExportImportOperations.ExportOptions(
                        Set.of(),
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Path.of("o.zip"),
                        Optional.empty())))
                .get("keyAlias")
                .isNull())
        .isTrue();
  }

  /**
   * Field test 2, E5 and I4: the organisation and the merge switch survive the stored arguments.
   */
  @Test
  void should_round_trip_the_organisation_on_export_and_import_arguments() throws IOException {
    ExportImportOperations.ExportOptions export =
        new ExportImportOperations.ExportOptions(
            Set.of(),
            false,
            false,
            false,
            false,
            false,
            false,
            Path.of("out.zip"),
            Optional.empty(),
            true,
            Optional.empty(),
            Optional.of("org1"));
    ExportImportOperations.ImportOptions imported =
        new ExportImportOperations.ImportOptions(
            Path.of("in.zip"),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            BrokenDependencies.FAIL,
            Optional.empty(),
            Optional.of("org1"),
            true);

    ExportImportOperations.ExportOptions exportBack =
        PlanRegistry.exportOptions(tree(PlanRegistry.exportArgs(export)));
    ExportImportOperations.ImportOptions importBack =
        PlanRegistry.importOptions(tree(PlanRegistry.importArgs(imported)));

    assertThat(exportBack.organization()).contains("org1");
    assertThat(importBack.organization()).contains("org1");
    assertThat(importBack.mergeOrganization()).isTrue();
  }

  /**
   * Vendor doc review §4.2, issue #144: the two export-only skip flags survive the stored
   * arguments.
   */
  @Test
  void should_round_trip_skip_dependent_and_favorite_resources_on_export_arguments()
      throws IOException {
    ExportImportOperations.ExportOptions export =
        new ExportImportOperations.ExportOptions(
            Set.of("/public/report.jrxml"),
            false,
            false,
            false,
            false,
            false,
            false,
            Path.of("out.zip"),
            Optional.empty(),
            true,
            Optional.empty(),
            Optional.empty(),
            true,
            true);

    ExportImportOperations.ExportOptions exportBack =
        PlanRegistry.exportOptions(tree(PlanRegistry.exportArgs(export)));

    assertThat(exportBack.skipDependentResources()).isTrue();
    assertThat(exportBack.skipFavoriteResources()).isTrue();
  }

  @Test
  void should_default_skip_dependent_and_favorite_resources_when_arguments_predate_the_option()
      throws IOException {
    ExportImportOperations.ExportOptions export =
        new ExportImportOperations.ExportOptions(
            Set.of(),
            false,
            false,
            false,
            false,
            false,
            false,
            Path.of("out.zip"),
            Optional.empty());

    ExportImportOperations.ExportOptions exportBack =
        PlanRegistry.exportOptions(tree(PlanRegistry.exportArgs(export)));

    assertThat(exportBack.skipDependentResources()).isFalse();
    assertThat(exportBack.skipFavoriteResources()).isFalse();
  }

  @Test
  void should_round_trip_upgrade_arguments() throws IOException {
    JsonNode rollback =
        tree(PlanRegistry.upgradeRollbackArgs("r-2026", UpgradeOperations.RollbackPoint.B));

    assertThat(rollback.get("runId").asText()).isEqualTo("r-2026");
    assertThat(rollback.get("point").asText()).isNotBlank();
    assertThat(rollback.get("restoreDatabase").asBoolean()).isFalse();

    JsonNode withDatabase =
        tree(
            PlanRegistry.upgradeRollbackArgs(
                "r-2026",
                new UpgradeOperations.RollbackOptions(UpgradeOperations.RollbackPoint.B, true)));
    assertThat(withDatabase.get("restoreDatabase").asBoolean()).isTrue();
  }

  @Test
  void should_refuse_an_operation_this_build_cannot_rebuild() {
    PlanRegistry registry =
        new PlanRegistry(
            () -> {
              throw new IllegalStateException("not needed");
            },
            () -> {
              throw new IllegalStateException("not needed");
            },
            () -> {
              throw new IllegalStateException("not needed");
            });

    assertThatThrownBy(() -> registry.rebuild("hotfix.teleport", "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown operation")
        .hasMessageContaining(PlanRegistry.HOTFIX_APPLY);
  }

  @Test
  void should_refuse_stored_arguments_that_are_not_json() {
    PlanRegistry registry =
        new PlanRegistry(
            () -> {
              throw new IllegalStateException("not needed");
            },
            () -> {
              throw new IllegalStateException("not needed");
            },
            () -> {
              throw new IllegalStateException("not needed");
            });

    assertThatThrownBy(() -> registry.rebuild(PlanRegistry.HOTFIX_APPLY, "not json at all"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not JSON");
  }

  // ---------------------------------------------------------------- rebuild through the registry

  private final Recorder hotfix = new Recorder();
  private final Recorder exim = new Recorder();
  private final Recorder upgrade = new Recorder();

  private PlanRegistry registry() {
    return new PlanRegistry(
        () -> hotfix.as(HotfixOperations.class),
        () -> exim.as(ExportImportOperations.class),
        () -> upgrade.as(UpgradeOperations.class));
  }

  @Test
  void should_plan_the_same_upgrade_when_rebuilding_from_the_stored_arguments() {
    UpgradeOperations.UpgradeOptions options =
        new UpgradeOperations.UpgradeOptions(
            "10.1.0", Path.of("packages", "jrs-10.1.0"), UpgradeOperations.Mode.SAMEDB, true, true);

    registry().rebuild(PlanRegistry.UPGRADE, PlanRegistry.upgradeArgs(options));

    assertThat(upgrade.calls).containsExactly("planUpgrade");
    assertThat(upgrade.args.get(0)).containsExactly(options);
  }

  @Test
  void should_plan_the_same_upgrade_rollback_when_rebuilding_from_the_stored_arguments() {
    registry()
        .rebuild(
            PlanRegistry.UPGRADE_ROLLBACK,
            PlanRegistry.upgradeRollbackArgs("r-20260914", UpgradeOperations.RollbackPoint.C));

    assertThat(upgrade.calls).containsExactly("planRollback");
    assertThat(upgrade.args.get(0))
        .containsExactly(
            "r-20260914",
            new UpgradeOperations.RollbackOptions(UpgradeOperations.RollbackPoint.C, false));
  }

  @Test
  void should_plan_the_same_hotfix_apply_when_rebuilding_from_the_stored_arguments() {
    Path bundle = Path.of("bundles", "hf-1.zip");

    registry().rebuild(PlanRegistry.HOTFIX_APPLY, PlanRegistry.applyArgs(bundle, true));

    assertThat(hotfix.calls).containsExactly("planApply");
    assertThat(hotfix.args.get(0))
        .containsExactly(
            bundle.toAbsolutePath().normalize(), new HotfixOperations.ApplyOptions(true));
  }

  @Test
  void should_keep_a_confirmed_checksum_when_rebuilding_a_hotfix_apply() {
    Path bundle = Path.of("bundles", "hf-1.zip");
    HotfixOperations.ApplyOptions confirmed =
        new HotfixOperations.ApplyOptions(HotfixOperations.UnsignedAcceptance.CHECKSUM_CONFIRMED);

    registry().rebuild(PlanRegistry.HOTFIX_APPLY, PlanRegistry.applyArgs(bundle, confirmed));

    assertThat(hotfix.args.get(0)).containsExactly(bundle.toAbsolutePath().normalize(), confirmed);
  }

  /** Arguments stored by 2.0.0 and earlier carry only the boolean. */
  @Test
  void should_read_allow_unsigned_when_older_stored_arguments_carry_only_the_boolean() {
    String older =
        "{\"bundle\":\""
            + Path.of("hf-1.zip").toAbsolutePath().toString().replace("\\", "\\\\")
            + "\",\"allowUnsigned\":true}";

    registry().rebuild(PlanRegistry.HOTFIX_APPLY, older);

    assertThat(((HotfixOperations.ApplyOptions) hotfix.args.get(0).get(1)).unsigned())
        .isEqualTo(HotfixOperations.UnsignedAcceptance.ALLOW_UNSIGNED);
  }

  @Test
  void should_plan_the_same_hotfix_rollback_when_rebuilding_from_the_stored_arguments() {
    registry().rebuild(PlanRegistry.HOTFIX_ROLLBACK, PlanRegistry.rollbackArgs("hf-1", true));

    assertThat(hotfix.calls).containsExactly("planRollback");
    assertThat(hotfix.args.get(0))
        .containsExactly("hf-1", new HotfixOperations.RollbackOptions(true));
  }

  @Test
  void should_plan_the_same_export_when_rebuilding_from_the_stored_arguments() {
    ExportImportOperations.ExportOptions options =
        new ExportImportOperations.ExportOptions(
            Set.of("/public", "/organizations"),
            true,
            false,
            true,
            false,
            true,
            false,
            Path.of("out", "export.zip").toAbsolutePath().normalize(),
            Optional.of(ExportImportStrategy.Kind.REST));

    registry().rebuild(PlanRegistry.EXPORT, PlanRegistry.exportArgs(options));

    assertThat(exim.calls).containsExactly("planExport");
    assertThat(exim.args.get(0)).containsExactly(options);
  }

  @Test
  void should_ignore_blank_and_non_text_uris_when_reading_export_arguments() throws IOException {
    ExportImportOperations.ExportOptions options =
        PlanRegistry.exportOptions(
            tree("{\"uris\":[\"/public\",\" \",5],\"out\":\"export.zip\",\"strategy\":null}"));

    assertThat(options.uris()).containsExactly("/public");
    assertThat(options.strategy()).isEmpty();
    assertThat(options.usersRoles()).isFalse();
  }

  @Test
  void should_plan_the_same_import_when_rebuilding_from_the_stored_arguments() {
    ExportImportOperations.ImportOptions withKeystore =
        new ExportImportOperations.ImportOptions(
            Path.of("in", "import.zip").toAbsolutePath().normalize(),
            true,
            false,
            true,
            false,
            true,
            false,
            true,
            Optional.of(Path.of("keys", "source.jrsks").toAbsolutePath().normalize()),
            Optional.of(SecretRef.parse("env:KEYSTORE_PASSWORD")),
            Optional.of(ExportImportStrategy.Kind.VENDOR_CLI));
    ExportImportOperations.ImportOptions plain =
        new ExportImportOperations.ImportOptions(
            Path.of("in", "plain.zip").toAbsolutePath().normalize(),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    registry().rebuild(PlanRegistry.IMPORT, PlanRegistry.importArgs(withKeystore));
    registry().rebuild(PlanRegistry.IMPORT, PlanRegistry.importArgs(plain));

    assertThat(exim.calls).containsExactly("planImport", "planImport");
    assertThat(exim.args.get(0)).containsExactly(withKeystore);
    assertThat(exim.args.get(1)).containsExactly(plain);
  }

  @Test
  void should_name_the_missing_field_when_stored_arguments_lack_a_required_one() {
    assertThatThrownBy(() -> registry().rebuild(PlanRegistry.UPGRADE, "{\"to\":\"10.1.0\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'package'");
    assertThatThrownBy(() -> registry().rebuild(PlanRegistry.HOTFIX_ROLLBACK, "{\"id\":\"  \"}"))
        .as("a blank value counts as missing")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'id'");
    assertThat(upgrade.calls).isEmpty();
    assertThat(hotfix.calls).isEmpty();
  }

  /** Implements any operations interface, records each call and its arguments, answers null. */
  private static final class Recorder implements InvocationHandler {
    final List<String> calls = new ArrayList<>();
    final List<List<Object>> args = new ArrayList<>();

    <T> T as(Class<T> type) {
      return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, this));
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // a proxy's equals is identity by definition
    public Object invoke(Object proxy, Method method, Object[] arguments) {
      if (method.getDeclaringClass() == Object.class) {
        return switch (method.getName()) {
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == arguments[0];
          default -> "recorder";
        };
      }
      calls.add(method.getName());
      args.add(arguments == null ? List.of() : List.of(arguments));
      return null;
    }
  }

  private static JsonNode tree(String json) throws IOException {
    return Json.mapper().readTree(json);
  }
}
