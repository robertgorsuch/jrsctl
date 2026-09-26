package com.jaspersoft.jrsctl.ops.doctor;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.Recovery;
import com.jaspersoft.jrsctl.core.engine.RunLock;
import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.LinuxInit;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.platform.RunningTomcats;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.platform.StalePidFile;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.selfcheck.SelfCheck;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StateStoreException;
import com.jaspersoft.jrsctl.jrs.service.CompanionDatabase;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticResolution;
import com.jaspersoft.jrsctl.ops.JsConfig;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.UnsupportedDeployment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

/**
 * The {@code doctor} checks that need no server connection (spec §12.1). Invariants: every method
 * is read-only; every non-PASS item names the next action; secret values are resolved only to
 * verify that they can be, and are closed immediately without being copied anywhere.
 */
final class LocalChecks {

  static final long GIB = 1L << 30;
  static final long DISK_WARN_BYTES = 5 * GIB;
  static final long DISK_FAIL_BYTES = 1 * GIB;
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

  private LocalChecks() {}

  static ReportItem runtime(Services s) {
    SelfCheck.Report report = new SelfCheck().run();
    String detail =
        report.items().stream()
            .map(i -> i.name() + "=" + i.status())
            .collect(Collectors.joining(", "));
    boolean warn = report.items().stream().anyMatch(i -> i.status() == SelfCheck.Status.WARN);
    if (!report.ok()) {
      String failing =
          report.items().stream()
              .filter(i -> i.status() == SelfCheck.Status.FAIL)
              .map(i -> i.name() + ": " + i.detail())
              .collect(Collectors.joining("; "));
      return ReportItem.fail("runtime", failing, "run jrsctl selfcheck and reinstall the archive");
    }
    return warn
        ? ReportItem.warn("runtime", detail, "run jrsctl selfcheck for details")
        : ReportItem.pass("runtime", detail);
  }

  static ReportItem config(Services s) {
    Path file = s.home().configFile();
    if (!Files.isRegularFile(file)) {
      return ReportItem.warn(
          "config", "no " + file + "; using defaults and JRSCTL_* overrides", "run jrsctl init");
    }
    Optional<java.net.URI> baseUrl = s.config().server().baseUrl();
    if (baseUrl.isEmpty()) {
      return ReportItem.fail(
          "config", file + " is valid but has no server.baseUrl", "run jrsctl init");
    }
    return ReportItem.pass("config", file + " valid; server " + baseUrl.get());
  }

  static ReportItem secrets(Services s) {
    Map<String, SecretRef> refs = configuredRefs(s.config());
    if (refs.isEmpty()) {
      return ReportItem.pass("secrets", "no secret references configured");
    }
    List<String> problems = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    List<String> weak = new ArrayList<>();
    Map<String, Optional<String>> usernames = usernames(s.config());
    for (Map.Entry<String, SecretRef> ref : refs.entrySet()) {
      // field test 2, D1: doctor never prompts, so a reference that would (an enc: entry with no
      // passphrase at hand) or that is simply not supplied yet is a WARN, not a FAIL; a reference
      // that is there but wrong (an unreadable or world-readable file) stays a FAIL
      if (!s.secrets().availableWithoutPrompt(ref.getValue())) {
        unavailable.add(
            ref.getKey() + " (" + ref.getValue().render() + "): " + unavailable(ref.getValue()));
        continue;
      }
      try (Secret secret = s.secrets().resolve(ref.getValue())) {
        // resolvable: file is owner-only, env is set, store unlocks
        if (isGuessable(secret, usernames.getOrDefault(ref.getKey(), Optional.empty()))) {
          weak.add(ref.getKey() + " (" + ref.getValue().render() + ")");
        }
      } catch (SecretException e) {
        problems.add(ref.getKey() + " (" + ref.getValue().render() + "): " + e.getMessage());
      }
    }
    if (!problems.isEmpty()) {
      List<String> all = new ArrayList<>(problems);
      all.addAll(unavailable);
      return ReportItem.fail(
          "secrets",
          String.join("; ", all),
          "fix the reference, restrict the file to its owner, set the variable, or run jrsctl"
              + " secrets set");
    }
    if (!unavailable.isEmpty()) {
      return ReportItem.warn(
          "secrets",
          String.join("; ", unavailable),
          "set the variable, create the file, or unlock secrets.enc with --passphrase-file or"
              + " JRSCTL_PASSPHRASE; a command that needs the secret asks for it or stops");
    }
    if (!weak.isEmpty()) {
      // #158: the redactor masks a secret wherever it occurs, so a common word or a username is
      // masked inside other words and names, and what surrounds the mask gives it away
      return ReportItem.warn(
          "secrets",
          String.join(", ", weak)
              + ": equals its username or a vendor installer default, so redaction cannot hide it;"
              + " logs and support bundles mask the word wherever it appears, which makes them"
              + " hard to read and shows what it is",
          "change the password on the server or database, then store the new one with jrsctl"
              + " config set <key>");
    }
    return ReportItem.pass(
        "secrets", refs.size() + " reference(s) resolvable: " + String.join(", ", refs.keySet()));
  }

