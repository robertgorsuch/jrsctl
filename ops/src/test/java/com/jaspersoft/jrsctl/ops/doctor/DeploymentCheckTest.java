package com.jaspersoft.jrsctl.ops.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.UnsupportedDeployment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-0043 (issue #118): doctor names an unsupported deployment, and it alone exits 6. */
class DeploymentCheckTest {

  @TempDir Path tmp;

  private UnsupportedDeployment.Probe host() {
    return new UnsupportedDeployment.Probe(tmp.resolve("root"), Map.of(), true);
  }

  @Test
  void should_pass_on_tomcat_outside_a_container() {
    ReportItem item =
        LocalChecks.deploymentItem(Optional.of(tmp.resolve("jrs")), Optional.empty(), host());

    assertThat(item.name()).isEqualTo(LocalChecks.DEPLOYMENT);
    assertThat(item.status()).isEqualTo(ReportItem.Status.PASS);
  }

  @Test
  void should_fail_on_a_wildfly_home_without_a_tomcat_layout() throws IOException {
    Path wildfly = tmp.resolve("wildfly");
    Files.createDirectories(wildfly.resolve("standalone").resolve("deployments"));

    ReportItem item = LocalChecks.deploymentItem(Optional.of(wildfly), Optional.empty(), host());

    assertThat(item.status()).isEqualTo(ReportItem.Status.FAIL);
    assertThat(item.detail()).contains("JBoss EAP or WildFly");
    assertThat(item.remediation()).contains("init --remote");
  }

  @Test
  void should_fail_inside_a_container_whatever_the_layout() throws IOException {
    Path root = Files.createDirectories(tmp.resolve("root"));
    Files.createFile(root.resolve(".dockerenv"));

    ReportItem item =
        LocalChecks.deploymentItem(Optional.of(tmp.resolve("jrs")), Optional.empty(), host());

    assertThat(item.status()).isEqualTo(ReportItem.Status.FAIL);
    assertThat(item.detail()).contains("container");
  }

  @Test
  void should_exit_6_when_the_deployment_is_the_only_failure() {
    ReportItem deployment = ReportItem.fail(LocalChecks.DEPLOYMENT, "in a container", "x");
    ReportItem compat = ReportItem.fail(DoctorReport.COMPAT, "unsupported", "x");
    ReportItem other = ReportItem.fail("service", "broken", "x");

    assertThat(DoctorReport.of(List.of(deployment)).exitCode()).isEqualTo(6);
    assertThat(DoctorReport.of(List.of(deployment, compat)).exitCode()).isEqualTo(6);
    assertThat(DoctorReport.of(List.of(deployment, other)).exitCode()).isEqualTo(2);
  }
}
