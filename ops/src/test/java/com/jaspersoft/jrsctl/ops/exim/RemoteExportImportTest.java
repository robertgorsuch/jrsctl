package com.jaspersoft.jrsctl.ops.exim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
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

/**
 * Issue #68: a REST export or import needs only the server address and credentials, so it runs from
 * a machine that is not the JasperReports Server host; the vendor tools, which need a local
 * installation, are refused with a message that says so instead of failing half-way.
 */
class RemoteExportImportTest {

  /** Nothing about a local installation: no installDir, tomcatDir, buildomaticDir or service. */
  static final String REMOTE_YAML =
      """
      server:
        baseUrl: http://localhost:8080/jasperserver-pro
        auth:
          username: superuser
          passwordRef: env:JRS_PASSWORD
      network:
        mode: public
      """;

  @TempDir Path tmp;

  private EximFakeAdapter adapter;
  private EximFixture fx;

  @BeforeEach
  void setUp() throws IOException {
    adapter = new EximFakeAdapter();
    fx = new EximFixture(tmp, () -> adapter, REMOTE_YAML);
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private ExportOptions export(boolean fullServer, Optional<ExportImportStrategy.Kind> kind) {
    return new ExportOptions(
        Set.of("/public"),
        true,
        false,
        false,
        false,
        false,
        fullServer,
        tmp.resolve("out").resolve("public.zip"),
        kind,
        false);
  }

  @Test
  void should_export_through_rest_when_no_local_installation_is_configured() {
    Plan plan = fx.ops().planExport(export(false, Optional.empty()));

    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).as(fx.events.toString()).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(plan.summary().strategy()).startsWith("rest (");
    assertThat(tmp.resolve("out").resolve("public.zip")).exists();
    assertThat(Sidecar.pathFor(tmp.resolve("out").resolve("public.zip"))).exists();
  }

  @Test
  void should_import_through_rest_and_warn_when_the_server_keystore_cannot_be_read_from_here()
      throws IOException {
    adapter.keystore = KeystoreInfo.absent("no local installation");
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
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).as(fx.events.toString()).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(plan.summary().strategy()).startsWith("rest (");
    assertThat(adapter.imports).hasSize(1);
    assertThat(adapter.exports).as("the pre-import snapshot, taken through REST").hasSize(1);
  }

  @Test
  void should_refuse_a_full_server_export_naming_the_local_installation_it_needs() {
    assertThatThrownBy(() -> fx.ops().planExport(export(true, Optional.empty())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("full-server")
        .hasMessageContaining("installation")
        .hasMessageContaining("run jrsctl on the JasperReports Server host");
  }

  @Test
  void should_refuse_the_vendor_fallback_naming_why_rest_was_not_used_when_the_probe_fails() {
    adapter.capabilities = EnumSet.noneOf(com.jaspersoft.jrsctl.jrs.api.Capability.class);

    assertThatThrownBy(() -> fx.ops().planExport(export(false, Optional.empty())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EXPORT_ASYNC")
        .hasMessageContaining("installation");
  }

  @Test
  void should_refuse_a_forced_vendor_strategy_when_no_local_installation_is_configured() {
    assertThatThrownBy(
            () ->
                fx.ops()
                    .planExport(export(false, Optional.of(ExportImportStrategy.Kind.VENDOR_CLI))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--strategy vendor")
        .hasMessageContaining("installation");
  }
}
