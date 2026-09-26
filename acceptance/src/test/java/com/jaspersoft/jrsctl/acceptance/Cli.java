package com.jaspersoft.jrsctl.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the packaged {@code jrsctl.jar} as a separate process, the way an operator would. Invariant:
 * acceptance tests never call application classes directly; the shaded jar is the unit under test.
 */
final class Cli {

  record Result(int exitCode, String stdout, String stderr) {
    Result assertExit(int expected) {
      assertThat(exitCode)
          .as("exit code\nstdout:\n%s\nstderr:\n%s", stdout, stderr)
          .isEqualTo(expected);
      return this;
    }
  }

  private final Path jar;
  private final String java;
  private final Map<String, String> env;

  Cli(Map<String, String> env) {
    this.jar = Path.of(System.getProperty("jrsctl.jar"));
    this.java = System.getProperty("jrsctl.java");
    this.env = env;
    assertThat(jar).as("shaded jar built by the app module").exists();
  }

  Cli() {
    this(Map.of());
  }

  /**
   * A jrsctl process that was started without waiting for it, so a test can observe it, kill it
   * mid-step with the operating system's own command, or collect its {@link Result} later.
   */
  record Running(Process process, Path out, Path err, List<String> cmd) {

    long pid() {
      return process.pid();
    }

    boolean alive() {
      return process.isAlive();
    }

    /** Waits for the process to exit (or forcibly ends it after {@code seconds}) and collects. */
    Result result(long seconds) throws IOException, InterruptedException {
      if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("jrsctl did not exit within " + seconds + "s: " + cmd);
      }
      return collect();
    }

    /** Reads what the (already exited) process wrote and removes the capture files. */
    Result collect() throws IOException {
      String o = Files.readString(out, StandardCharsets.UTF_8);
      String e = Files.readString(err, StandardCharsets.UTF_8);
      Files.deleteIfExists(out);
      Files.deleteIfExists(err);
      return new Result(process.exitValue(), o, e);
    }
  }

  /** Starts jrsctl and returns at once; the caller decides when and how it ends. */
  Running start(String... args) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add(java);
    // ADR-0043: acceptance must pass when the build itself runs in a container
    cmd.add("-Djrsctl.containerCheck=false");
    cmd.add("-jar");
    cmd.add(jar.toString());
    cmd.addAll(List.of(args));
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.environment().putAll(env);
    Path out = Files.createTempFile("jrsctl-out", ".txt");
    Path err = Files.createTempFile("jrsctl-err", ".txt");
    pb.redirectOutput(out.toFile());
    pb.redirectError(err.toFile());
    return new Running(pb.start(), out, err, List.copyOf(cmd));
  }

  Result run(String... args) throws IOException, InterruptedException {
    return start(args).result(120);
  }
}
