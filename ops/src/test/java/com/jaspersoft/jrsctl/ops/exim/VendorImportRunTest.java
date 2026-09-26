package com.jaspersoft.jrsctl.ops.exim;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.ops.FakePlatform;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations.ImportOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0040: a vendor import run by the real Runner against fake {@code js-export}/{@code js-import}
 * tools and a recording service controller. The pre-import snapshot runs with the server up, so the
 * import is the only outage; a failed {@code js-import} starts the service again before the REST
 * deletions and the snapshot re-import of the rollback, which the fake adapter refuses while the
 * service is stopped, as a real server would.
 */
class VendorImportRunTest {

  @TempDir Path tmp;

  private EximFakeAdapter adapter;
  private EximFixture fx;
  private FakePlatform platform;
  private Path archive;

  /** Each vendor invocation: the tool, its archive argument and the service state it ran in. */
  private record Call(String tool, String zip, boolean update, ServiceController.State state) {}

  private final List<Call> calls = new ArrayList<>();
  private int failImports;

  @BeforeEach
  void setUp() throws IOException {
    Path install = tmp.resolve("jrs");
    Path buildomatic = Files.createDirectories(install.resolve("buildomatic"));
    Files.writeString(buildomatic.resolve("js-export.sh"), "#!/bin/sh\n");
    Files.writeString(buildomatic.resolve("js-import.sh"), "#!/bin/sh\n");
    Path javaHome = Files.createDirectories(tmp.resolve("jdk"));
    adapter = new EximFakeAdapter();
    fx =
        new EximFixture(
            tmp,
            () -> adapter,
            """
            server:
              baseUrl: http://localhost:8080/jasperserver-pro
              installDir: %s
              auth:
                username: jasperadmin
                passwordRef: env:JRS_PASSWORD
            service:
              kind: systemd
              name: jasperreports
            vendor:
              javaHome: %s
            network:
              mode: public
            """
                .formatted(slashes(install), slashes(javaHome)));
    platform = fx.fake.platform;
    adapter.serverUp = () -> platform.serviceState == ServiceController.State.RUNNING;
    platform.dynamic = this::vendorTool;
    archive = tmp.resolve("in").resolve("public.zip");
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
            ExportImportStrategy.Kind.VENDOR_CLI));
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private static String slashes(Path p) {
    return p.toString().replace("\\", "/");
  }

  /** The fake vendor tools: js-export writes an archive with an index, js-import may fail. */
  private Optional<FakePlatform.Response> vendorTool(List<String> command) {
    int out = command.indexOf("--output-zip");
    if (out >= 0) {
      Path zip = Path.of(command.get(out + 1));
      calls.add(new Call("js-export", zip.toString(), false, platform.serviceState));
      try {
        Files.createDirectories(zip.getParent());
        try (OutputStream raw = Files.newOutputStream(zip);
            ZipOutputStream z = new ZipOutputStream(raw)) {
          z.putNextEntry(new ZipEntry("index.xml"));
          z.write("<export/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
          z.closeEntry();
        }
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      return Optional.of(FakePlatform.Response.ok("Processing started", "Done"));
    }
    int in = command.indexOf("--input-zip");
    if (in >= 0) {
      calls.add(
          new Call(
              "js-import",
              command.get(in + 1),
              command.contains("--update"),
              platform.serviceState));
      if (failImports > 0) {
        failImports--;
        return Optional.of(new FakePlatform.Response(2, List.of("BUILD FAILED")));
      }
      return Optional.of(
          FakePlatform.Response.ok("VALIDATION COMPLETED", "Processing started", "Done"));
    }
    return Optional.empty();
  }

  private ImportOptions vendorImport() {
    return new ImportOptions(
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
  }

  private List<Call> tool(String name) {
    return calls.stream().filter(c -> c.tool().equals(name)).toList();
  }

  @Test
  void should_stop_the_service_once_when_a_vendor_import_succeeds() {
    Plan plan = fx.ops().planImport(vendorImport());

    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).as(fx.events.toString()).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(platform.controller.events).containsExactly("stop", "start");
    assertThat(tool("js-export"))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.state())
                  .as("the snapshot runs with the server up")
                  .isEqualTo(ServiceController.State.RUNNING);
              assertThat(c.zip()).contains("pre-import");
            });
    assertThat(tool("js-import"))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.state()).isEqualTo(ServiceController.State.STOPPED);
              assertThat(Path.of(c.zip())).isEqualTo(archive);
            });
    assertThat(platform.serviceState).isEqualTo(ServiceController.State.RUNNING);
  }

  /**
   * The compensation order matters: the stop-service step's compensation starts the service before
   * the new-content and snapshot anchors run, so the REST deletions and the snapshot re-import
   * (itself stop, js-import, start) find a running server.
   */
  @Test
  void should_start_the_service_before_the_rollback_deletes_and_reimports_when_js_import_fails() {
    failImports = 1;
    adapter.trees.add(List.of("/public/kept"));
    adapter.trees.add(List.of("/public/kept", "/public/added"));
    Plan plan = fx.ops().planImport(vendorImport());
    Path snapshot = plan.summary().backupLocations().get(0);

    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).as(fx.events.toString()).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(outcome.exitCode()).isEqualTo(3);
    assertThat(adapter.refusedWhileDown).as("no REST call met a stopped server").isEmpty();
    assertThat(adapter.deleted).containsExactly("/public/added");
    List<Call> imports = tool("js-import");
    assertThat(imports).hasSize(2);
    assertThat(Path.of(imports.get(1).zip())).isEqualTo(snapshot);
    assertThat(imports.get(1).update()).isTrue();
    assertThat(imports.get(1).state()).isEqualTo(ServiceController.State.STOPPED);
    assertThat(platform.controller.events)
        .as("the import's stop, its rollback's start, then the restore's own stop and start")
        .containsExactly("stop", "start", "stop", "start");
    assertThat(platform.serviceState).isEqualTo(ServiceController.State.RUNNING);
    assertThat(fx.journal(EximFixture.RUN))
        .containsSubsequence(
            "import.stop-service:SUCCEEDED",
            "import.js-import:FAILED",
            "import.stop-service:ROLLED_BACK",
            "import.new-content-rollback:ROLLED_BACK",
            "import.snapshot-rollback:ROLLED_BACK");
  }

  /** ADR-0040: arguments an earlier jrsctl stored still run the plan it journaled. */
  @Test
  void should_stop_around_the_snapshot_too_when_the_plan_comes_from_older_arguments() {
    Plan plan = fx.ops().planImport(vendorImport().withSnapshotStopsService(true));

    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).as(fx.events.toString()).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(platform.controller.events).containsExactly("stop", "start", "stop", "start");
    assertThat(tool("js-export").get(0).state()).isEqualTo(ServiceController.State.STOPPED);
  }

  /** ADR-0040: without a snapshot, the rollback still deletes additions with the server up. */
  @Test
  void should_delete_additions_without_reimporting_when_no_snapshot_import_fails() {
    failImports = 1;
    adapter.trees.add(List.of("/public/kept"));
    adapter.trees.add(List.of("/public/kept", "/public/added"));
    Plan plan = fx.ops().planImport(vendorImport().withNoSnapshot(true));

    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).as(fx.events.toString()).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(tool("js-export")).as("no snapshot").isEmpty();
    assertThat(tool("js-import")).as("no restore import").hasSize(1);
    assertThat(adapter.refusedWhileDown).isEmpty();
    assertThat(adapter.deleted).containsExactly("/public/added");
    assertThat(platform.controller.events).containsExactly("stop", "start");
    assertThat(platform.serviceState).isEqualTo(ServiceController.State.RUNNING);
    assertThat(fx.journal(EximFixture.RUN))
        .containsSubsequence(
            "backup.pre-import-listing:SUCCEEDED",
            "import.additions-rollback:SUCCEEDED",
            "import.js-import:FAILED",
            "import.stop-service:ROLLED_BACK",
            "import.additions-rollback:ROLLED_BACK");
  }
}
