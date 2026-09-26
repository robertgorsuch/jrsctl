package com.jaspersoft.jrsctl.ops.exim;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.RunOptions;
import com.jaspersoft.jrsctl.core.engine.RunOutcome;
import com.jaspersoft.jrsctl.core.engine.Runner;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.DefaultFileOps;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.SecretResolver;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.strategy.Polling;
import com.jaspersoft.jrsctl.jrs.strategy.RestStrategy;
import com.jaspersoft.jrsctl.jrs.strategy.Strategies;
import com.jaspersoft.jrsctl.jrs.strategy.VendorCliStrategy;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * A {@link Services} bundle for export/import tests: the {@link FakeServices} home and config, a
 * platform whose file operations compute a real streaming SHA-256 (the sidecar and the archive
 * fingerprint need one), the adapter of the test's choice, no-wait polling strategies, and a
 * context builder that registers exactly what {@code PlanExecutor} registers.
 */
final class EximFixture implements AutoCloseable {

  static final String RUN = "r-20260908-100000-0001";

  static final String YAML =
      """
      server:
        baseUrl: http://localhost:8080/jasperserver-pro
        installDir: %s
        auth:
          username: jasperadmin
          passwordRef: env:JRS_PASSWORD
      service:
        kind: manual
      network:
        mode: public
      """;

  final FakeServices fake;
  final Platform platform;
  final Services services;
  final Strategies strategies;
  final List<Event> events = new CopyOnWriteArrayList<>();

  EximFixture(Path root, Supplier<JrsAdapter> adapter) throws IOException {
    this(root, adapter, YAML.formatted(root.toString().replace("\\", "/")));
  }

  EximFixture(Path root, Supplier<JrsAdapter> adapter, String yaml) throws IOException {
    this.fake = FakeServices.in(root.resolve("home"));
    fake.yaml(yaml);
    Services base = fake.build();
    this.platform = new HashingPlatform(fake.platform);
    this.services =
        new Services(
            base.home(),
            base.config(),
            platform,
            base.secrets(),
            base.redactor(),
            base.matrix(),
            base.stateStore(),
            adapter,
            base.clock(),
            false);
    Polling polling =
        new Polling(
            Duration.ZERO,
            Duration.ZERO,
            Duration.ofHours(2),
            Duration.ofSeconds(30),
            base.clock(),
            Sleeper.none());
    this.strategies =
        new Strategies(
            new RestStrategy(polling),
            new VendorCliStrategy(
                new BuildomaticLocator(platform),
                new VendorTools(platform.processes(), platform.files(), base.redactor()),
                polling));
  }

  DefaultExportImportOperations ops() {
    return new DefaultExportImportOperations(services, strategies);
  }

  Context context(String runId) {
    Map<Class<?>, Object> map = new HashMap<>();
    map.put(Services.class, services);
    map.put(StateStore.class, services.stateStore().get());
    map.put(Config.class, services.config());
    map.put(JrsAdapter.class, new DeferredJrsAdapter(services.adapter()));
    map.put(Redactor.class, services.redactor());
    map.put(SecretResolver.class, services.secrets());
    return new Context(runId, services.home(), platform, new CancellationToken(), map);
  }

  /** The hex SHA-256 an export's sidecar records for {@code file} (issue #185). */
  static String sha256(Path file) {
    try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
        digest.update(buffer, 0, n);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (IOException | java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  RunOutcome run(Plan plan, String runId) {
    EventSink sink = events::add;
    Runner runner = new Runner(services.stateStore().get(), sink, services.clock(), Sleeper.none());
    return runner.run(plan, context(runId), plan.fingerprint(), RunOptions.DEFAULT);
  }

  List<String> journal(String runId) {
    return services.stateStore().get().transitions(runId).stream()
        .map(t -> t.stepId() + ":" + t.toState())
        .toList();
  }

  @Override
  public void close() {
    fake.close();
  }

  /** The fake platform with a real SHA-256 in place of its unsupported one. */
  static final class HashingPlatform implements Platform {
    private final Platform delegate;
    private final FileOps hashing;

    HashingPlatform(Platform delegate) {
      this.delegate = delegate;
      FileOps inner = delegate.files();
      DefaultFileOps real = new DefaultFileOps();
      this.hashing =
          new FileOps() {
            @Override
            public String sha256(Path file) throws IOException {
              return real.sha256(file);
            }

            @Override
            public void atomicReplace(Path source, Path target) throws IOException {
              inner.atomicReplace(source, target);
            }

            @Override
            public void copyPreserving(Path source, Path target) throws IOException {
              inner.copyPreserving(source, target);
            }

            @Override
            public boolean isLocked(Path file) {
              return inner.isLocked(file);
            }

            @Override
            public Optional<String> lockHolder(Path file) {
              return inner.lockHolder(file);
            }

            @Override
            public Optional<String> lockInspectionLimit() {
              return inner.lockInspectionLimit();
            }

            @Override
            public Permissions capturePermissions(Path path) throws IOException {
              return inner.capturePermissions(path);
            }

            @Override
            public void applyPermissions(Path path, Permissions permissions) throws IOException {
              inner.applyPermissions(path, permissions);
            }

            @Override
            public long freeSpaceBytes(Path anyPathOnVolume) throws IOException {
              return inner.freeSpaceBytes(anyPathOnVolume);
            }

            @Override
            public String volumeId(Path anyPathOnVolume) throws IOException {
              return inner.volumeId(anyPathOnVolume);
            }

            @Override
            public boolean isWritable(Path dir) {
              return inner.isWritable(dir);
            }

            @Override
            public boolean isOwnerOnly(Path file) throws IOException {
              return inner.isOwnerOnly(file);
            }
          };
    }

    @Override
    public OsFamily os() {
      return delegate.os();
    }

    @Override
    public Arch arch() {
      return delegate.arch();
    }

    @Override
    public ServiceController services(ServiceConfig cfg) {
      return delegate.services(cfg);
    }

    @Override
    public FileOps files() {
      return hashing;
    }

    @Override
    public ProcessRunner processes() {
      return delegate.processes();
    }

    @Override
    public Path defaultHome() {
      return delegate.defaultHome();
    }

    @Override
    public Optional<TomcatLayout> detectTomcat(Path installDir) {
      return delegate.detectTomcat(installDir);
    }

    @Override
    public List<Path> candidateInstallDirs() {
      return delegate.candidateInstallDirs();
    }
  }
}
