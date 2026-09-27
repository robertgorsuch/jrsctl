package com.jaspersoft.jrsctl.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Spec §14 Phase 0: multi-module build, CLAUDE.md, {@code --version}, {@code selfcheck}, CI. */
@Tag("phase0")
class Phase0SkeletonTest {

  private final Cli cli = new Cli();

  @Test
  void version_prints_product_and_vendor() throws Exception {
    Cli.Result r = cli.run("--version").assertExit(0);
    assertThat(r.stdout()).contains("jrsctl " + System.getProperty("jrsctl.version"));
    assertThat(r.stdout()).contains("Jaspersoft").doesNotContain("Actian");
  }

  @Test
  void selfcheck_passes_on_the_packaged_jar() throws Exception {
    Cli.Result r = cli.run("selfcheck").assertExit(0);
    assertThat(r.stdout()).contains("PASS").contains("selfcheck ok").doesNotContain("FAIL");
  }

  @Test
  void selfcheck_json_is_machine_readable() throws Exception {
    Cli.Result r = cli.run("selfcheck", "--json").assertExit(0);
    assertThat(r.stdout().trim()).startsWith("{").contains("\"items\"");
  }

  @Test
  void packaged_jar_carries_no_web_console() throws Exception {
    Path jar = Path.of(System.getProperty("jrsctl.jar"));
    try (JarFile packaged = new JarFile(jar.toFile())) {
      assertThat(packaged.stream().map(JarEntry::getName))
          .as("ADR-0038: no static UI, no Javalin, no Jetty, no Kotlin in the jar")
          .noneMatch(
              n ->
                  n.startsWith("web/")
                      || n.startsWith("io/javalin/")
                      || n.startsWith("org/eclipse/jetty/")
                      || n.startsWith("kotlin/"));
    }
  }

