package com.jaspersoft.jrsctl.ops.doctor;

import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code jrsctl doctor} (spec §12.1): runs every check in a fixed order and returns a {@link
 * DoctorReport}. Invariants: the operation mutates nothing but the audit table (one row when {@code
 * --allow-unsupported} is given); the server is contacted through one {@link ServerProbe}, and when
 * it is unreachable every server-dependent check is a SKIP; a check that throws becomes a FAIL
 * naming the exception rather than aborting the report; the report always contains every check name
 * so consumers can key on it.
 */
public final class DoctorOperation {

  /** Check names in report order. */
  public static final List<String> CHECKS =
      List.of(
          "runtime",
          "config",
          "secrets",
          "server",
          "auth",
          "identity",
          "compat",
          "capabilities",
          "cluster",
          LocalChecks.DEPLOYMENT,
          "layout",
          "service",
          LocalChecks.RUNNING_TOMCAT,
          LocalChecks.SERVICE_MANAGER,
          LocalChecks.DATABASE_SERVICE,
          LocalChecks.PID_FILE,
          "permissions",
          "disk",
          "keystore",
          "vendor",
          "vendor-java",
          "tomcat",
          LocalChecks.TELEMETRY,
          LocalChecks.AUDIT,
          "database",
          "state",
          "runs",
          "lock",
          "snapshots",
          "network",
          "elevated");

  private static final Logger LOG = LoggerFactory.getLogger(DoctorOperation.class);

  private final Services services;

  public DoctorOperation(Services services) {
    this.services = Objects.requireNonNull(services, "services");
  }

  public DoctorReport run(DoctorOptions options) {
    Objects.requireNonNull(options, "options");
    if (options.allowUnsupported()) {
      audit("--allow-unsupported", "doctor run with the compat check downgraded to WARN");
    }
    List<ReportItem> items = new ArrayList<>();
    items.add(guard("runtime", LocalChecks::runtime));
    items.add(guard("config", LocalChecks::config));
    items.add(guard("secrets", LocalChecks::secrets));

    ServerProbe probe = ServerProbe.connect(services);
    items.add(probe.item());
    // field test 2, D1: the two items that log in are skipped, saying so, when no password is at
    // hand without a prompt; everything else that needs only the server's identity still runs
    items.add(guard("auth", s -> probe.authenticated("auth", c -> ServerChecks.auth(s, c))));
    items.add(guard("identity", s -> probe.dependent("identity", ServerChecks::identity)));
    items.add(
        guard(
            DoctorReport.COMPAT,
            s ->
                probe.dependent(
                    DoctorReport.COMPAT,
                    c -> ServerChecks.compat(s, c, options.allowUnsupported()))));
    items.add(
        guard(
            "capabilities",
            s -> probe.authenticated("capabilities", c -> ServerChecks.capabilities(s, c))));
    // review §3.2 (issue #112): the licence's clustering flag, read by the same probe
    items.add(guard("cluster", s -> probe.authenticated("cluster", ServerChecks::cluster)));

    // #68: a jrsctl that reaches the server over REST only has no installation to check
    boolean local = services.config().server().namesLocalInstallation();
    Optional<TomcatLayout> layout = LocalChecks.layout(services);
    ReportItem deployment =
        local
            ? guard(LocalChecks.DEPLOYMENT, s -> LocalChecks.deployment(s, layout))
            : remote(LocalChecks.DEPLOYMENT);
    items.add(deployment);
    // ADR-0043: on a JBoss/WildFly home the missing Tomcat layout is the deployment item's finding,
    // not a second one, so a refused deployment alone exits 6
    boolean notTomcat = local && layout.isEmpty() && deployment.status() == ReportItem.Status.FAIL;
    items.add(
        !local
            ? remote("layout")
            : notTomcat
                ? ReportItem.skip("layout", "not a Tomcat deployment", "see the deployment item")
                : guard("layout", s -> LocalChecks.layout(s, layout)));
    items.add(local ? guard("service", LocalChecks::service) : remote("service"));
    items.add(
        local
            ? guard(LocalChecks.RUNNING_TOMCAT, s -> LocalChecks.runningTomcat(s, layout))
            : remote(LocalChecks.RUNNING_TOMCAT));
    items.add(
        local
            ? guard(LocalChecks.SERVICE_MANAGER, LocalChecks::serviceManager)
            : remote(LocalChecks.SERVICE_MANAGER));
    items.add(
        local
            ? guard(LocalChecks.DATABASE_SERVICE, LocalChecks::databaseService)
            : remote(LocalChecks.DATABASE_SERVICE));
    items.add(
        local
            ? guard(LocalChecks.PID_FILE, s -> LocalChecks.pidFile(layout))
            : remote(LocalChecks.PID_FILE));
    items.add(
        local
            ? guard("permissions", s -> LocalChecks.permissions(s, layout))
            : remote("permissions"));
    items.add(guard("disk", LocalChecks::disk));
    items.add(
        local
            // the keystore check asks the server for its capabilities, which logs in
            ? guard(
                "keystore", s -> probe.authenticated("keystore", c -> ServerChecks.keystore(s, c)))
            : remote("keystore"));
    items.add(local ? guard("vendor", LocalChecks::vendor) : remote("vendor"));
    items.add(
        local
            ? guard(
                "vendor-java",
                s -> probe.dependent("vendor-java", c -> ServerChecks.vendorJava(s, c)))
            : remote("vendor-java"));
    items.add(
        local
            ? guard(
                "tomcat", s -> probe.dependent("tomcat", c -> ServerChecks.tomcat(s, c, layout)))
            : remote("tomcat"));
    items.add(
        local
            ? guard(LocalChecks.TELEMETRY, s -> LocalChecks.telemetry(s, layout))
            : remote(LocalChecks.TELEMETRY));
    items.add(
        local
            ? guard(LocalChecks.AUDIT, s -> LocalChecks.audit(layout))
            : remote(LocalChecks.AUDIT));
    items.add(guard("database", DatabaseCheck::check));
    items.add(guard("state", LocalChecks::state));
    items.add(guard("runs", LocalChecks::runs));
    items.add(guard("lock", LocalChecks::lock));
    items.add(guard("snapshots", LocalChecks::snapshots));
    items.add(guard("network", LocalChecks::network));
    items.add(guard("elevated", LocalChecks::elevated));
    return DoctorReport.of(items);
  }

  private static ReportItem remote(String name) {
    return ReportItem.skip(
        name,
        "no local installation configured: this jrsctl reaches the server over REST only",
        "REST export and import work from here; run jrsctl on the server for hotfixes, upgrades"
            + " and vendor tools, or set server.installDir");
  }

  private ReportItem guard(String name, DoctorCheck check) {
    try {
      return check.check(services);
    } catch (RuntimeException e) {
      LOG.debug("doctor check {} threw", name, e);
      return ReportItem.fail(
          name,
          "check threw " + e.getClass().getSimpleName() + ": " + e.getMessage(),
          "see the jrsctl log for the stack trace");
    }
  }

  private void audit(String action, String detail) {
    try {
      services.stateStore().get().audit("operator", action, detail);
    } catch (RuntimeException e) {
      LOG.warn("cannot write audit row for {}: {}", action, e.getMessage());
    }
  }
}
