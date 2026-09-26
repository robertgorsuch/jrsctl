package com.jaspersoft.jrsctl.jrs.vendor;

import com.jaspersoft.jrsctl.core.config.ConfigLoader;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs the vendor {@code js-export}, {@code js-import} and {@code js-ant} scripts of a located
 * {@link Buildomatic} (spec §7.4). Invariants: every invocation goes through the {@link
 * ProcessRunner} as an argument list with no shell; the working directory is the buildomatic
 * directory and {@code JAVA_HOME} is set to {@code vendor.javaHome}, and an absent {@code javaHome}
 * or script yields {@link VendorRun.NotStarted} before anything is launched; every stdout/stderr
 * line is passed through the {@link Redactor} and emitted as an {@link Event.Log} before anything
 * else sees it; the timeout is enforced by the runner and reported as {@link VendorRun.TimedOut};
 * vendor scripts are never modified. Flag spellings live in {@link VendorFlags} only.
 */
public final class VendorTools {

  /** Default wall-clock limit for one vendor-tool run. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofHours(2);

  /** How many trailing output lines a {@link VendorRun} keeps for failure messages. */
  public static final int TAIL_LINES = 20;

  /**
   * Ant's own build banners, and the line the Windows wrappers print when they check Ant's return
   * code. Buildomatic replaces the success banner with {@code VALIDATION COMPLETED} through its
   * {@code ImportExportLogger}, so both spellings count. Matched case-insensitively anywhere in a
   * line, because the wrappers pipe Ant's output through {@code tee} and prefix it.
   */
  private static final List<String> REPORTED_FAILURE =
      List.of("build failed", "checking ant return code: bad");

  private static final List<String> REPORTED_SUCCESS =
      List.of("build successful", "validation completed");

  /**
   * The export/import command's own markers, string constants of {@code BaseExportImportCommand} in
   * {@code jasperserver-export-tool}: matched case-sensitively, the error and the start anywhere in
   * a line (log4j prefixes a timestamp), {@code Done} as the whole line or its last word.
   */
  private static final String PROCESSING_STARTED = "Processing started";

  private static final String PROCESSING_DONE = "Done";
  private static final String PROCESSING_ERROR = "ERROR BaseExportImportCommand";
  // buildomatic/bin/setup.xml (10.0.0): the confirmMessage of the interactive create-ks and the
  // warningMessage of the silent one both start with this sentence
  static final String KEYSTORE_CREATED_BANNER =
      "a new encryption key and a new keystore are about to be created";

  private final ProcessRunner runner;
  private final FileOps files;
  private final Redactor redactor;
  private final Duration timeout;
  private final Set<String> withheld;

  public VendorTools(ProcessRunner runner, FileOps files, Redactor redactor) {
    this(runner, files, redactor, DEFAULT_TIMEOUT);
  }

  public VendorTools(ProcessRunner runner, FileOps files, Redactor redactor, Duration timeout) {
    this(runner, files, redactor, timeout, Set.of());
  }

  /**
   * {@code withheld}: the variable names the configuration's {@code env:} secret references read.
   * They and every {@code JRSCTL_*} variable are removed from the vendor scripts' environment
   * (issue #47, ADR-0019).
   */
  public VendorTools(
      ProcessRunner runner,
      FileOps files,
      Redactor redactor,
      Duration timeout,
      Set<String> withheld) {
    this.runner = Objects.requireNonNull(runner, "runner");
    this.files = Objects.requireNonNull(files, "files");
    this.redactor = Objects.requireNonNull(redactor, "redactor");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.withheld = Set.copyOf(withheld);
  }

  /**
   * Inherited names the vendor scripts must not see (issue #47, ADR-0019): every {@code JRSCTL_*}
   * variable and every name in {@code configured}, compared without regard to case.
   */
  static Set<String> withheldNames(Map<String, String> inherited, Set<String> configured) {
    Set<String> names = new LinkedHashSet<>();
    String prefix = ConfigLoader.ENV_PREFIX;
    for (String name : inherited.keySet()) {
      boolean jrsctl = name.regionMatches(true, 0, prefix, 0, prefix.length());
      if (jrsctl || configured.stream().anyMatch(name::equalsIgnoreCase)) {
        names.add(name);
      }
    }
    return Set.copyOf(names);
  }

  /** The run and step the streamed log lines belong to. */
  public record LogScope(String runId, Optional<String> stepId, String phase) {
    public LogScope {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(stepId, "stepId");
      Objects.requireNonNull(phase, "phase");
    }
  }

