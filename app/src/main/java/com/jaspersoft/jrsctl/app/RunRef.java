package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

/**
 * Turns what an operator typed for a run into one run (issue #186). Invariants: an exact id always
 * wins; otherwise the text matches a run whose id ends with it (the trailing hex, {@code ab12}) or
 * starts with it, with or without the {@code r-} prefix ({@code 20260926-1530}); more than one
 * match is {@link Resolved.Ambiguous}, never a guess; blank text matches nothing. Run ids keep
 * their format, so directories, the journal and support bundles are unaffected.
 */
final class RunRef {

  private static final String PREFIX = "r-";

  private RunRef() {}

  /** What the text named. */
  sealed interface Resolved {

    /** Exactly one run. */
    record Found(RunRecord run) implements Resolved {}

    /** No run. */
    record Unknown(String given) implements Resolved {}

    /** Several runs; the operator must give more of the id. */
    record Ambiguous(String given, List<String> ids) implements Resolved {
      public Ambiguous {
        ids = List.copyOf(ids);
      }
    }
  }

  static Resolved resolve(StateStore store, String given) {
    String text = given.strip();
    if (text.isEmpty()) {
      return new Resolved.Unknown(given);
    }
    Optional<RunRecord> exact = store.run(text);
    if (exact.isPresent()) {
      return new Resolved.Found(exact.get());
    }
    List<RunRecord> matches =
        store.runs(Integer.MAX_VALUE).stream().filter(r -> matches(r.runId(), text)).toList();
    return switch (matches.size()) {
      case 0 -> new Resolved.Unknown(given);
      case 1 -> new Resolved.Found(matches.get(0));
      default -> new Resolved.Ambiguous(given, matches.stream().map(RunRecord::runId).toList());
    };
  }

  private static boolean matches(String id, String text) {
    return id.endsWith(text) || id.startsWith(text) || id.startsWith(PREFIX + text);
  }

  /**
   * The run {@code given} names, or the exit code after reporting why there is none: unknown is the
   * precheck failure it always was (exit 2), ambiguous is a usage error (exit 1) that lists the
   * candidates. A shortened id is echoed in full on stderr, outside JSON output, so the operator
   * sees which run the command acts on.
   */
  static Lookup lookup(
      StateStore store, String given, PrintWriter out, PrintWriter err, boolean json) {
    return switch (resolve(store, given)) {
      case Resolved.Found f -> {
        if (!json && !f.run().runId().equals(given.strip())) {
          err.println("run " + f.run().runId());
          err.flush();
        }
        yield new Lookup.Run(f.run());
      }
      case Resolved.Unknown u ->
          new Lookup.Exit(
              ExitCodes.fail(
                  out,
                  err,
                  json,
                  ExitCodes.PRECHECK_FAILED,
                  "unknown run " + u.given(),
                  Optional.of("see `jrsctl runs list`")));
      case Resolved.Ambiguous a ->
          new Lookup.Exit(
              ExitCodes.fail(
                  out,
                  err,
                  json,
                  ExitCodes.USAGE,
                  "run id "
                      + a.given()
                      + " matches "
                      + a.ids().size()
                      + " runs: "
                      + String.join(", ", a.ids()),
                  Optional.of("give more of the id")));
    };
  }

  /** The outcome of {@link #lookup}. */
  sealed interface Lookup {

    /** The run to act on. */
    record Run(RunRecord run) implements Lookup {}

    /** Nothing to act on; the command returns this exit code. */
    record Exit(int code) implements Lookup {}
  }
}