  /**
   * Passwords the vendor's installers and samples set by default (installation guide: the admin
   * accounts, the sample user, the bundled PostgreSQL), compared without regard to case.
   */
  private static final List<String> INSTALLER_DEFAULTS =
      List.of("jasperadmin", "superuser", "joeuser", "demo", "postgres");

  private static Map<String, Optional<String>> usernames(Config c) {
    return Map.of(
        "server.auth.passwordRef", c.server().auth().username(),
        "database.passwordRef", c.database().username(),
        "network.proxy.passwordRef", c.network().proxy().username());
  }

  /**
   * Whether the secret equals its username or an installer default, ignoring case. Compared on a
   * copy of the characters that is zeroed afterwards; no String of the secret is made.
   */
  static boolean isGuessable(Secret secret, Optional<String> username) {
    char[] value = secret.chars();
    try {
      if (username.isPresent() && equalsIgnoreCase(value, username.get())) {
        return true;
      }
      for (String known : INSTALLER_DEFAULTS) {
        if (equalsIgnoreCase(value, known)) {
          return true;
        }
      }
      return false;
    } finally {
      java.util.Arrays.fill(value, '\0');
    }
  }

  private static boolean equalsIgnoreCase(char[] value, String text) {
    if (value.length != text.length()) {
      return false;
    }
    for (int i = 0; i < value.length; i++) {
      if (Character.toLowerCase(value[i]) != Character.toLowerCase(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static String unavailable(SecretRef ref) {
    return switch (ref) {
      case SecretRef.Env e -> "environment variable " + e.name() + " is not set";
      case SecretRef.File f -> "secret file " + f.path() + " does not exist";
      case SecretRef.Enc c -> "secrets.enc is not unlocked (no passphrase without a prompt)";
    };
  }

  static Map<String, SecretRef> configuredRefs(Config c) {
    Map<String, SecretRef> refs = new LinkedHashMap<>();
    c.server().auth().passwordRef().ifPresent(r -> refs.put("server.auth.passwordRef", r));
    c.database().passwordRef().ifPresent(r -> refs.put("database.passwordRef", r));
    c.network().proxy().passwordRef().ifPresent(r -> refs.put("network.proxy.passwordRef", r));
    c.network()
        .trustStore()
        .passwordRef()
        .ifPresent(r -> refs.put("network.trustStore.passwordRef", r));
    return refs;
  }

  /** Report name of the deployment check (ADR-0043). */
  static final String DEPLOYMENT = DoctorReport.DEPLOYMENT;

  /**
   * ADR-0043: jrsctl manages JasperReports Server on Tomcat, outside containers. A JBoss EAP or
   * WildFly home is judged only when no Tomcat layout was found there.
   */
  static ReportItem deployment(Services s, Optional<TomcatLayout> layout) {
    return deploymentItem(
        s.config().server().installDir(), layout, UnsupportedDeployment.Probe.host());
  }

  static ReportItem deploymentItem(
      Optional<Path> installDir, Optional<TomcatLayout> layout, UnsupportedDeployment.Probe probe) {
    Optional<UnsupportedDeployment.Refusal> refused =
        UnsupportedDeployment.container(probe)
            .or(
                () ->
                    layout.isPresent()
                        ? Optional.empty()
                        : installDir.flatMap(UnsupportedDeployment::jboss));
    return refused
        .map(r -> ReportItem.fail(DEPLOYMENT, r.reason(), r.remediation()))
        .orElseGet(() -> ReportItem.pass(DEPLOYMENT, "Apache Tomcat, not inside a container"));
  }

  static Optional<TomcatLayout> layout(Services s) {
    return s.config().server().installDir().flatMap(d -> s.platform().detectTomcat(d));
  }

  static ReportItem layout(Services s, Optional<TomcatLayout> layout) {
    Optional<Path> installDir = s.config().server().installDir();
    if (installDir.isEmpty()) {
      return ReportItem.fail("layout", "server.installDir is not configured", "run jrsctl init");
    }
    if (layout.isEmpty()) {
      return ReportItem.fail(
          "layout",
          "no Tomcat layout (tomcat dir with webapps/jasperserver[-pro]) under " + installDir.get(),
          "point server.installDir at the JasperReports Server installation root");
    }
    TomcatLayout l = layout.get();
    String webapp = s.config().server().webappName().map(Config.WebappName::yamlValue).orElse("");
    if (!webapp.isEmpty() && !webapp.equals(l.webappName())) {
      return ReportItem.fail(
          "layout",
          "server.webappName is " + webapp + " but the layout has " + l.webappName(),
          "set server.webappName to " + l.webappName());
    }
    return ReportItem.pass(
        "layout",
        "tomcat "
            + l.tomcatDir()
            + ", webapp "
            + l.webappName()
            + l.httpPort().map(p -> ", http port " + p).orElse(""));
  }

  static ReportItem service(Services s) {
    Optional<ServiceConfig.Kind> kind = s.config().service().kind();
    if (kind.isEmpty()) {
      return ReportItem.fail("service", "service.kind is not configured", "run jrsctl init");
    }
    if (kind.get() == ServiceConfig.Kind.MANUAL) {
      return ReportItem.warn(
          "service",
          "service.kind is manual: stop/start are operator instructions",
          "manual kind needs an interactive session; configure a service or script kind to"
              + " automate it");
    }
    try {
      ServiceController controller = s.platform().services(s.config().toServiceConfig());
      ServiceController.State state = controller.state();
      String detail = controller.describe() + " is " + state;
      return state == ServiceController.State.UNKNOWN
          ? ReportItem.warn(
              "service",
              detail,
              "check service.name / service.scriptPath and that jrsctl may query it")
          : ReportItem.pass("service", detail);
    } catch (RuntimeException e) {
      return ReportItem.fail(
          "service",
          "cannot query the service: " + e.getMessage(),
          "check service.kind, service.name and service.scriptPath in config.yaml");
    }
  }

  /** Report name of the running-Tomcat check (issue #147). */
  static final String RUNNING_TOMCAT = "running-tomcat";

  /**
   * Whether a Tomcat under the installation runs, from the process list rather than from the
   * configured service (issue #147), so a Tomcat that a customer-named service or a bare {@code
   * startup} script started is seen even when {@code service.kind} cannot stop it.
   */
  static ReportItem runningTomcat(Services s, Optional<TomcatLayout> layout) {
    if (layout.isEmpty()) {
      return ReportItem.skip(RUNNING_TOMCAT, "no install layout", "fix the layout check first");
    }
    Path tomcatDir = layout.get().tomcatDir();
    Optional<ServiceConfig.Kind> kind = s.config().service().kind();
    Optional<ServiceController.State> state = Optional.empty();
    if (kind.isPresent() && kind.get() != ServiceConfig.Kind.MANUAL) {
      try {
        state = Optional.of(s.platform().services(s.config().toServiceConfig()).state());
      } catch (RuntimeException e) {
        // the service item reports why the controller cannot be queried
      }
    }
    return runningTomcatItem(tomcatDir, s.platform().runningTomcats(tomcatDir), kind, state);
  }

  static ReportItem runningTomcatItem(
      Path tomcatDir,
      RunningTomcats scan,
      Optional<ServiceConfig.Kind> kind,
      Optional<ServiceController.State> serviceState) {
    return switch (scan) {
      case RunningTomcats.Unavailable u ->
          ReportItem.skip(
              RUNNING_TOMCAT,
              u.reason(),
              "run jrsctl as the account that runs Tomcat, or elevated");
      case RunningTomcats.Scanned found when !found.pids().isEmpty() -> {
        String running = "Tomcat under " + tomcatDir + " is running (" + pids(found.pids()) + ")";
        if (serviceState.filter(st -> st == ServiceController.State.STOPPED).isPresent()) {
          yield ReportItem.warn(
              RUNNING_TOMCAT,
              running + " but service." + kindName(kind) + " reports STOPPED",
              "the configured service does not run this Tomcat, so a stop through it would leave"
                  + " the server running: set service.name to the service that does, or, when"
                  + " Tomcat is started by hand with startup, set service.kind to catalina with"
                  + " service.scriptPath at its bin/catalina script, or to manual");
        }
        yield ReportItem.pass(RUNNING_TOMCAT, running);
      }
      case RunningTomcats.Scanned found when !found.unreadable().isEmpty() -> {
        int count = found.unreadable().size();
        String unreadable =
            (count == 1 ? "1 process" : count + " processes")
                + " ("
                + pids(found.unreadable())
                + ") whose command line this account cannot read";
        if (serviceState.filter(st -> st == ServiceController.State.RUNNING).isPresent()) {
          // the installer's LocalSystem service seen without elevation: consistent, not a conflict
          yield ReportItem.pass(
              RUNNING_TOMCAT,
              "service."
                  + kindName(kind)
                  + " reports RUNNING; "
                  + unreadable
                  + " may be its Tomcat");
        }
        yield ReportItem.warn(
            RUNNING_TOMCAT,
            "no readable Tomcat under "
                + tomcatDir
                + " is running, but "
                + unreadable
                + " may be it",
            "run jrsctl as the account that runs Tomcat, or elevated, to see it");
      }
      case RunningTomcats.Scanned found -> {
        String none = "no Tomcat under " + tomcatDir + " is running";
        if (serviceState.filter(st -> st == ServiceController.State.RUNNING).isPresent()) {
          yield ReportItem.warn(
              RUNNING_TOMCAT,
              "service." + kindName(kind) + " reports RUNNING but " + none,
              "the configured service may run another Tomcat: check that service.name names"
                  + " the service of this installation");
        }
        yield ReportItem.pass(RUNNING_TOMCAT, none);
      }
    };
  }

  private static String pids(List<Long> pids) {
    return pids.stream().map(p -> "pid " + p).collect(Collectors.joining(", "));
  }

  private static String kindName(Optional<ServiceConfig.Kind> kind) {
    return "kind " + kind.map(Config.Service::kindToYaml).orElse("(not configured)");
  }

  /** Report name of the service-manager check (review 3.2). */
  static final String SERVICE_MANAGER = "service-manager";

  /**
   * What supervises services on this host (review 3.2). A Linux box whose process 1 is not systemd
   * is named, so an operator does not discover during a hotfix that the supervisor restarted the
   * server behind a {@code catalina.sh} stop.
   */
  static ReportItem serviceManager(Services s) {
    // the check is about the machine doctor runs on, not the platform the configuration names
    if (Platforms.osFamily(System.getProperty("os.name", "")).orElse(null)
        == Platform.OsFamily.WINDOWS) {
      return ReportItem.pass(SERVICE_MANAGER, "Windows service control manager");
    }
    return serviceManagerItem(s.config().service().kind(), LinuxInit.detect());
  }

  static ReportItem serviceManagerItem(Optional<ServiceConfig.Kind> kind, LinuxInit.Detected init) {
    if (init.systemd()) {
      return ReportItem.pass(SERVICE_MANAGER, init.detail());
    }
    if (init.kind() == LinuxInit.Kind.UNKNOWN) {
      return ReportItem.warn(
          SERVICE_MANAGER,
          init.detail(),
          "confirm how this host starts Tomcat; service.kind is taken on trust while process 1"
              + " cannot be read");
    }
    if (kind.filter(k -> k == ServiceConfig.Kind.SYSTEMD).isPresent()) {
      return ReportItem.fail(
          SERVICE_MANAGER,
          "service.kind is systemd but " + init.detail(),
          "set service.kind to the way this host really starts Tomcat, or to manual");
    }
    if (init.supervised()) {
      return ReportItem.warn(
          SERVICE_MANAGER,
          init.detail(),
          "a script stop bypasses the supervisor, which may restart the server mid-run; stop the"
              + " server through its supervisor or set service.kind to manual");
    }
    return ReportItem.warn(
        SERVICE_MANAGER,
        init.detail(),
        "confirm how this host starts Tomcat; with no service manager a script stop is the only"
            + " way and nothing will restart the server for you");
  }

  static ReportItem permissions(Services s, Optional<TomcatLayout> layout) {
    if (layout.isEmpty()) {
      return ReportItem.skip("permissions", "no install layout", "fix the layout check first");
    }
    List<Path> targets = new ArrayList<>();
    targets.add(layout.get().webInfLib());
    targets.add(layout.get().webInfClasses());
    new BuildomaticLocator(s.platform())
        .resolve(s.config())
        .located()
        .map(Buildomatic::dir)
        .ifPresent(targets::add);
    FileOps files = s.platform().files();
    List<String> readOnly = new ArrayList<>();
    for (Path target : targets) {
      if (!Files.isDirectory(target)) {
        readOnly.add(target + " (missing)");
        continue;
      }
      // The probe creates and deletes a file. Done inside WEB-INF/lib or WEB-INF/classes it is a
      // write into the live webapp that a reloadable context reacts to, and doctor is read-only
      // (spec §0); WEB-INF itself carries the same permissions and is not watched (item P7).
      Path probeDir = layout.get().requiresServiceStop(target) ? target.getParent() : target;
      if (!files.isWritable(probeDir)) {
        readOnly.add(target.toString());
      }
    }
    if (!readOnly.isEmpty()) {
      return ReportItem.fail(
          "permissions",
          "not writable: " + String.join(", ", readOnly),
          "run jrsctl as the account that owns the installation or grant it write access");
    }
    return ReportItem.pass("permissions", targets.size() + " target dirs writable");
  }

  /**
   * Field test 2, U3: the jrsctl home holds every backup and the state, and it need not share a
   * volume with the installation (the tester's 800 MB /home held it), so both are measured and the
   * smaller decides.
   */
  static ReportItem disk(Services s) {
    Path home = s.home().root();
    Optional<Path> install =
        s.config().server().installDir().filter(Files::exists).filter(p -> !p.equals(home));
    long homeFree;
    try {
      homeFree = s.platform().files().freeSpaceBytes(home);
    } catch (IOException e) {
      return ReportItem.fail(
          "disk", "cannot read free space of " + home + ": " + e.getMessage(), "check the path");
    }
    long least = homeFree;
    long installFreeSeen = Long.MAX_VALUE;
    StringBuilder detail =
        new StringBuilder(human(homeFree) + " free under " + home + " (backups and state)");
    if (install.isPresent()) {
      try {
        long installFree = s.platform().files().freeSpaceBytes(install.get());
        installFreeSeen = installFree;
        least = Math.min(least, installFree);
        detail
            .append(", ")
            .append(human(installFree))
            .append(" free under ")
            .append(install.get())
            .append(" (installation)");
      } catch (IOException e) {
        detail.append(", free space under ").append(install.get()).append(" unknown");
      }
    }
    try {
      detail.append("; snapshots use ").append(human(snapshotStore(s).totalBytes()));
    } catch (IOException | RuntimeException e) {
      detail.append("; snapshot size unknown");
    }
    // field test 3: say which volume is short and give the advice that fits it; moving the
    // jrsctl home does nothing for a full installation volume
    boolean installShort = installFreeSeen < homeFree;
    String where =
        installShort
            ? "the installation's volume ("
                + install.map(Path::toString).orElse("")
                + ") has the least room; free space there, since hotfixes and upgrades write"
                + " into it"
            : "the jrsctl home's volume ("
                + home
                + ") has the least room: remove old snapshots with `jrsctl runs prune`, or move"
                + " the home to a bigger volume with `jrsctl home set <dir>`";
    if (least < DISK_FAIL_BYTES) {
      return ReportItem.fail(
          "disk", detail.toString(), "at least 1 GB is needed (5 GB recommended); " + where);
    }
    if (least < DISK_WARN_BYTES) {
      return ReportItem.warn(
          "disk", detail.toString(), "5 GB recommended for backups and staging; " + where);
    }
    return ReportItem.pass("disk", detail.toString());
  }

  /**
   * The vendor scripts in the buildomatic directory {@link BuildomaticLocator#resolve} settles on,
   * which need not be under the install directory (ADR-0013). Skipped only when nothing that could
   * locate it is configured; an unreachable configured directory or an ambiguous search fails.
   */
  static ReportItem vendor(Services s) {
    return vendor(s, System.getenv());
  }

  /** As {@link #vendor(Services)}, with {@code env} as the environment the vendor tools inherit. */
  static ReportItem vendor(Services s, Map<String, String> env) {
    BuildomaticLocator locator = new BuildomaticLocator(s.platform());
    return switch (locator.resolve(s.config())) {
      case BuildomaticResolution.NotFound missing ->
          missing.reason() == BuildomaticResolution.Reason.NOT_CONFIGURED
              ? ReportItem.skip("vendor", missing.detail(), missing.remediation())
              : ReportItem.fail("vendor", missing.detail(), missing.remediation());
      case BuildomaticResolution.Found found -> vendorScripts(s, locator, found, env);
    };
  }

  private static ReportItem vendorScripts(
      Services s,
      BuildomaticLocator locator,
      BuildomaticResolution.Found found,
      Map<String, String> env) {
    Buildomatic buildomatic = found.buildomatic();
    String ext = locator.scriptExtension();
    List<String> missing = buildomatic.missingScripts().stream().map(n -> n + ext).toList();
    String where = buildomatic.dir() + " (" + found.source() + ")";
    if (!missing.isEmpty()) {
      return ReportItem.fail(
          "vendor",
          "missing in " + where + ": " + String.join(", ", missing),
          "restore the vendor scripts; export/import and upgrade need them");
    }
    if (s.platform().os() == Platform.OsFamily.WINDOWS
        && BuildomaticLocator.isUncPath(buildomatic.dir())) {
      return ReportItem.warn(
          "vendor",
          "js-export, js-import, js-ant present in "
              + where
              + ", but cmd.exe cannot use a UNC path as the working directory of the batch"
              + " wrappers",
          "map the share to a drive letter for the account that runs jrsctl, or link it with"
              + " mklink /D (which needs Administrator or Developer Mode), and set"
              + " server.buildomaticDir to that path");
    }
    Optional<Path> ant = locator.ant(buildomatic, env);
    if (ant.isEmpty()) {
      return ReportItem.warn(
          "vendor",
          "js-export, js-import, js-ant present in "
              + where
              + ", but they will find no Ant: there is no apache-ant next to "
              + buildomatic.dir()
              + " and no ant on PATH",
          "copy the distribution's apache-ant folder next to the buildomatic directory, or put"
              + " Ant's bin directory on PATH for the account that runs jrsctl");
    }
    return ReportItem.pass(
        "vendor", "js-export, js-import, js-ant present in " + where + "; Ant " + ant.get());
  }

  /**
   * {@code PRAGMA quick_check} on {@code state.db} (review finding 1.18). The store refuses to open
   * a damaged file, so the failure surfaces here with the way out rather than in the first check
   * that needs the store.
   */
  static ReportItem state(Services s) {
    Path db = s.home().stateDb();
    if (!Files.exists(db)) {
      return ReportItem.pass("state", "no state.db yet; created on first use");
    }
    try {
      StateStore store = s.stateStore().get();
      String check = store.integrity();
      if ("ok".equals(check)) {
        return ReportItem.pass(
            "state", "quick_check ok, schema v" + store.schemaVersion() + ", " + db);
      }
      return ReportItem.fail(
          "state", "quick_check: " + check, StateStore.corruptionRemediation(db));
    } catch (StateStoreException e) {
      return ReportItem.fail(
          "state", "cannot open state.db: " + e.getMessage(), StateStore.corruptionRemediation(db));
    }
  }

  static ReportItem runs(Services s) {
    List<RunRecord> pending = Recovery.pendingRuns(s.stateStore().get());
    if (pending.isEmpty()) {
      return ReportItem.pass("runs", "no pending runs");
    }
    String ids = pending.stream().map(RunRecord::runId).collect(Collectors.joining(", "));
    return ReportItem.fail(
        "runs",
        pending.size() + " pending run(s): " + ids,
        "run jrsctl runs recover " + pending.get(0).runId() + " --resume or --rollback");
  }

  /**
   * Judged by trying the lock, not by the pid text a crashed process left behind (review 5.4,
   * assessment item E8); a lock this process holds is answered without opening the file (E1).
   */
  static ReportItem lock(Services s) {
    Optional<RunLock.Holder> holder = RunLock.heldBy(s.home().runLock());
    if (holder.isEmpty()) {
      return ReportItem.pass("lock", "run lock free");
    }
    return ReportItem.fail(
        "lock",
        "held by run " + holder.get().runId() + " (pid " + holder.get().pid() + ")",
        "wait for that jrsctl process to finish");
  }

  static ReportItem snapshots(Services s) {
    try {
      SnapshotStore store = snapshotStore(s);
      int count = store.list().size();
      return ReportItem.pass("snapshots", count + " snapshot(s), " + human(store.totalBytes()));
    } catch (IOException e) {
      return ReportItem.fail(
          "snapshots",
          "snapshot store unreadable: " + e.getMessage(),
          "inspect " + s.home().snapshots() + " and remove corrupt entries");
    }
  }

  static ReportItem network(Services s) {
    Config.Network network = s.config().network();
    boolean proxy = network.proxy().host().isPresent();
    if (network.mode() == Config.NetworkMode.ISOLATED && proxy) {
      return ReportItem.warn(
          "network",
          "network.mode is isolated but a proxy is configured; the proxy is never used",
          "remove network.proxy or set network.mode to public");
    }
    return ReportItem.pass(
        "network", "mode " + network.mode().yamlValue() + (proxy ? ", proxy configured" : ""));
  }

  static ReportItem elevated(Services s) {
    Platform platform = s.platform();
    boolean elevated =
        switch (platform.os()) {
          case LINUX -> "root".equals(System.getProperty("user.name", ""));
          case WINDOWS -> windowsElevated(platform.processes());
        };
    if (elevated) {
      return ReportItem.warn(
          "elevated",
          "running elevated; doctor needs no elevation",
          "run jrsctl as the account that owns the installation unless a step requires more");
    }
    return ReportItem.pass("elevated", "not running elevated");
  }

  /**
   * Whether this Windows process holds an elevated (administrator) token, judged by {@code fltmc}:
   * the Filter Manager console answers with exit code 0 to an elevated caller and "access denied"
   * otherwise, in about 80 ms. It replaces {@code whoami /groups}, which resolves every group of
   * the user against the domain and took 2.7 to 4.2 seconds on a domain-joined machine, most of
   * what {@code doctor} took (measured 2026-09-19). A probe that cannot run, times out or answers
   * anything but success counts as not elevated, as before.
   */
  static boolean windowsElevated(ProcessRunner runner) {
    try {
      return runner
          .run(
              new ProcessRunner.Request(
                  List.of("fltmc"), Optional.empty(), Map.of(), PROBE_TIMEOUT),
              line -> {})
          .ok();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static SnapshotStore snapshotStore(Services s) {
    return new SnapshotStore(s.home(), s.platform().files(), s.clock());
  }

  static String human(long bytes) {
    if (bytes >= GIB) {
      return String.format(Locale.ROOT, "%.1f GB", bytes / (double) GIB);
    }
    long mib = 1L << 20;
    if (bytes >= mib) {
      return String.format(Locale.ROOT, "%.1f MB", bytes / (double) mib);
    }
    return bytes + " B";
  }

  // ---------------------------------------------------------------- vendor-backed items (#113)

  static final String TELEMETRY = "telemetry";
  static final String AUDIT = "audit";
  static final String DATABASE_SERVICE = "database-service";
  static final String PID_FILE = "pid-file";

  private static final String NO_JS_CONFIG_REMEDIATION =
      "nothing to do now; the switch arrives with a cumulative hotfix, so run doctor again after"
          + " the next hotfix apply";

  /**
   * {@code heartbeat.enabled} uploads usage telemetry to the vendor (administrator guide); it is a
   * WARN because an isolated host must not call out, and because a cumulative hotfix can set it.
   */
  static ReportItem telemetry(Services s, Optional<TomcatLayout> layout) {
    if (layout.isEmpty()) {
      return ReportItem.skip(TELEMETRY, "no install layout", "fix the layout check first");
    }
    boolean isolated = s.config().network().mode() == Config.NetworkMode.ISOLATED;
    return telemetryItem(
        JsConfig.read(layout.get().webappDir()), isolated, layout.get().webappDir());
  }

  static ReportItem telemetryItem(Optional<JsConfig> cfg, boolean isolated, Path webappDir) {
    if (cfg.isEmpty()) {
      return ReportItem.skip(
          TELEMETRY, "no " + JsConfig.RELATIVE + " under " + webappDir, NO_JS_CONFIG_REMEDIATION);
    }
    Optional<Boolean> on = cfg.get().flag(JsConfig.HEARTBEAT);
    if (on.isEmpty()) {
      return ReportItem.pass(
          TELEMETRY,
          JsConfig.HEARTBEAT + " is not set in " + cfg.get().file() + "; no telemetry upload");
    }
    if (on.get()) {
      return ReportItem.warn(
          TELEMETRY,
          JsConfig.HEARTBEAT
              + "=true in "
              + cfg.get().file()
              + ": the server uploads usage telemetry to the vendor"
              + (isolated ? ", and network.mode is isolated" : ""),
          "set "
              + JsConfig.HEARTBEAT
              + "=false in "
              + JsConfig.RELATIVE
              + " and restart the server if this host must not call out (administrator guide); a"
              + " cumulative hotfix can set it again, so run doctor after every hotfix apply");
    }
    return ReportItem.pass(TELEMETRY, JsConfig.HEARTBEAT + "=false in " + cfg.get().file());
  }

  /**
   * {@code feature.audit_monitoring.enabled} decides whether the events table grows: an export that
   * includes events can then be very large (administrator guide pp.250, 416), and a newdb upgrade
   * has events to lose.
   */
  static ReportItem audit(Optional<TomcatLayout> layout) {
    if (layout.isEmpty()) {
      return ReportItem.skip(AUDIT, "no install layout", "fix the layout check first");
    }
    return auditItem(JsConfig.read(layout.get().webappDir()), layout.get().webappDir());
  }

  static ReportItem auditItem(Optional<JsConfig> cfg, Path webappDir) {
    if (cfg.isEmpty()) {
      return ReportItem.skip(
          AUDIT, "no " + JsConfig.RELATIVE + " under " + webappDir, NO_JS_CONFIG_REMEDIATION);
    }
    Optional<Boolean> on = cfg.get().flag(JsConfig.AUDIT);
    if (on.isEmpty()) {
      return ReportItem.pass(
          AUDIT,
          JsConfig.AUDIT
              + " is not set in "
              + cfg.get().file()
              + "; audit and monitoring events are not collected");
    }
    if (on.get()) {
      return ReportItem.warn(
          AUDIT,
          JsConfig.AUDIT
              + "=true in "
              + cfg.get().file()
              + ": audit and monitoring events are collected, so an export that includes them can"
              + " be very large (administrator guide pp.250, 416) and a newdb upgrade loses them"
              + " unless --include-events is given",
          "size the export volume for the events table, or leave events out of the exports and"
              + " upgrades that do not need them (upgrade --include-events carries them)");
    }
    return ReportItem.pass(
        AUDIT, JsConfig.AUDIT + "=false in " + cfg.get().file() + "; event exports stay small");
  }

  /**
   * The bundled PostgreSQL service the installer registers beside the Tomcat one; the vendor starts
   * the database first (installation guide p.51), and so does jrsctl's start-service step.
   */
  static ReportItem databaseService(Services s) {
    Optional<ServiceConfig.Kind> kind = s.config().service().kind();
    if (kind.isEmpty()) {
      return ReportItem.skip(DATABASE_SERVICE, "service.kind is not configured", "run jrsctl init");
    }
    Optional<ServiceController> companion;
    try {
      companion = CompanionDatabase.controller(s.platform(), s.config().toServiceConfig());
    } catch (RuntimeException e) {
      return ReportItem.warn(
          DATABASE_SERVICE,
          "cannot look for the bundled database service: " + e.getMessage(),
          "check service.* in config.yaml");
    }
    return databaseServiceItem(kind.get(), companion);
  }

  static ReportItem databaseServiceItem(
      ServiceConfig.Kind kind, Optional<ServiceController> companion) {
    switch (kind) {
      case CTLSCRIPT -> {
        return ReportItem.pass(
            DATABASE_SERVICE, "not applicable: ctlscript.sh starts the bundled database itself");
      }
      case CATALINA, MANUAL -> {
        return ReportItem.pass(
            DATABASE_SERVICE,
            "not applicable: service.kind "
                + kind.name().toLowerCase(Locale.ROOT)
                + " has no service manager to register a database service with");
      }
      case WINDOWS_SERVICE, SYSTEMD -> {}
    }
    if (companion.isEmpty()) {
      return ReportItem.pass(
          DATABASE_SERVICE,
          "no bundled PostgreSQL service registered beside the Tomcat service; the repository"
              + " database is managed elsewhere");
    }
    ServiceController.State state = companion.get().state();
    if (state == ServiceController.State.RUNNING) {
      return ReportItem.pass(
          DATABASE_SERVICE,
          companion.get().describe() + " is RUNNING; " + CompanionDatabase.VENDOR_NOTE);
    }
    return ReportItem.warn(
        DATABASE_SERVICE,
        companion.get().describe()
            + " is "
            + state
            + "; Tomcat cannot reach the repository without it",
        "start it before Tomcat ("
            + CompanionDatabase.VENDOR_NOTE
            + "); jrsctl's start-service step starts it first");
  }

  /**
   * A {@code catalina.pid} naming a process that is gone makes {@code catalina.sh start} refuse
   * (installation guide p.237); the start-service step removes such a file, doctor only names it.
   */
  static ReportItem pidFile(Optional<TomcatLayout> layout) {
    if (layout.isEmpty()) {
      return ReportItem.skip(PID_FILE, "no install layout", "fix the layout check first");
    }
    return pidFileItem(layout.get().tomcatDir(), StalePidFile.LIVE_PROCESSES);
  }

  static ReportItem pidFileItem(Path tomcatDir, LongPredicate alive) {
    Optional<StalePidFile.Named> named = StalePidFile.find(tomcatDir);
    if (named.isEmpty()) {
      return ReportItem.pass(PID_FILE, "no " + tomcatDir.resolve(StalePidFile.RELATIVE));
    }
    StalePidFile.Named n = named.get();
    if (!n.stale(alive)) {
      return ReportItem.pass(PID_FILE, n.file() + " names running process " + n.pid().get());
    }
    return ReportItem.warn(
        PID_FILE,
        n.file()
            + " names "
            + n.pid().map(p -> "process " + p + ", which is not running").orElse("no process")
            + "; catalina.sh refuses to start while it exists (installation guide p.237)",
        "delete the file before starting the server by hand; jrsctl's start-service step removes a"
            + " stale one itself");
  }
}