  /** One script invocation; {@code args} excludes the script path itself. */
  public record Invocation(
      Buildomatic buildomatic, String script, List<String> args, Optional<Path> javaHome) {
    public Invocation {
      Objects.requireNonNull(buildomatic, "buildomatic");
      Objects.requireNonNull(script, "script");
      args = List.copyOf(args);
      Objects.requireNonNull(javaHome, "javaHome");
    }
  }

  public FileOps files() {
    return files;
  }

  public Duration timeout() {
    return timeout;
  }

  // ---------------------------------------------------------------- argument builders

  /** {@code js-export} arguments for the request; the archive path is {@code output}. */
  public static List<String> exportArgs(ExportRequest request, Path output) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(output, "output");
    List<String> args = new ArrayList<>();
    args.add(VendorFlags.OUTPUT_ZIP);
    args.add(output.toString());
    boolean everything = request.fullServer() || request.scope() == ExportRequest.Scope.EVERYTHING;
    if (everything) {
      args.add(VendorFlags.EVERYTHING);
    } else {
      List<String> uris = new ArrayList<>(new TreeSet<>(request.uris()));
      if (uris.isEmpty()) {
        uris.add("/");
      }
      args.add(VendorFlags.URIS);
      args.add(String.join(VendorFlags.URI_SEPARATOR, uris));
      args.add(VendorFlags.REPOSITORY_PERMISSIONS);
    }
    if (request.includeUsersRoles()) {
      args.add(VendorFlags.USERS);
      args.add(VendorFlags.ROLES);
    }
    if (request.includeAccessEvents()) {
      args.add(VendorFlags.INCLUDE_ACCESS_EVENTS);
    }
    if (request.includeAuditEvents()) {
      args.add(VendorFlags.INCLUDE_AUDIT_EVENTS);
    }
    if (request.includeMonitoring()) {
      args.add(VendorFlags.INCLUDE_MONITORING_EVENTS);
    }
    if (request.includeSettings()) {
      args.add(VendorFlags.INCLUDE_SERVER_SETTINGS);
    }
    if (request.skipDependentResources()) {
      args.add(VendorFlags.SKIP_DEPENDENT_RESOURCES);
    }
    if (request.skipFavoriteResources()) {
      args.add(VendorFlags.SKIP_FAVORITE_RESOURCES);
    }
    request
        .keyAlias()
        .ifPresent(
            alias -> {
              args.add(VendorFlags.KEYALIAS);
              args.add(alias);
            });
    request
        .organization()
        .ifPresent(
            org -> {
              args.add(VendorFlags.ORGANIZATION);
              args.add(org);
            });
    return List.copyOf(args);
  }

  /** {@code js-import} arguments for the request, without any keystore option. */
  public static List<String> importArgs(ImportRequest request) {
    return importArgs(request, Optional.empty());
  }

  /**
   * {@code js-import} arguments for the request; when {@code storepass} is present and the request
   * names a source keystore, the keystore options are appended (see {@link #keystoreArgs}).
   */
  public static List<String> importArgs(ImportRequest request, Optional<Secret> storepass) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(storepass, "storepass");
    List<String> args = new ArrayList<>();
    args.add(VendorFlags.INPUT_ZIP);
    args.add(request.archive().toString());
    if (request.update()) {
      args.add(VendorFlags.UPDATE);
    }
    if (request.skipUserUpdate()) {
      args.add(VendorFlags.SKIP_USER_UPDATE);
    }
    if (request.includeAccessEvents()) {
      args.add(VendorFlags.INCLUDE_ACCESS_EVENTS);
    }
    if (request.includeAuditEvents()) {
      args.add(VendorFlags.INCLUDE_AUDIT_EVENTS);
    }
    if (request.includeMonitoring()) {
      args.add(VendorFlags.INCLUDE_MONITORING_EVENTS);
    }
    if (request.includeSettings()) {
      args.add(VendorFlags.INCLUDE_SERVER_SETTINGS);
    }
    if (request.skipThemes()) {
      args.add(VendorFlags.SKIP_THEMES);
    }
    if (request.brokenDependencies() != BrokenDependencies.FAIL) {
      args.add(VendorFlags.BROKEN_DEPENDENCIES);
      args.add(request.brokenDependencies().vendor());
    }
    request.sourceKeystore().ifPresent(ks -> args.addAll(keystoreArgs(ks, storepass)));
    request
        .keyAlias()
        .ifPresent(
            alias -> {
              args.add(VendorFlags.KEYALIAS);
              args.add(alias);
            });
    request
        .organization()
        .ifPresent(
            org -> {
              args.add(VendorFlags.ORGANIZATION);
              args.add(org);
              if (request.mergeOrganization()) {
                args.add(VendorFlags.MERGE_ORGANIZATION);
              }
            });
    return List.copyOf(args);
  }

  /**
   * {@code --keystore <path>} plus {@code --storepass <secret>} when a password is given. The
   * password necessarily becomes a {@link String} argument of the child process; callers register
   * it with the {@link Redactor} first so the logged command line and any echo are masked.
   */
  public static List<String> keystoreArgs(Path keystore, Optional<Secret> storepass) {
    Objects.requireNonNull(keystore, "keystore");
    List<String> args = new ArrayList<>();
    args.add(VendorFlags.KEYSTORE);
    args.add(keystore.toString());
    storepass.ifPresent(
        s -> {
          args.add(VendorFlags.STOREPASS);
          char[] chars = s.chars();
          try {
            args.add(new String(chars));
          } finally {
            Arrays.fill(chars, '\0');
          }
        });
    return List.copyOf(args);
  }

  // ---------------------------------------------------------------- convenience runners

  public VendorRun export(
      Buildomatic buildomatic,
      ExportRequest request,
      Path output,
      Optional<Path> javaHome,
      EventSink sink,
      LogScope scope) {
    return run(
        new Invocation(
            buildomatic, Buildomatic.EXPORT_SCRIPT, exportArgs(request, output), javaHome),
        sink,
        scope);
  }

  public VendorRun importArchive(
      Buildomatic buildomatic,
      ImportRequest request,
      Optional<Secret> storepass,
      Optional<Path> javaHome,
      EventSink sink,
      LogScope scope) {
    storepass.ifPresent(redactor::register);
    return run(
        new Invocation(
            buildomatic, Buildomatic.IMPORT_SCRIPT, importArgs(request, storepass), javaHome),
        sink,
        scope);
  }

  /** {@code js-ant <target> [args...]}; used by the upgrade orchestration (spec §10.2). */
  public VendorRun ant(
      Buildomatic buildomatic,
      String target,
      List<String> extraArgs,
      Optional<Path> javaHome,
      EventSink sink,
      LogScope scope) {
    Objects.requireNonNull(target, "target");
    List<String> args = new ArrayList<>();
    args.add(target);
    args.addAll(extraArgs);
    return run(new Invocation(buildomatic, Buildomatic.ANT_SCRIPT, args, javaHome), sink, scope);
  }

  /**
   * What cmd splits a token at or treats as an operator: delimiters, and the redirection and
   * chaining characters. A percent sign is left alone, since batch expansion happens inside quotes
   * too; the operator guide names it as unsupported with the vendor strategy on Windows.
   */
  static final String CMD_METACHARACTERS = ",;=&|<>^";

  /**
   * Review finding 2.8: a batch wrapper re-reads its arguments as {@code %1} tokens, and cmd splits
   * a token at a comma, a semicolon or an equals sign, so {@code --uris /a,/b} arrived as three
   * arguments. Such a value is wrapped in double quotes, which cmd keeps as one token and strips
   * before the importer sees it. Java already quotes arguments holding spaces.
   */
  static String quoteForCmd(String arg) {
    boolean splits = false;
    for (int i = 0; i < CMD_METACHARACTERS.length(); i++) {
      splits |= arg.indexOf(CMD_METACHARACTERS.charAt(i)) >= 0;
    }
    boolean quoted = arg.length() >= 2 && arg.startsWith("\"") && arg.endsWith("\"");
    boolean spaced = arg.indexOf(' ') >= 0 || arg.indexOf('\t') >= 0;
    return splits && !quoted && !spaced ? "\"" + arg + "\"" : arg;
  }

  /**
   * The inherited {@code JAVA_OPTS} with {@code -Djs.cache.provider=<provider>} appended, or the
   * inherited value unchanged when it already names that property, so an operator's own choice
   * wins.
   */
  static String javaOptsWith(Map<String, String> inherited, String provider) {
    String current = inherited.getOrDefault(VendorFlags.JAVA_OPTS, "").strip();
    String prefix = "-D" + VendorFlags.CACHE_PROVIDER_PROPERTY + "=";
    if (current.contains(prefix)) {
      return current;
    }
    String flag = prefix + provider;
    return current.isEmpty() ? flag : current + " " + flag;
  }

  /**
   * {@code <javaHome>/bin} followed by the inherited {@code PATH} (looked up case-insensitively, as
   * Windows spells it {@code Path}), joined with {@code ;} for batch wrappers and {@code :} for
   * shell ones.
   */
  static String pathWithJavaFirst(Path javaHome, Map<String, String> inherited, boolean batch) {
    String separator = batch ? ";" : ":";
    String bin = javaHome.resolve("bin").toString();
    String rest =
        inherited.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(VendorFlags.PATH))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse("");
    return rest.isBlank() ? bin : bin + separator + rest;
  }

  // ---------------------------------------------------------------- the one launcher

  /** Launches the invocation, streaming output as redacted {@link Event.Log} lines. */
  public VendorRun run(Invocation invocation, EventSink sink, LogScope scope) {
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(sink, "sink");
    Objects.requireNonNull(scope, "scope");
    Optional<Path> script = invocation.buildomatic().scriptFor(invocation.script());
    if (script.isEmpty()) {
      return new VendorRun.NotStarted(
          "vendor script "
              + invocation.script()
              + " not found in "
              + invocation.buildomatic().dir(),
          "check that server.installDir points at a complete JasperReports Server installation"
              + " whose buildomatic directory contains "
              + invocation.script());
    }
    if (invocation.javaHome().isEmpty()) {
      return new VendorRun.NotStarted(
          "vendor.javaHome is not set; buildomatic needs a JDK",
          "set vendor.javaHome in config.yaml to the JDK the vendor scripts should use");
    }
    List<String> command = new ArrayList<>();
    command.add(script.get().toString());
    boolean batch = script.get().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bat");
    for (String arg : invocation.args()) {
      command.add(batch ? quoteForCmd(arg) : arg);
    }
    // The wrappers launch ExportCommand/ImportCommand with the first java on PATH unless a java
    // folder sits next to buildomatic, so a relocated tree ran on the machine's default JDK (#31).
    Path javaHome = invocation.javaHome().get();
    Map<String, String> inherited = System.getenv();
    // js-export.bat never passes js.cache.provider, so ExportCommand's Spring context failed with
    // "Invalid profile [null]" on every Windows host (#39); every wrapper appends %JAVA_OPTS%.
    Map<String, String> env =
        Map.of(
            VendorFlags.JAVA_HOME,
            javaHome.toString(),
            VendorFlags.PATH,
            pathWithJavaFirst(javaHome, inherited, batch),
            VendorFlags.JAVA_OPTS,
            javaOptsWith(inherited, invocation.buildomatic().cacheProvider()));
    long startedAt = System.currentTimeMillis() - 1000L;
    log(sink, scope, Event.Log.Level.INFO, "running " + String.join(" ", command));
    Deque<String> tail = new ArrayDeque<>(TAIL_LINES);
    EnumSet<VendorRun.Reported> banners = EnumSet.noneOf(VendorRun.Reported.class);
    ProcessingMarkers markers = new ProcessingMarkers();
    ProcessRunner.Result result =
        runner.run(
            new ProcessRunner.Request(
                command,
                Optional.of(invocation.buildomatic().dir()),
                env,
                timeout,
                withheldNames(inherited, withheld)),
            line -> {
              String text = redactor.redact(line.text());
              synchronized (tail) {
                if (tail.size() == TAIL_LINES) {
                  tail.removeFirst();
                }
                tail.addLast(text);
                banner(text).ifPresent(banners::add);
                markers.accept(text);
              }
              Event.Log.Level level =
                  switch (line.stream()) {
                    case STDOUT -> Event.Log.Level.INFO;
                    case STDERR -> Event.Log.Level.WARN;
                  };
              log(sink, scope, level, text);
            });
    List<String> lines;
    VendorRun.Reported reported;
    VendorRun.Processing processing;
    synchronized (tail) {
      lines = List.copyOf(tail);
      reported =
          banners.contains(VendorRun.Reported.CREATED_KEYSTORE)
              ? VendorRun.Reported.CREATED_KEYSTORE
              : banners.contains(VendorRun.Reported.FAILED)
                  ? VendorRun.Reported.FAILED
                  : banners.contains(VendorRun.Reported.SUCCEEDED)
                      ? VendorRun.Reported.SUCCEEDED
                      : VendorRun.Reported.SILENT;
      processing = markers.result();
    }
    if (result.timedOut()) {
      log(
          sink,
          scope,
          Event.Log.Level.ERROR,
          invocation.script() + " killed after " + timeout.toSeconds() + "s");
      return new VendorRun.TimedOut(timeout, lines);
    }
    log(
        sink,
        scope,
        result.exitCode() == 0 ? Event.Log.Level.INFO : Event.Log.Level.ERROR,
        invocation.script()
            + " exited with "
            + result.exitCode()
            + " after "
            + result.elapsed().toSeconds()
            + "s");
    // review §3.4 (issue #111): the vendor's first troubleshooting instruction is this file
    newestLogSince(invocation.buildomatic().dir().resolve("logs"), startedAt)
        .ifPresent(p -> log(sink, scope, Event.Log.Level.INFO, BUILDOMATIC_LOG_PREFIX + p));
    VendorRun.Completed completed =
        new VendorRun.Completed(result.exitCode(), result.elapsed(), lines, reported, processing);
    if (result.exitCode() == 0 && !completed.ok()) {
      log(
          sink,
          scope,
          Event.Log.Level.ERROR,
          invocation.script() + " " + completed.summary() + "; treating it as a failure");
    }
    return completed;
  }

  /** Prefix of the log line naming the buildomatic log a vendor run wrote (issue #111). */
  public static final String BUILDOMATIC_LOG_PREFIX = "buildomatic log: ";

  /**
   * The newest {@code *.log} under buildomatic's {@code logs} directory modified since the run
   * started, which is the file the vendor's troubleshooting instructions point at first ({@code
   * js-upgrade-<date>.log}, {@code js-export-…log}); empty when the tool wrote none.
   */
  static Optional<Path> newestLogSince(Path logsDir, long sinceMillis) {
    if (!Files.isDirectory(logsDir)) {
      return Optional.empty();
    }
    Path best = null;
    long bestTime = sinceMillis;
    try (DirectoryStream<Path> files = Files.newDirectoryStream(logsDir, "*.log")) {
      for (Path f : files) {
        if (!Files.isRegularFile(f)) {
          continue;
        }
        long t = Files.getLastModifiedTime(f).toMillis();
        if (t >= bestTime) {
          best = f;
          bestTime = t;
        }
      }
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.ofNullable(best);
  }

  /**
   * Follows the export/import command's markers through a transcript in order. An error line wins
   * over everything; {@code Done} counts only after a {@code Processing started}, and a second
   * start (a wrapper that runs the command again) needs its own {@code Done}.
   */
  private static final class ProcessingMarkers {
    private boolean started;
    private boolean done;
    private boolean failed;

    void accept(String line) {
      if (line.contains(PROCESSING_ERROR)) {
        failed = true;
      } else if (line.contains(PROCESSING_STARTED)) {
        started = true;
        done = false;
      } else if (started) {
        String stripped = line.strip();
        if (stripped.equals(PROCESSING_DONE) || stripped.endsWith(" " + PROCESSING_DONE)) {
          done = true;
        }
      }
    }

    VendorRun.Processing result() {
      if (failed) {
        return VendorRun.Processing.FAILED;
      }
      if (done) {
        return VendorRun.Processing.COMPLETED;
      }
      return started ? VendorRun.Processing.UNFINISHED : VendorRun.Processing.NOT_STARTED;
    }
  }

  /** The build banner this line carries, if any; failure wins when a line somehow holds both. */
  private static Optional<VendorRun.Reported> banner(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    if (lower.contains(KEYSTORE_CREATED_BANNER)) {
      return Optional.of(VendorRun.Reported.CREATED_KEYSTORE);
    }
    if (REPORTED_FAILURE.stream().anyMatch(lower::contains)) {
      return Optional.of(VendorRun.Reported.FAILED);
    }
    if (REPORTED_SUCCESS.stream().anyMatch(lower::contains)) {
      return Optional.of(VendorRun.Reported.SUCCEEDED);
    }
    return Optional.empty();
  }

  private void log(EventSink sink, LogScope scope, Event.Log.Level level, String message) {
    sink.emit(
        new Event.Log(
            Instant.now(),
            scope.runId(),
            scope.stepId(),
            scope.phase(),
            level,
            redactor.redact(message)));
  }
}
