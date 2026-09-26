package com.jaspersoft.jrsctl.app;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;

/**
 * Questions for the operator across several prompts in one command (#63). Invariants: answers come
 * from the console when there is one (secrets without echo), otherwise from one buffered reader
 * over standard input shared by every prompt of the process, so piped answers are consumed line by
 * line and none is lost to a second buffer; end of input is an empty {@link Optional} and a yes/no
 * question answered by end of input is no, so an unattended run never agrees by accident; a secret
 * is returned as a {@code char[]} the caller must zero, and is never printed; tests replace the
 * input with {@link #override} and restore it with {@link #reset}. {@link #path} additionally
 * offers filesystem completion through JLine (#102, ADR-0037) when a real interactive terminal is
 * present; any failure to obtain one is permanent for the process and falls back to {@link #line}.
 */
final class Prompter {

  private static volatile Optional<BufferedReader> override = Optional.empty();
  private static volatile Optional<BufferedReader> stdin = Optional.empty();
  private static volatile boolean jlineUnavailable = false;
  private static volatile Optional<LineReader> jlineReader = Optional.empty();

  /**
   * A parser that treats the whole line as one word: these are single-path prompts, not commands.
   * Package-private for tests.
   */
  static final Parser WHOLE_LINE_PARSER = (text, cursor, context) -> new WholeLine(text, cursor);

  /**
   * The whole line as one path. It implements {@link CompletingParsedLine} so JLine completes it
   * without quoting and without its warning; a path is never quoted or escaped, so a completed
   * candidate is inserted as is, spaces included.
   */
  private record WholeLine(String line, int cursor) implements CompletingParsedLine {
    @Override
    public String word() {
      return line;
    }

    @Override
    public int wordCursor() {
      return cursor;
    }

    @Override
    public int wordIndex() {
      return 0;
    }

    @Override
    public List<String> words() {
      return List.of(line);
    }

    @Override
    public CharSequence escape(CharSequence candidate, boolean complete) {
      return candidate;
    }

    @Override
    public int rawWordCursor() {
      return cursor;
    }

    @Override
    public int rawWordLength() {
      return line.length();
    }
  }

  private Prompter() {}

  /** Replaces standard input and the console with {@code answers} (tests). */
  static void override(Reader answers) {
    override = Optional.of(new BufferedReader(answers));
  }

  /** Restores standard input and the console, and forgets the reader over the previous stdin. */
  static synchronized void reset() {
    override = Optional.empty();
    stdin = Optional.empty();
  }

  /**
   * One path, stripped; empty at end of input. Behaves exactly like {@link #line} except that, in a
   * real interactive session with no {@link #override}, tab completes filesystem entries and
   * supports normal line editing (arrow keys, backspace across the line) through JLine. Falls back
   * to {@link #line} under test, when piped, or when JLine cannot obtain a terminal on this host.
   */
  static Optional<String> path(PrintWriter out, String prompt) {
    if (override.isEmpty()) {
      Optional<LineReader> reader = jlineReader();
      if (reader.isPresent()) {
        out.flush();
        try {
          return Optional.of(reader.get().readLine(prompt).strip());
        } catch (EndOfFileException | UserInterruptException e) {
          return Optional.empty();
        }
      }
    }
    return line(out, prompt);
  }

  /**
   * A cached JLine reader for {@link #path}, built once per process on the shared {@link
   * SystemTerminal} (JNI provider, no JNA, ADR-0037) and never rebuilt once building it has failed.
   * Empty when there is no console at all, since a piped or redirected session gets nothing from
   * line editing either way.
   */
  private static synchronized Optional<LineReader> jlineReader() {
    if (jlineUnavailable) {
      return Optional.empty();
    }
    if (jlineReader.isPresent()) {
      return jlineReader;
    }
    Optional<org.jline.terminal.Terminal> terminal = SystemTerminal.get();
    if (terminal.isEmpty()) {
      return Optional.empty();
    }
    try {
      jlineReader =
          Optional.of(
              LineReaderBuilder.builder()
                  .terminal(terminal.get())
                  .parser(WHOLE_LINE_PARSER)
                  .completer(new PathCompleter())
                  .build());
    } catch (Throwable e) {
      jlineUnavailable = true;
      jlineReader = Optional.empty();
    }
    return jlineReader;
  }

