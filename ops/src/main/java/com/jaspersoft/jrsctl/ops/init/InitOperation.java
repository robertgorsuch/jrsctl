package com.jaspersoft.jrsctl.ops.init;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigWriter;
import com.jaspersoft.jrsctl.core.platform.InstallScan;
import com.jaspersoft.jrsctl.core.platform.LinuxInit;
import com.jaspersoft.jrsctl.core.platform.NaturalOrder;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticResolution;
import com.jaspersoft.jrsctl.ops.JrsVersion;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.UnsupportedDeployment;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code jrsctl init} (spec §12.0): detects the installation and proposes a {@code config.yaml}.
 * Invariants: {@link #detect} is read-only and never throws for a missing or odd installation (it
 * reports what it found and defaults the rest); without {@code --install-dir} every installation
 * the platform search finds is listed, ranked running first, then highest version by number, then
 * path, and the first is proposed, while {@link #choose} proposes another from the same list (field
 * test 3); every value in the report carries its source; the only write is {@link #write}, which
 * refuses to overwrite an existing file unless forced; passwords are never read from anywhere, only
 * {@code env:} placeholders are written; confirmation is the caller's job.
 */
public final class InitOperation {

  /** Username proposed when the edition is unknown or Community; it exists on both editions. */
  public static final String DEFAULT_USERNAME = "jasperadmin";

  /**
   * Username proposed for the commercial edition, where jasperadmin administers one organisation
   * and full-server operations need superuser (#59).
   */
  public static final String COMMERCIAL_USERNAME = "superuser";

  public static final String DEFAULT_PASSWORD_REF = "env:JRS_PASSWORD";
  public static final String DEFAULT_DB_PASSWORD_REF = "env:JRS_DB_PASSWORD";
  public static final String DEFAULT_SMOKE_REPORT = "/public/Samples/Reports/AllAccounts";
  public static final int DEFAULT_HTTP_PORT = 8080;

  private static final Logger LOG = LoggerFactory.getLogger(InitOperation.class);
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern SC_SERVICE_NAME =
      Pattern.compile("^\\s*SERVICE_NAME:\\s*(\\S.*?)\\s*$");
  private static final String SOURCE_DEFAULT = "default";

  /** Report key carrying what supervises services on this host (review 3.2). */
  static final String SERVICE_MANAGER = "service.manager";

  private final Services services;
  private final Supplier<Optional<String>> tomcatOwner;
  private final Supplier<LinuxInit.Detected> linuxInit;

  public InitOperation(Services services) {
    this(services, InitOperation::owningUserOfTomcat);
  }

  InitOperation(Services services, Supplier<Optional<String>> tomcatOwner) {
    this(services, tomcatOwner, LinuxInit::detect);
  }

  InitOperation(
      Services services,
      Supplier<Optional<String>> tomcatOwner,
      Supplier<LinuxInit.Detected> linuxInit) {
    this.services = Objects.requireNonNull(services, "services");
    this.tomcatOwner = Objects.requireNonNull(tomcatOwner, "tomcatOwner");
    this.linuxInit = Objects.requireNonNull(linuxInit, "linuxInit");
  }

  /** Detects the installation; {@code installDirHint} is tried before the platform candidates. */
  public InitReport detect(Optional<Path> installDirHint) {
    return detect(installDirHint, Optional.empty());
  }

  /**
   * Why {@code init} must refuse this host before detecting anything (ADR-0043): jrsctl runs inside
   * a container, or {@code --install-dir} names a JBoss EAP or WildFly home with no Tomcat layout.
   * Not consulted for {@code --remote}.
   */
  public Optional<UnsupportedDeployment.Refusal> unsupported(
      Optional<Path> installDirHint, UnsupportedDeployment.Probe probe) {
    return UnsupportedDeployment.container(probe)
        .or(
            () ->
                installDirHint
                    .filter(dir -> services.platform().detectTomcat(dir).isEmpty())
                    .flatMap(UnsupportedDeployment::jboss));
  }

  /**
   * As {@link #detect(Optional)}; {@code buildomaticDirHint} is proposed as {@code
   * server.buildomaticDir} when it is a reachable directory, and without it the directory is
   * searched for the way {@link BuildomaticLocator#resolve} does (ADR-0013).
   */
  public InitReport detect(Optional<Path> installDirHint, Optional<Path> buildomaticDirHint) {
    Objects.requireNonNull(installDirHint, "installDirHint");
    Objects.requireNonNull(buildomaticDirHint, "buildomaticDirHint");
    Platform platform = services.platform();
    if (installDirHint.isPresent()) {
      return platform
          .detectTomcat(installDirHint.get())
          .map(
              layout ->
                  detected(layout, "from --install-dir", buildomaticDirHint, List.of(), List.of()))
          .orElseGet(() -> notDetected(installDirHint, List.of()));
    }
    Found found = search(platform);
    if (found.candidates().isEmpty()) {
      return notDetected(installDirHint, found.notes());
    }
    return detected(found.candidates(), 0, buildomaticDirHint, found.notes(), false);
  }

  /**
   * The report {@link #detect} would give had the operator picked candidate {@code index} of {@code
   * report}'s list (field test 3): the same list, without searching again, with that one chosen.
   *
   * @throws IllegalArgumentException when {@code index} is not a position in the list
   */
  public InitReport choose(InitReport report, int index, Optional<Path> buildomaticDirHint) {
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(buildomaticDirHint, "buildomaticDirHint");
    if (index < 0 || index >= report.candidates().size()) {
      throw new IllegalArgumentException(
          "no installation " + (index + 1) + " of " + report.candidates().size());
    }
    return detected(report.candidates(), index, buildomaticDirHint, report.notes(), true);
  }

  private InitReport detected(
      List<InitReport.Candidate> candidates,
      int index,
      Optional<Path> buildomaticDirHint,
      List<String> notes,
      boolean pickedByOperator) {
    List<InitReport.Candidate> marked = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      marked.add(candidates.get(i).withChosen(i == index));
    }
    InitReport.Candidate chosen = marked.get(index);
    String source =
        pickedByOperator
            ? "chosen from " + candidates.size() + " detected installations"
            : "detected candidate "
                + chosen.layout().installDir()
                + " ("
                + describe(chosen)
                + (candidates.size() > 1
                    ? "; recommended of " + candidates.size() + " installations found"
                    : "")
                + ")";
    return detected(chosen.layout(), source, buildomaticDirHint, marked, notes);
  }

  /** "running, JRS 10.0.0 commercial" and the like, for a source or a list line. */
  public static String describe(InitReport.Candidate candidate) {
    return (candidate.running() ? "running" : "not seen running")
        + ", JasperReports Server "
        + candidate.version().orElse("version unknown")
        + " "
        + candidate.edition();
  }

  private InitReport notDetected(Optional<Path> installDirHint, List<String> notes) {
    List<InitReport.Detected> values = new ArrayList<>();
    Config defaults = Config.defaults();
    values.add(
        new InitReport.Detected(
            "server.installDir",
            "(not detected)",
            installDirHint
                .map(p -> "no Tomcat layout under " + p)
                .orElse("no candidate install dir found; pass --install-dir")));
    values.add(new InitReport.Detected("server.auth.username", DEFAULT_USERNAME, SOURCE_DEFAULT));
    values.add(
        new InitReport.Detected("server.auth.passwordRef", DEFAULT_PASSWORD_REF, SOURCE_DEFAULT));
    values.add(new InitReport.Detected("smoke.reportUri", DEFAULT_SMOKE_REPORT, SOURCE_DEFAULT));
    Config config =
        new Config(
            defaults.server(),
            defaults.service(),
            defaults.database(),
            defaults.vendor(),
            defaults.network(),
            defaults.backups(),
            new Config.Smoke(Optional.of(DEFAULT_SMOKE_REPORT)));
    return new InitReport(config, values, List.of(), notes);
  }

  private InitReport detected(
      TomcatLayout layout,
      String installDirSource,
      Optional<Path> buildomaticDirHint,
      List<InitReport.Candidate> candidates,
      List<String> notes) {
    List<InitReport.Detected> values = new ArrayList<>();
    Config defaults = Config.defaults();
    values.add(
        new InitReport.Detected(
            "server.installDir", layout.installDir().toString(), installDirSource));
    values.add(
        new InitReport.Detected(
            "server.tomcatDir", layout.tomcatDir().toString(), "detected from Tomcat layout"));
    values.add(
        new InitReport.Detected(
            "server.webappName", layout.webappName(), "detected from " + layout.webappDir()));
    int port = layout.httpPort().orElse(DEFAULT_HTTP_PORT);
    String portSource =
        layout.httpPort().isPresent() ? "detected from conf/server.xml" : SOURCE_DEFAULT;
    URI baseUrl = URI.create("http://localhost:" + port + "/" + layout.webappName());
    values.add(new InitReport.Detected("server.baseUrl", baseUrl.toString(), portSource));

    Optional<String> runAsUser = probeTomcatOwner();
    runAsUser.ifPresent(
        u ->
            values.add(
                new InitReport.Detected("server.runAsUser", u, "owner of the Tomcat process")));
    String username = username(layout.webappName());
    values.add(
        new InitReport.Detected(
            "server.auth.username",
            username,
            username.equals(COMMERCIAL_USERNAME)
                ? "commercial edition ("
                    + layout.webappName()
                    + "): full-server operations need"
                    + " superuser"
                : "community edition (" + layout.webappName() + ")"));
    values.add(
        new InitReport.Detected("server.auth.passwordRef", DEFAULT_PASSWORD_REF, SOURCE_DEFAULT));

    Config.Service service = probeService(layout, values);

    Optional<Path> buildomaticDir = buildomaticDir(layout, buildomaticDirHint, values);
    DefaultMasterProperties master = readDefaultMaster(buildomaticDir);
    Config.Database database = database(master, values);

    Optional<Path> javaHome = layout.bundledJavaHome();
    javaHome.ifPresent(
        j ->
            values.add(
                new InitReport.Detected(
                    "vendor.javaHome",
                    j.toString(),
                    "bundled JDK in install dir; buildomatic runs with it (not the Java jrsctl runs"
                        + " on, which is its own)")));
    values.add(new InitReport.Detected("smoke.reportUri", DEFAULT_SMOKE_REPORT, SOURCE_DEFAULT));
    values.add(
        new InitReport.Detected(
            "network.mode", defaults.network().mode().yamlValue(), SOURCE_DEFAULT));

    Config config =
        new Config(
            new Config.Server(
                Optional.of(baseUrl),
                Config.YamlValued.fromYaml(Config.WebappName.class, layout.webappName()),
                Optional.of(layout.installDir()),
                Optional.of(layout.tomcatDir()),
                buildomaticDir,
                runAsUser,
                new Config.Auth(
                    Config.AuthMode.DEFAULT,
                    Optional.of(username),
                    Optional.of(SecretRef.parse(DEFAULT_PASSWORD_REF)))),
            service,
            database,
            new Config.Vendor(javaHome),
            defaults.network(),
            defaults.backups(),
            new Config.Smoke(Optional.of(DEFAULT_SMOKE_REPORT)));
    return new InitReport(config, values, candidates, notes);
  }

  /**
   * A server-only configuration for a jrsctl that reaches the server over REST and has no
   * installation on this machine (#68): the address, the webapp and admin user its path implies,
   * and the password placeholder. Nothing is probed and nothing local is detected.
   */
  public InitReport detectRemote(URI baseUrl) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    List<InitReport.Detected> values = new ArrayList<>();
    Config defaults = Config.defaults();
    values.add(new InitReport.Detected("server.baseUrl", baseUrl.toString(), "from --remote"));
    String path = baseUrl.getPath() == null ? "" : baseUrl.getPath();
    String last = path.replaceAll("/+$", "");
    last = last.substring(last.lastIndexOf('/') + 1);
    Optional<Config.WebappName> webapp =
        last.equals(Config.WebappName.JASPERSERVER_PRO.yamlValue())
            ? Optional.of(Config.WebappName.JASPERSERVER_PRO)
            : last.equals(Config.WebappName.JASPERSERVER.yamlValue())
                ? Optional.of(Config.WebappName.JASPERSERVER)
                : Optional.empty();
    webapp.ifPresent(
        w ->
            values.add(
                new InitReport.Detected(
                    "server.webappName", w.yamlValue(), "last segment of the --remote address")));
    String username = username(webapp.map(Config.WebappName::yamlValue).orElse(""));
    values.add(
        new InitReport.Detected(
            "server.auth.username",
            username,
            username.equals(COMMERCIAL_USERNAME)
                ? "commercial edition (jasperserver-pro): full-server operations need superuser"
                : "default; set it to the admin user you log in with"));
    values.add(
        new InitReport.Detected("server.auth.passwordRef", DEFAULT_PASSWORD_REF, SOURCE_DEFAULT));
    values.add(
        new InitReport.Detected(
            "server.installDir",
            "(none)",
            "remote: REST export and import only; run jrsctl on the server for the rest"));
    Config config =
        new Config(
            new Config.Server(
                Optional.of(baseUrl),
                webapp,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new Config.Auth(
                    Config.AuthMode.DEFAULT,
                    Optional.of(username),
                    Optional.of(SecretRef.parse(DEFAULT_PASSWORD_REF)))),
            defaults.service(),
            defaults.database(),
            defaults.vendor(),
            defaults.network(),
            defaults.backups(),
            new Config.Smoke(Optional.of(DEFAULT_SMOKE_REPORT)));
    return new InitReport(config, values);
  }

  /** The configuration the report proposes. */
  public Config toConfig(InitReport report) {
    return Objects.requireNonNull(report, "report").config();
  }

  /**
   * Writes {@code config} to {@code config.yaml} in the home, as {@link #write(Config, Path,
   * boolean)}.
   */
  public Path write(Config config, boolean force) throws IOException {
    return write(config, services.home().yamlConfigFile(), force);
  }

  /**
   * Writes {@code config} to {@code target}, {@code config.yaml} or {@code jrsctl.properties} in
   * the home, in the format its name says (#74).
   *
   * @throws FileAlreadyExistsException when {@code target} exists and {@code force} is false, or
   *     when the configuration exists in the other format, which even {@code force} does not
   *     replace because two files would be left
   */
  public Path write(Config config, Path target, boolean force) throws IOException {
    Path other = otherFormat(target);
    if (Files.exists(other)) {
      throw new FileAlreadyExistsException(
          other.toString(),
          null,
          "the configuration is already in "
              + other.getFileName()
              + "; move it out of the jrsctl home to switch formats, or write that format");
    }
    if (Files.exists(target) && !force) {
      throw new FileAlreadyExistsException(
          target.toString(),
          null,
          target.getFileName() + " already exists; pass --force to overwrite it");
    }
    ConfigWriter.write(config, target);
    return target;
  }

  /** The home's configuration file in the format {@code target} is not in. */
  public Path otherFormat(Path target) {
    return target.equals(services.home().propertiesConfigFile())
        ? services.home().yamlConfigFile()
        : services.home().propertiesConfigFile();
  }

  // ---- detection helpers ------------------------------------------------------------------------

  private static String username(String webappName) {
    return webappName.equals(Config.WebappName.JASPERSERVER_PRO.yamlValue())
        ? COMMERCIAL_USERNAME
        : DEFAULT_USERNAME;
  }

  /** Every installation the platform search found, ranked, and notes about the search. */
  private record Found(List<InitReport.Candidate> candidates, List<String> notes) {}

  /**
   * Field test 3: every candidate with a Tomcat layout, not just the first, one entry per Tomcat
   * directory (a running Tomcat is found at its Tomcat directory, the search at the install root
   * above it: the root is kept, and the entry counts as running), ranked by {@link #RANK}.
   */
  private static Found search(Platform platform) {
    InstallScan scan;
    try {
      scan = platform.scanInstallDirs();
    } catch (RuntimeException e) {
      LOG.debug("candidate install dir probe failed", e);
      scan = new InstallScan(List.of(), Set.of(), Optional.empty());
    }
    Map<String, InitReport.Candidate> byTomcat = new LinkedHashMap<>();
    for (Path dir : scan.candidates()) {
      Optional<TomcatLayout> layout = platform.detectTomcat(dir);
      if (layout.isEmpty()) {
        continue;
      }
      boolean running = scan.isRunning(dir);
      String tomcat = layout.get().tomcatDir().toAbsolutePath().normalize().toString();
      String key =
          platform.os() == Platform.OsFamily.WINDOWS ? tomcat.toLowerCase(Locale.ROOT) : tomcat;
      InitReport.Candidate found =
          new InitReport.Candidate(layout.get(), versionOf(layout.get()), running, false);
      byTomcat.merge(
          key,
          found,
          (a, b) -> {
            TomcatLayout outer =
                a.layout().installDir().getNameCount() <= b.layout().installDir().getNameCount()
                    ? a.layout()
                    : b.layout();
            return new InitReport.Candidate(
                outer, a.version().or(b::version), a.running() || b.running(), false);
          });
    }
    List<InitReport.Candidate> ranked = new ArrayList<>(byTomcat.values());
    ranked.sort(RANK);
    return new Found(ranked, scan.processScanLimit().stream().toList());
  }

  /**
   * Running first, then the highest version by number (one whose version is unknown last), then the
   * path, so the order is stable.
   */
  static final Comparator<InitReport.Candidate> RANK =
      Comparator.comparing(InitReport.Candidate::running)
          .reversed()
          .thenComparing(
              (a, b) ->
                  b.version().isPresent() && a.version().isPresent()
                      ? NaturalOrder.compareVersions(b.version().get(), a.version().get())
                      : Boolean.compare(b.version().isPresent(), a.version().isPresent()))
          .thenComparing(c -> c.layout().installDir(), NaturalOrder.PATHS);

  /** The version the webapp's jars state, else the one the install directory's name states. */
  private static Optional<String> versionOf(TomcatLayout layout) {
    return JrsVersion.ofWebapp(layout.webappDir())
        .or(() -> JrsVersion.ofDistributionDir(layout.installDir()));
  }

  private Optional<String> probeTomcatOwner() {
    try {
      return tomcatOwner.get();
    } catch (RuntimeException e) {
      LOG.debug("tomcat owner probe failed", e);
      return Optional.empty();
    }
  }

  private static Optional<String> owningUserOfTomcat() {
    return ProcessHandle.allProcesses()
        .filter(
            p ->
                p.info()
                    .commandLine()
                    .map(c -> c.toLowerCase(Locale.ROOT))
                    .filter(c -> c.contains("catalina") && c.contains("java"))
                    .isPresent())
        .map(p -> p.info().user())
        .flatMap(Optional::stream)
        .findFirst();
  }

  private Config.Service probeService(TomcatLayout layout, List<InitReport.Detected> values) {
    Platform platform = services.platform();
    // review 3.2: name the Linux service manager instead of assuming systemd, and never probe
    // systemctl on a host that process 1 says is supervised some other way
    Optional<LinuxInit.Detected> init =
        platform.os() == Platform.OsFamily.LINUX ? Optional.of(linuxInit.get()) : Optional.empty();
    init.ifPresent(
        d ->
            values.add(
                new InitReport.Detected(
                    SERVICE_MANAGER, d.kind().name().toLowerCase(Locale.ROOT), d.detail())));
    Optional<ManagedService> managed =
        switch (platform.os()) {
          case WINDOWS -> windowsService(platform.processes(), layout.tomcatDir());
          case LINUX ->
              init.orElseThrow().supervised()
                  ? Optional.empty()
                  : systemdUnit(platform.processes(), layout.tomcatDir());
        };
    ServiceConfig.Kind managedKind =
        switch (platform.os()) {
          case WINDOWS -> ServiceConfig.Kind.WINDOWS_SERVICE;
          case LINUX -> ServiceConfig.Kind.SYSTEMD;
        };
    String probeName =
        switch (platform.os()) {
          case WINDOWS -> "sc.exe query";
          case LINUX -> "systemctl list-units";
        };
    if (managed.isPresent()) {
      values.add(
          new InitReport.Detected(
              "service.kind", Config.Service.kindToYaml(managedKind), "detected via " + probeName));
      ManagedService service = managed.get();
      values.add(
          new InitReport.Detected(
              "service.name",
              service.name(),
              "detected via "
                  + probeName
                  + (service.pathConfirmed()
                      ? "; its executable is under " + layout.tomcatDir()
                      : "; chosen by name only, its executable could not be confirmed under "
                          + layout.tomcatDir()
                          + " (check it before the first stop)")));
      return new Config.Service(
          Optional.of(managedKind),
          Optional.of(service.name()),
          Optional.empty(),
          Config.Service.DEFAULT_STOP_TIMEOUT_SECONDS);
    }
    String ext =
        switch (platform.os()) {
          case WINDOWS -> ".bat";
          case LINUX -> ".sh";
        };
    Path ctlscript = layout.installDir().resolve("ctlscript" + ext);
    if (Files.isRegularFile(ctlscript)) {
      warnIfSupervised(init, values);
      return scriptService(ServiceConfig.Kind.CTLSCRIPT, ctlscript, values);
    }
    Path catalina = layout.tomcatDir().resolve("bin").resolve("catalina" + ext);
    if (Files.isRegularFile(catalina)) {
      warnIfSupervised(init, values);
      return scriptService(ServiceConfig.Kind.CATALINA, catalina, values);
    }
    values.add(
        new InitReport.Detected(
            "service.kind",
            Config.Service.kindToYaml(ServiceConfig.Kind.MANUAL),
            "no registered service, ctlscript or catalina script found: jrsctl will ask you to"
                + " stop and start Tomcat and wait for it"));
    return new Config.Service(
        Optional.of(ServiceConfig.Kind.MANUAL),
        Optional.empty(),
        Optional.empty(),
        Config.Service.DEFAULT_STOP_TIMEOUT_SECONDS);
  }

  /**
   * Records that a script stop can be undone by the supervisor that owns process 1, so the operator
   * sees it before confirming the proposed configuration (review 3.2).
   */
  private static void warnIfSupervised(
      Optional<LinuxInit.Detected> init, List<InitReport.Detected> values) {
    init.filter(LinuxInit.Detected::supervised)
        .ifPresent(
            d ->
                values.add(
                    new InitReport.Detected(
                        SERVICE_MANAGER,
                        "warning",
                        "a script stop bypasses "
                            + d.pid1().orElse(d.kind().name().toLowerCase(Locale.ROOT))
                            + ", which may restart the server mid-run; set service.kind to manual"
                            + " or stop the server through its supervisor")));
  }

  private static Config.Service scriptService(
      ServiceConfig.Kind kind, Path script, List<InitReport.Detected> values) {
    String source =
        switch (kind) {
          case CATALINA ->
              "no registered service found (usual for a WAR + buildomatic install); jrsctl runs "
                  + script.getFileName()
                  + " to stop and start Tomcat, whether it was started by that script, by"
                  + " startup or by hand; choose manual if a supervisor or your own tooling"
                  + " starts it";
          case CTLSCRIPT ->
              "no registered service found; jrsctl runs "
                  + script.getFileName()
                  + " to stop and start Tomcat";
          case WINDOWS_SERVICE, SYSTEMD, MANUAL -> throw new IllegalArgumentException(kind.name());
        };
    values.add(new InitReport.Detected("service.kind", Config.Service.kindToYaml(kind), source));
    values.add(new InitReport.Detected("service.scriptPath", script.toString(), "detected"));
    return new Config.Service(
        Optional.of(kind),
        Optional.empty(),
        Optional.of(script),
        Config.Service.DEFAULT_STOP_TIMEOUT_SECONDS);
  }

  /**
   * A managed service found by name, and whether its executable was confirmed to live under the
   * detected Tomcat directory. On a host with a second Tomcat a name alone can pick the wrong one,
   * and a hotfix would then stop the wrong server (assessment item P4).
   */
  record ManagedService(String name, boolean pathConfirmed) {}

  private static Optional<ManagedService> windowsService(ProcessRunner runner, Path tomcatDir) {
    List<String> names = new ArrayList<>();
    run(
        runner,
        List.of("sc.exe", "query", "state=", "all"),
        line -> {
          Matcher m = SC_SERVICE_NAME.matcher(line);
          if (m.matches()) {
            names.add(m.group(1));
          }
        });
    // `sc qc` prints BINARY_PATH_NAME, the service executable (procrun's tomcatNw.exe under the
    // Tomcat directory for a Tomcat service).
    return choose(names, name -> mentions(query(runner, List.of("sc.exe", "qc", name)), tomcatDir));
  }

  private static Optional<ManagedService> systemdUnit(ProcessRunner runner, Path tomcatDir) {
    List<String> names = new ArrayList<>();
    run(
        runner,
        List.of(
            "systemctl", "list-units", "--type=service,socket", "--all", "--no-legend", "--plain"),
        line -> {
          String stripped = line.strip();
          if (!stripped.isEmpty()) {
            int space = stripped.indexOf(' ');
            names.add(space < 0 ? stripped : stripped.substring(0, space));
          }
        });
    return choose(
        preferSockets(names),
        name ->
            mentions(
                query(
                    runner,
                    List.of(
                        "systemctl",
                        "show",
                        serviceUnitOf(name),
                        "-p",
                        "ExecStart",
                        "-p",
                        "ExecStop")),
                tomcatDir));
  }

  /**
   * A socket-activated Tomcat (the vendor's AWS images control it through {@code tomcat.socket},
   * AWS guide p.30, issue #113) must be driven through its socket: stopping the service alone
   * leaves the socket to start it again on the next request. So {@code X.service} (or {@code X})
   * gives way to {@code X.socket} whenever both are listed, each name once, order kept.
   */
  static List<String> preferSockets(List<String> names) {
    Set<String> listed = new LinkedHashSet<>(names);
    Set<String> out = new LinkedHashSet<>();
    for (String name : names) {
      String stem = name.endsWith(".service") ? name.substring(0, name.length() - 8) : name;
      String socket = stem + ".socket";
      out.add(!name.endsWith(".socket") && listed.contains(socket) ? socket : name);
    }
    return List.copyOf(out);
  }

  /** The unit whose {@code ExecStart} names the Tomcat: {@code X.service} for {@code X.socket}. */
  static String serviceUnitOf(String unit) {
    return unit.endsWith(".socket") ? unit.substring(0, unit.length() - 7) + ".service" : unit;
  }

  private static List<String> query(ProcessRunner runner, List<String> command) {
    List<String> lines = new ArrayList<>();
    run(runner, command, lines::add);
    return lines;
  }

  /** Whether any line names a path under {@code tomcatDir}, either separator, either case. */
  static boolean mentions(List<String> lines, Path tomcatDir) {
    String dir =
        tomcatDir
            .toAbsolutePath()
            .normalize()
            .toString()
            .replace('/', '\\')
            .toLowerCase(Locale.ROOT);
    return lines.stream()
        .map(l -> l.replace('/', '\\').toLowerCase(Locale.ROOT))
        .anyMatch(l -> l.contains(dir));
  }

  /**
   * The best-ranked candidate whose executable is confirmed, else the best-ranked one, unconfirmed.
   */
  static Optional<ManagedService> choose(List<String> names, Predicate<String> confirmed) {
    List<String> ranked = rankServiceNames(names);
    for (String name : ranked) {
      if (confirmed.test(name)) {
        return Optional.of(new ManagedService(name, true));
      }
    }
    return ranked.stream().findFirst().map(name -> new ManagedService(name, false));
  }

  static Optional<String> pickServiceName(List<String> names) {
    return rankServiceNames(names).stream().findFirst();
  }

  /**
   * Prefers the application-server service: names mentioning both jasper and tomcat (the bundled
   * installer registers {@code jasperreportsTomcat} next to {@code jasperreportsPostgreSQL}), then
   * any tomcat, then any jasper name that is not the database service; each name once.
   */
  static List<String> rankServiceNames(List<String> names) {
    List<String> lower = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
    Set<String> ranked = new LinkedHashSet<>();
    for (int i = 0; i < names.size(); i++) {
      if (lower.get(i).contains("jasper") && lower.get(i).contains("tomcat")) {
        ranked.add(names.get(i));
      }
    }
    for (int i = 0; i < names.size(); i++) {
      if (lower.get(i).contains("tomcat")) {
        ranked.add(names.get(i));
      }
    }
    for (int i = 0; i < names.size(); i++) {
      String n = lower.get(i);
      if (n.contains("jasper") && !n.contains("postgres") && !n.contains("sql")) {
        ranked.add(names.get(i));
      }
    }
    return List.copyOf(ranked);
  }

  private static void run(
      ProcessRunner runner, List<String> command, java.util.function.Consumer<String> onStdout) {
    try {
      runner.run(
          new ProcessRunner.Request(command, Optional.empty(), Map.of(), PROBE_TIMEOUT),
          line -> {
            if (line.stream() == ProcessRunner.OutputLine.Stream.STDOUT) {
              onStdout.accept(line.text());
            }
          });
    } catch (RuntimeException e) {
      LOG.debug("service probe {} failed", command.get(0), e);
    }
  }

  /**
   * The buildomatic directory to propose: the hint when it is reachable, else what the locator
   * finds from the detected layout. Either way the value and its source go into the report, and an
   * unreachable hint is reported rather than written.
   */
  private Optional<Path> buildomaticDir(
      TomcatLayout layout, Optional<Path> hint, List<InitReport.Detected> values) {
    Config defaults = Config.defaults();
    Config probe =
        new Config(
            new Config.Server(
                Optional.empty(),
                Optional.empty(),
                Optional.of(layout.installDir()),
                Optional.of(layout.tomcatDir()),
                hint,
                Optional.empty(),
                Config.Auth.defaults()),
            defaults.service(),
            defaults.database(),
            defaults.vendor(),
            defaults.network(),
            defaults.backups(),
            defaults.smoke());
    return switch (new BuildomaticLocator(services.platform()).resolve(probe)) {
      case BuildomaticResolution.Found found -> {
        Path dir = found.buildomatic().dir();
        String source = hint.isPresent() ? "from --buildomatic-dir" : "detected " + found.source();
        values.add(new InitReport.Detected("server.buildomaticDir", dir.toString(), source));
        yield Optional.of(dir);
      }
      case BuildomaticResolution.NotFound missing -> {
        values.add(
            new InitReport.Detected(
                "server.buildomaticDir",
                "(not detected)",
                missing.detail() + "; " + missing.remediation()));
        yield Optional.empty();
      }
    };
  }

  private static DefaultMasterProperties readDefaultMaster(Optional<Path> buildomatic) {
    if (buildomatic.isEmpty()) {
      return DefaultMasterProperties.empty();
    }
    try {
      return DefaultMasterProperties.parse(buildomatic.get().resolve("default_master.properties"));
    } catch (IOException e) {
      LOG.debug("cannot read default_master.properties", e);
      return DefaultMasterProperties.empty();
    }
  }

  private static Config.Database database(
      DefaultMasterProperties master, List<InitReport.Detected> values) {
    Optional<Config.DatabaseType> type = master.databaseType();
    if (type.isEmpty()) {
      return Config.Database.empty();
    }
    // #73: read at run time from default_master.properties, not copied, so the two cannot drift
    String source = "from default_master.properties at run time (not copied into config.yaml)";
    values.add(new InitReport.Detected("database.type", type.get().yamlValue(), source));
    master.jdbcUrl().ifPresent(u -> values.add(new InitReport.Detected("database.url", u, source)));
    master
        .dbUsername()
        .ifPresent(u -> values.add(new InitReport.Detected("database.username", u, source)));
    values.add(
        new InitReport.Detected("database.passwordRef", DEFAULT_DB_PASSWORD_REF, SOURCE_DEFAULT));
    return new Config.Database(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(SecretRef.parse(DEFAULT_DB_PASSWORD_REF)),
        Optional.empty());
  }
}
