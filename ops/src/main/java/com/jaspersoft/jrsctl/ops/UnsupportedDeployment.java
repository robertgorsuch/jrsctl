package com.jaspersoft.jrsctl.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Recognises the deployments jrsctl does not support (ADR-0043, spec §19 Q2): JasperReports Server
 * on JBoss EAP or WildFly, and jrsctl running inside a container. Invariants: only Apache Tomcat is
 * supported, so a JBoss/WildFly home (a {@code jboss-modules.jar} or a {@code
 * standalone/deployments} directory at the given directory or up to three levels above it) is
 * refused, but only when the caller found no Tomcat layout there; a container is recognised by the
 * markers Docker, Podman and Kubernetes leave on Linux ({@code /.dockerenv}, {@code
 * /run/.containerenv}, the service-account directory, {@code KUBERNETES_SERVICE_HOST}) and is
 * refused whatever runs in it, because a hotfix or upgrade made inside a running container is lost
 * when its image is rebuilt; nothing here is checked for a remote-only configuration, where REST
 * export and import work against any server; reading the markers never throws.
 */
public final class UnsupportedDeployment {

  /**
   * Where the container markers are looked for, the environment, and whether to look at all:
   * containers are recognised on Linux only, and jrsctl's own test suites turn the check off with
   * {@code -Djrsctl.containerCheck=false} so the build passes inside a container too.
   */
  public record Probe(Path root, Map<String, String> env, boolean checkContainers) {
    public Probe {
      Objects.requireNonNull(root, "root");
      env = Map.copyOf(env);
    }

    /** This host: the file-system root and the process environment. */
    public static Probe host() {
      String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
      boolean check =
          os.contains("linux") && !"false".equals(System.getProperty("jrsctl.containerCheck"));
      return new Probe(Path.of("/"), System.getenv(), check);
    }
  }

  /** Why the deployment is refused, and what the operator can do instead. */
  public record Refusal(String reason, String remediation) {}

  public static final String REMEDIATION =
      "jrsctl manages JasperReports Server on Apache Tomcat only (ADR-0043); REST export and import"
          + " still work against any reachable server from another machine with `jrsctl init"
          + " --remote <url>`";

  private static final int ANCESTOR_LEVELS = 3;

  private UnsupportedDeployment() {}

  /** The container this process runs in, if any, named by the marker that gave it away. */
  public static Optional<Refusal> container(Probe probe) {
    if (!probe.checkContainers()) {
      return Optional.empty();
    }
    Optional<String> marker = Optional.empty();
    if (exists(probe.root().resolve(".dockerenv"))) {
      marker = Optional.of("/.dockerenv (Docker)");
    } else if (exists(probe.root().resolve("run").resolve(".containerenv"))) {
      marker = Optional.of("/run/.containerenv (Podman)");
    } else if (probe.env().containsKey("KUBERNETES_SERVICE_HOST")
        || exists(
            probe
                .root()
                .resolve("var")
                .resolve("run")
                .resolve("secrets")
                .resolve("kubernetes.io")
                .resolve("serviceaccount"))) {
      marker = Optional.of("a Kubernetes pod");
    }
    return marker.map(
        m ->
            new Refusal(
                "jrsctl is running inside a container ("
                    + m
                    + "); a hotfix or upgrade made inside a running container is lost when its"
                    + " image is rebuilt, so it is not supported",
                "apply hotfixes and upgrades when building the JasperReports Server image; "
                    + REMEDIATION));
  }

  /** The JBoss EAP or WildFly home at or above {@code dir}, if that is what it is. */
  public static Optional<Refusal> jboss(Path dir) {
    Path probe = dir.toAbsolutePath().normalize();
    for (int level = 0; level <= ANCESTOR_LEVELS && probe != null; level++) {
      if (exists(probe.resolve("jboss-modules.jar"))
          || Files.isDirectory(probe.resolve("standalone").resolve("deployments"))) {
        return Optional.of(
            new Refusal(
                probe
                    + " is a JBoss EAP or WildFly installation; jrsctl supports JasperReports"
                    + " Server on Apache Tomcat only",
                REMEDIATION));
      }
      probe = probe.getParent();
    }
    return Optional.empty();
  }

  private static boolean exists(Path path) {
    try {
      return Files.exists(path);
    } catch (SecurityException e) {
      return false;
    }
  }
}
