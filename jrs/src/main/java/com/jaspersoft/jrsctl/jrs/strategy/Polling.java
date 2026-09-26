package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * How the strategy steps wait for the server (spec §6.5, §7.3): a bounded exponential backoff from
 * {@code initial} to {@code cap}, an overall {@code timeout}, a cancellation checkpoint before
 * every attempt and one {@link Event.Log} progress line per {@code logEvery}. Invariants: the clock
 * and sleeper are injectable so tests never wait; a {@link Tick.Failed} or the timeout ends the
 * loop with a message the step turns into its own {@code StepFailure}; nothing here mutates.
 */
public record Polling(
    Duration initial,
    Duration cap,
    Duration timeout,
    Duration logEvery,
    Clock clock,
    Sleeper sleeper) {

  public static final Duration DEFAULT_INITIAL = Duration.ofSeconds(2);
  public static final Duration DEFAULT_CAP = Duration.ofSeconds(10);
  public static final Duration DEFAULT_TIMEOUT = Duration.ofHours(2);
  public static final Duration DEFAULT_LOG_EVERY = Duration.ofSeconds(30);
  public static final Duration SERVER_START_TIMEOUT = Duration.ofMinutes(10);

  public Polling {
    Objects.requireNonNull(initial, "initial");
    Objects.requireNonNull(cap, "cap");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(logEvery, "logEvery");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(sleeper, "sleeper");
  }

  /** 2s doubling to 10s, 2h overall, a progress line every 30s, real clock and sleeps. */
  public static Polling defaults() {
    return new Polling(
        DEFAULT_INITIAL,
        DEFAULT_CAP,
        DEFAULT_TIMEOUT,
        DEFAULT_LOG_EVERY,
        Clock.systemUTC(),
        Sleeper.system());
  }

  /** Same backoff with a different overall timeout. */
  public Polling withTimeout(Duration newTimeout) {
    return new Polling(initial, cap, newTimeout, logEvery, clock, sleeper);
  }

  /** What one attempt observed. */
  public sealed interface Tick permits Tick.Continue, Tick.Done, Tick.Failed {
    /** Still in progress; {@code message} is shown in the periodic progress line. */
    record Continue(String message) implements Tick {}

    record Done() implements Tick {}

    /** The server reported failure; {@code message} is its own words. */
    record Failed(String message) implements Tick {}
  }

  /** How the loop ended. */
  public sealed interface Outcome permits Outcome.Completed, Outcome.Failed, Outcome.TimedOut {
    record Completed(int attempts) implements Outcome {}

    record Failed(String message, int attempts) implements Outcome {}

    record TimedOut(Duration after, int attempts) implements Outcome {}
  }

  /**
   * How many polls in a row may fail with a transient answer (a 503 from a restarting Tomcat, a 502
   * from a proxy, a refused connection) before the poll gives up (review finding 2.2). One blip
   * during a two-hour export used to fail the run while the server-side task went on.
   */
  public static final int MAX_CONSECUTIVE_FAILURES = 5;

  /** A transient failure counts as "still in progress" until too many arrive in a row. */
  private static Tick transientTick(
      Context ctx, EventSink out, Step step, String what, int consecutive, String message) {
    if (consecutive >= MAX_CONSECUTIVE_FAILURES) {
      return new Tick.Failed(
          consecutive + " consecutive transient failures polling " + what + "; last: " + message);
    }
    out.emit(
        new Event.Log(
            Instant.now(),
            ctx.runId(),
            Optional.of(step.id()),
            step.phase(),
            Event.Log.Level.WARN,
            "transient failure polling "
                + what
                + " ("
                + consecutive
                + " of "
                + MAX_CONSECUTIVE_FAILURES
                + " allowed in a row): "
                + message));
    return new Tick.Continue("");
  }

  /**
   * Polls {@code attempt} until it reports {@link Tick.Done} or {@link Tick.Failed}, the timeout
   * elapses, or the run is cancelled (which propagates as {@code CancelledException}).
   */
  public Outcome until(Context ctx, EventSink out, Step step, String what, Supplier<Tick> attempt) {
    Instant start = clock.instant();
    Instant lastLog = start;
    Duration delay = initial;
    int attempts = 0;
    int consecutiveFailures = 0;
    while (true) {
      ctx.cancel().checkpoint();
      attempts++;
      Tick tick;
      Optional<Duration> retryAfter = Optional.empty();
      try {
        tick = attempt.get();
        consecutiveFailures = 0;
      } catch (RestException e) {
        if (!e.transientFailure()) {
          throw e;
        }
        consecutiveFailures++;
        retryAfter = e.retryAfter();
        tick = transientTick(ctx, out, step, what, consecutiveFailures, e.getMessage());
      } catch (JrsUnreachableException e) {
        consecutiveFailures++;
        tick = transientTick(ctx, out, step, what, consecutiveFailures, e.getMessage());
      }
      switch (tick) {
        case Tick.Done d -> {
          return new Outcome.Completed(attempts);
        }
        case Tick.Failed f -> {
          return new Outcome.Failed(f.message(), attempts);
        }
        case Tick.Continue c -> {
          Instant now = clock.instant();
          Duration elapsed = Duration.between(start, now);
          if (elapsed.compareTo(timeout) >= 0) {
            return new Outcome.TimedOut(elapsed, attempts);
          }
          if (Duration.between(lastLog, now).compareTo(logEvery) >= 0) {
            lastLog = now;
            out.emit(
                new Event.Log(
                    now,
                    ctx.runId(),
                    Optional.of(step.id()),
                    step.phase(),
                    Event.Log.Level.INFO,
                    what
                        + " in progress after "
                        + elapsed.toSeconds()
                        + "s"
                        + (c.message().isBlank() ? "" : ": " + c.message())));
          }
          // Token-aware (review finding 1.11): a cancellation lands within one slice of the
          // backoff, not at its end, which for a two-hour export can be a minute away.
          Duration backoff = delay;
          Duration wait = retryAfter.filter(d -> d.compareTo(backoff) > 0).orElse(backoff);
          sleeper.sleep(wait, ctx.cancel());
          delay = delay.multipliedBy(2).compareTo(cap) > 0 ? cap : delay.multipliedBy(2);
        }
      }
    }
  }
}
