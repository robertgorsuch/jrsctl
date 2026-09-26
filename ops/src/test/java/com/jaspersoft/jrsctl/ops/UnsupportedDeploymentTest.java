package com.jaspersoft.jrsctl.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #118, ADR-0043: jrsctl manages JasperReports Server on Tomcat, outside containers. */
class UnsupportedDeploymentTest {

  @TempDir Path root;

  private UnsupportedDeployment.Probe linux(Map<String, String> env) {
    return new UnsupportedDeployment.Probe(root, env, true);
  }

  @Test
  void should_refuse_inside_docker_podman_and_kubernetes() throws IOException {
    assertThat(UnsupportedDeployment.container(linux(Map.of()))).isEmpty();

    Files.createFile(root.resolve(".dockerenv"));
    assertThat(UnsupportedDeployment.container(linux(Map.of())))
        .hasValueSatisfying(
            r -> {
              assertThat(r.reason()).contains("container").contains("Docker");
              assertThat(r.remediation()).contains("image").contains("init --remote");
            });

    Files.delete(root.resolve(".dockerenv"));
    Files.createDirectories(root.resolve("run"));
    Files.createFile(root.resolve("run").resolve(".containerenv"));
    assertThat(UnsupportedDeployment.container(linux(Map.of())))
        .hasValueSatisfying(r -> assertThat(r.reason()).contains("Podman"));
  }

  @Test
  void should_refuse_in_a_kubernetes_pod_by_environment_or_service_account() throws IOException {
    assertThat(
            UnsupportedDeployment.container(linux(Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1"))))
        .hasValueSatisfying(r -> assertThat(r.reason()).contains("Kubernetes"));

    Files.createDirectories(root.resolve("var/run/secrets/kubernetes.io/serviceaccount"));
    assertThat(UnsupportedDeployment.container(linux(Map.of())))
        .hasValueSatisfying(r -> assertThat(r.reason()).contains("Kubernetes"));
  }

  @Test
  void should_not_look_for_container_markers_when_the_check_is_off() throws IOException {
    Files.createFile(root.resolve(".dockerenv"));

    assertThat(
            UnsupportedDeployment.container(
                new UnsupportedDeployment.Probe(
                    root, Map.of("KUBERNETES_SERVICE_HOST", "x"), false)))
        .isEmpty();
  }

  @Test
  void should_recognise_a_jboss_or_wildfly_home_at_or_above_the_directory() throws IOException {
    Path wildfly = root.resolve("wildfly-36");
    Path deployments =
        Files.createDirectories(wildfly.resolve("standalone").resolve("deployments"));
    Path war = Files.createDirectories(deployments.resolve("jasperserver-pro.war"));

    assertThat(UnsupportedDeployment.jboss(wildfly)).isPresent();
    assertThat(UnsupportedDeployment.jboss(war))
        .hasValueSatisfying(
            r -> {
              assertThat(r.reason()).contains(wildfly.toString()).contains("Tomcat only");
              assertThat(r.remediation()).contains("ADR-0043");
            });

    Path eap = Files.createDirectories(root.resolve("jboss-eap-8"));
    Files.createFile(eap.resolve("jboss-modules.jar"));
    assertThat(UnsupportedDeployment.jboss(eap)).isPresent();
  }

  @Test
  void should_not_call_a_tomcat_tree_jboss() throws IOException {
    Path tomcat =
        Files.createDirectories(root.resolve("jrs").resolve("apache-tomcat").resolve("webapps"));

    assertThat(UnsupportedDeployment.jboss(tomcat.getParent())).isEmpty();
  }

  /** The build sets -Djrsctl.containerCheck=false so it passes inside a container too. */
  @Test
  void should_run_this_suite_with_the_container_check_off() {
    assertThat(UnsupportedDeployment.Probe.host().checkContainers()).isFalse();
  }
}
