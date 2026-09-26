package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.RunRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * The {@code runs list} filters (issue #186). Invariants: an empty filter matches every run; the
 * status set is the terminal states plus {@link Status#PENDING}, written in lower case with hyphens
 * on the command line; an operation matches itself or any operation it is a dotted prefix of
 * ({@code hotfix} matches {@code hotfix.apply}, not {@code hotfixes}); {@code since} keeps runs
 * that started at or after the cut-off, which a relative age turns into an instant against the
 * clock given at match time; a value the converters cannot read is a picocli usage error (exit 1).
 */
record RunFilter(Set<Status> statuses, Optional<String> operation, Optional<Since> since) {

  RunFilter {
    statuses = Set.copyOf(statuses);
  }

  /** How a run ended, or that it has not. */
  enum Status {
    SUCCEEDED,
    FAILED,
    ROLLED_BACK,
    CANCELLED,
    PRECHECK_FAILED,
    PENDING;

    String cli() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static Status of(RunRecord run) {
      return run.terminalState()
          .map(
              s ->
                  switch (s) {
                    case SUCCEEDED -> SUCCEEDED;
                    case FAILED -> FAILED;
                    case ROLLED_BACK -> ROLLED_BACK;
                    case CANCELLED -> CANCELLED;
                    case PRECHECK_FAILED -> PRECHECK_FAILED;
                  })
          .orElse(PENDING);
    }

    static String choices() {
      return Arrays.stream(values()).map(Status::cli).collect(Collectors.joining(", "));
    }
  }

  /** A start-time cut-off: an instant, or an age measured back from now. */
  record Since(Optional<Instant> at, Optional<Duration> ago) {

    Instant cutoff(Clock clock) {
      return at.orElseGet(() -> clock.instant().minus(ago.orElse(Duration.ZERO)));
    }
  }

  boolean empty() {
    return statuses.isEmpty() && operation.isEmpty() && since.isEmpty();
  }

  boolean matches(RunRecord run, Clock clock) {
    if (!statuses.isEmpty() && !statuses.contains(Status.of(run))) {
      return false;
    }
    if (operation.isPresent()) {
      String op = operation.get();
      if (!run.operation().equals(op) && !run.operation().startsWith(op + ".")) {
        return false;
      }
    }
    return since.map(s -> !run.startedAt().isBefore(s.cutoff(clock))).orElse(true);
  }

  /** Reads {@code succeeded}, {@code rolled-back}, ... in any case. */
  static final class StatusConverter implements ITypeConverter<Status> {
    @Override
    public Status convert(String value) {
      String name = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
      for (Status s : Status.values()) {
        if (s.name().equals(name)) {
          return s;
        }
      }
      throw new TypeConversionException(
          "'" + value + "' is not a run status; use one of " + Status.choices());
    }
  }

  /** Reads {@code 2026-09-20} (UTC midnight), an ISO instant, or an age such as {@code 7d}. */
  static final class SinceConverter implements ITypeConverter<Since> {

    private static final Pattern AGE = Pattern.compile("(\\d+)([dhm])");

    @Override
    public Since convert(String value) {
      String v = value.strip();
      Matcher m = AGE.matcher(v.toLowerCase(Locale.ROOT));
      if (m.matches()) {
        long n = Long.parseLong(m.group(1));
        Duration ago =
            switch (m.group(2)) {
              case "d" -> Duration.ofDays(n);
              case "h" -> Duration.ofHours(n);
              default -> Duration.ofMinutes(n);
            };
        return new Since(Optional.empty(), Optional.of(ago));
      }
      for (var parse :
          List.<java.util.function.Function<String, Instant>>of(
              Instant::parse, s -> LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant())) {
        try {
          return new Since(Optional.of(parse.apply(v)), Optional.empty());
        } catch (DateTimeParseException e) {
          // try the next form
        }
      }
      throw new TypeConversionException(
          "'"
              + value
              + "' is not a time; use a date (2026-09-20), an instant (2026-09-20T08:00:00Z) or"
              + " an age (7d, 12h, 30m)");
    }
  }
}