  /**
   * The release-gate suites ({@code needs-jrs}, {@code needs-docker}; spec §0 rule 10) are excluded
   * from the default build through surefire's {@code excludedGroups}. surefire 3.6.0 stopped
   * honouring it on the JUnit Platform (apache/maven-surefire#3468) and the live-server suite ran
   * in CI trying to pull a Docker image, so this canary reads the unit-test reports the modules
   * just wrote and fails when any tagged class appears in them (assessment item B1).
   */
  @Test
  void needs_tagged_suites_never_run_in_the_default_build() throws Exception {
    Path root = repoRoot();
    List<String> modules = List.of("core", "jrs", "ops", "app");
    List<String> tagged = new ArrayList<>();
    for (String module : modules) {
      Path tests = root.resolve(module).resolve("src").resolve("test").resolve("java");
      if (!Files.isDirectory(tests)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(tests)) {
        for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
          if (Files.readString(file, StandardCharsets.UTF_8).contains("@Tag(\"needs-")) {
            tagged.add(file.getFileName().toString().replace(".java", ""));
          }
        }
      }
    }
    assertThat(tagged).as("the release-gate suites exist").isNotEmpty();
    for (String module : modules) {
      Path reports = root.resolve(module).resolve("target").resolve("surefire-reports");
      if (!Files.isDirectory(reports)) {
        continue;
      }
      try (Stream<Path> files = Files.list(reports)) {
        List<String> ran =
            files
                .map(p -> p.getFileName().toString())
                .filter(name -> tagged.stream().anyMatch(name::contains))
                .toList();
        assertThat(ran)
            .as(
                "a needs-* suite ran in the default build of "
                    + module
                    + "; excludedGroups is not being honoured (surefire regression?)")
            .isEmpty();
      }
    }
  }

  @Test
  void repo_carries_claude_md_and_spec_and_ci_workflow() {
    Path root = repoRoot();
    assertThat(root.resolve("CLAUDE.md")).exists();
    assertThat(root.resolve("docs/spec.md")).exists();
    assertThat(root.resolve("docs/BUILD_STATUS.md")).exists();
    assertThat(root.resolve(".github/workflows/ci.yml")).exists();
    assertThat(Files.isDirectory(root.resolve("docs/decisions"))).isTrue();
  }

  /**
   * A workflow file that GitHub cannot parse fails every run in zero seconds with no jobs, which
   * looks like a red build but is really a silent gate. Every file under {@code .github/workflows}
   * must parse as YAML and declare at least one job.
   */
  @Test
  void every_workflow_file_parses_as_yaml_and_declares_jobs() throws Exception {
    Path workflows = repoRoot().resolve(".github/workflows");
    List<Path> files;
    try (Stream<Path> listing = Files.list(workflows)) {
      files =
          listing.filter(p -> p.getFileName().toString().matches(".*\\.ya?ml")).sorted().toList();
    }
    assertThat(files).as("workflow files under %s", workflows).isNotEmpty();
    YAMLMapper yaml = new YAMLMapper();
    for (Path file : files) {
      JsonNode tree;
      try (InputStream in = Files.newInputStream(file)) {
        tree = yaml.readTree(in);
      } catch (Exception e) {
        throw new AssertionError("workflow " + file.getFileName() + " is not valid YAML: " + e, e);
      }
      assertThat(tree.path("jobs").isObject())
          .as("workflow %s must declare a jobs mapping", file.getFileName())
          .isTrue();
      assertThat(tree.path("jobs").size())
          .as("workflow %s must declare at least one job", file.getFileName())
          .isPositive();
    }
  }

  /**
   * A workflow that runs on a moving tag executes whatever that tag points at tomorrow, and a job
   * without a timeout can hold a runner for six hours. Every {@code uses:} must name a 40-hex
   * commit and every job must set {@code timeout-minutes} (assessment item 5.5).
   */
  @Test
  void every_workflow_pins_actions_to_a_commit_and_bounds_every_job() throws Exception {
    Path workflows = repoRoot().resolve(".github/workflows");
    YAMLMapper yaml = new YAMLMapper();
    List<String> problems = new java.util.ArrayList<>();
    try (Stream<Path> listing = Files.list(workflows)) {
      for (Path file :
          listing.filter(p -> p.getFileName().toString().matches(".*\\.ya?ml")).toList()) {
        JsonNode tree;
        try (InputStream in = Files.newInputStream(file)) {
          tree = yaml.readTree(in);
        }
        tree.path("jobs")
            .properties()
            .forEach(
                job -> {
                  String where = file.getFileName() + " job " + job.getKey();
                  if (!job.getValue().path("timeout-minutes").isNumber()) {
                    problems.add(where + " has no timeout-minutes");
                  }
                  for (JsonNode step : job.getValue().path("steps")) {
                    String uses = step.path("uses").asText("");
                    if (!uses.isEmpty() && !uses.matches("[^@]+@[0-9a-f]{40}")) {
                      problems.add(where + " uses " + uses + " (not pinned to a commit)");
                    }
                  }
                });
      }
    }
    assertThat(problems).as("workflow hygiene").isEmpty();
  }

  /**
   * The repository once shipped a GPL-3.0 {@code LICENSE} beside a README that said "All rights
   * reserved. Licensed under the Apache License, Version 2.0" (ADR-0010). Every place that names
   * the licence must name the one in {@code LICENSE}, by its SPDX identifier, so a stray edit
   * cannot reopen the contradiction.
   */
  @Test
  void licence_is_gpl_3_and_every_document_says_so() throws Exception {
    Path root = repoRoot();
    String licence = Files.readString(root.resolve("LICENSE"));
    assertThat(licence).startsWith("                    GNU GENERAL PUBLIC LICENSE");
    assertThat(licence).contains("Version 3, 29 June 2007");
    assertThat(root.resolve("docs/decisions/0010-gpl-3-licence.md")).exists();

    for (String file :
        List.of(
            "README.md",
            "CONTRIBUTING.md",
            "pom.xml",
            "dist/src/image/README.txt",
            "dist/src/image/LICENSE-THIRD-PARTY.txt")) {
      String text = Files.readString(root.resolve(file));
      assertThat(text).as(file + " names the licence by SPDX id").contains("GPL-3.0-only");
      assertThat(text)
          .as(file + " does not contradict LICENSE")
          .doesNotContainIgnoringCase("Apache License, Version 2.0")
          .doesNotContainIgnoringCase("all rights reserved");
    }
  }

  /**
   * Repository hygiene (assessment items B3 and the tracked scratch config): no personal tool
   * settings or live-server scratch home in the index; the pre-commit hook is executable in the
   * index (git runs it as a program), checks only the staged Java files, and the build scripts wire
   * {@code core.hooksPath} so the hook is not merely documented.
   */
  @Test
  void repo_tracks_no_personal_files_and_wires_an_executable_staged_only_hook() throws Exception {
    Path root = repoRoot();
    String hook = Files.readString(root.resolve(".githooks/pre-commit"));
    assertThat(hook).contains("--cached").contains("spotlessFiles");
    assertThat(Files.readString(root.resolve("scripts/mvn.cmd"))).contains("core.hooksPath");
    assertThat(Files.readString(root.resolve("scripts/mvn.sh"))).contains("core.hooksPath");

    // Issue #188: a source archive (GitHub's "Download ZIP") has no index to inspect.
    assumeTrue(Files.exists(root.resolve(".git")), "not a git checkout; no index to inspect");
    Process git =
        new ProcessBuilder("git", "ls-files", "-s")
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start();
    String index = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(git.waitFor()).as(index).isZero();
    List<String> tracked = index.lines().toList();

    assertThat(tracked.stream().map(l -> l.substring(l.indexOf('	') + 1)))
        .noneMatch(p -> p.startsWith(".claude/") || p.startsWith("tmp-real-home/"));
    assertThat(tracked)
        .as("the hook is tracked with the executable bit")
        .anyMatch(l -> l.startsWith("100755 ") && l.endsWith("	.githooks/pre-commit"));
    assertThat(tracked).noneMatch(l -> l.endsWith(".githooks/pre-commit.cmd"));
  }

  /**
   * Roadmap item 16: the engine names the journal it needs ({@code engine.Journal}) and the state
   * package implements it, so the dependency runs one way only. An import of {@code core.state}
   * from {@code core.engine} puts the cycle back and makes the Runner untestable without SQLite.
   */
  @Test
  void engine_package_never_imports_the_state_package() throws Exception {
    Path engine = repoRoot().resolve("core/src/main/java/com/jaspersoft/jrsctl/core/engine");
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> listing = Files.list(engine)) {
      for (Path file : listing.filter(p -> p.toString().endsWith(".java")).toList()) {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
          if (line.startsWith("import com.jaspersoft.jrsctl.core.state.")) {
            offenders.add(file.getFileName() + ": " + line.strip());
          }
        }
      }
    }

    assertThat(offenders)
        .as("core.engine must not depend on core.state; the port is engine.Journal")
        .isEmpty();
  }

  private static Path repoRoot() {
    return Path.of(System.getProperty("jrsctl.acceptanceDir")).getParent();
  }
}