  /**
   * A reader on the same JLine terminal as {@link #path}, completing {@code completions} instead of
   * paths (field test 3: every prompt gets line editing, not only path prompts). Empty when there
   * is no JLine terminal, under test or when piped.
   */
  private static Optional<LineReader> plainReader(java.util.Collection<String> completions) {
    if (override.isPresent() || jlineReader().isEmpty() || SystemTerminal.get().isEmpty()) {
      return Optional.empty();
    }
    LineReaderBuilder builder =
        LineReaderBuilder.builder().terminal(SystemTerminal.get().get()).parser(WHOLE_LINE_PARSER);
    if (!completions.isEmpty()) {
      builder.completer(new org.jline.reader.impl.completer.StringsCompleter(completions));
    }
    return Optional.of(builder.build());
  }

  /**
   * A line with {@code initial} already typed, so the operator edits a value instead of retyping
   * it, and Tab completes {@code completions} (field test 3). Returns what was on the line, which
   * may be empty; empty Optional at end of input. Without a JLine terminal the initial value is
   * shown in brackets and an empty answer keeps it.
   */
  static Optional<String> edit(
      PrintWriter out, String prompt, String initial, java.util.Collection<String> completions) {
    Optional<LineReader> reader = plainReader(completions);
    if (reader.isPresent()) {
      out.flush();
      try {
        return Optional.of(reader.get().readLine(prompt, null, initial).strip());
      } catch (EndOfFileException | UserInterruptException e) {
        return Optional.empty();
      }
    }
    String shown = initial.isEmpty() ? prompt : prompt + "[" + initial + "] ";
    return line(out, shown).map(answer -> answer.isEmpty() ? initial : answer);
  }

  /**
   * One line, stripped; empty at end of input. On a real terminal it is read through JLine, so the
   * arrow keys move the cursor (field test 3); under test and when piped it is the plain reader.
   */
  static Optional<String> line(PrintWriter out, String prompt) {
    Optional<LineReader> reader = plainReader(List.of());
    if (reader.isPresent()) {
      out.flush();
      try {
        return Optional.of(reader.get().readLine(prompt).strip());
      } catch (EndOfFileException | UserInterruptException e) {
        return Optional.empty();
      }
    }
    Optional<Console> console = console();
    if (console.isPresent()) {
      out.flush();
      return Optional.ofNullable(console.get().readLine("%s", prompt)).map(String::strip);
    }
    out.print(prompt);
    out.flush();
    try {
      Optional<String> answer = Optional.ofNullable(reader().readLine()).map(String::strip);
      if (answer.isEmpty()) {
        out.println();
      }
      return answer;
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** A secret, not echoed on a console; empty at end of input. The caller zeroes the array. */
  static Optional<char[]> secret(PrintWriter out, String prompt) {
    Optional<Console> console = console();
    if (console.isPresent()) {
      out.flush();
      return Optional.ofNullable(console.get().readPassword("%s", prompt));
    }
    out.print(prompt);
    out.flush();
    try {
      String answer = reader().readLine();
      if (answer == null) {
        out.println();
        return Optional.empty();
      }
      return Optional.of(answer.toCharArray());
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** A yes/no question; an empty answer takes {@code defaultYes}, end of input is no. */
  static boolean yes(PrintWriter out, String prompt, boolean defaultYes) {
    Optional<String> answer = line(out, prompt);
    if (answer.isEmpty()) {
      return false;
    }
    String a = answer.get().toLowerCase(Locale.ROOT);
    if (a.isEmpty()) {
      return defaultYes;
    }
    return a.equals("y") || a.equals("yes");
  }

  private static Optional<Console> console() {
    return override.isPresent() ? Optional.empty() : Terminal.console();
  }

  private static synchronized BufferedReader reader() {
    if (override.isPresent()) {
      return override.get();
    }
    if (stdin.isEmpty()) {
      stdin =
          Optional.of(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }
    return stdin.get();
  }
}
