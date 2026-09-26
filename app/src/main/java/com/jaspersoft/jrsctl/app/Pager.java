package com.jaspersoft.jrsctl.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jline.terminal.Attributes;

/**
 * A small built-in pager for long plain-text output in a terminal (issue #187). Invariants: text
 * that fits on one screen, or any output that is not going to a terminal, is printed at once and
 * unchanged; paging only reads single keys (Space a page, Enter a line, {@code q}, end of input or
 * Ctrl-C to stop), never spawns {@code less} or {@code more}, and uses the same JNI terminal as the
 * guided menu ({@link SystemTerminal}, no JNA, ADR-0037); the prompt line is erased before more
 * text is printed or the pager returns, so what stays on screen is the text itself.
 */
final class Pager {

  static final String PROMPT = "-- more (%d%%) --  Space: next page, Enter: next line, q: quit";

  /** Screen height when neither the terminal nor {@code LINES} gives one. */
  static final int DEFAULT_HEIGHT = 24;

  private static final int CTRL_C = 3;

  /** One key press, or -1 at the end of input. */
  @FunctionalInterface
  interface Keys {
    int next() throws IOException;
  }

  private Pager() {}

  /**
   * Prints {@code text}, paging it when {@code paging} is true, a terminal is attached and the text
   * is taller than the screen; otherwise exactly as {@code out.print(text)} would.
   */
  static void print(String text, PrintWriter out, boolean paging) {
    if (!paging) {
      out.print(text);
      out.flush();
      return;
    }
    Optional<org.jline.terminal.Terminal> terminal = SystemTerminal.get();
    List<String> lines = lines(text);
    int height = height(terminal.map(org.jline.terminal.Terminal::getHeight).orElse(0), Env.vars());
    if (terminal.isEmpty() || lines.size() < height) {
      out.print(text);
      out.flush();
      return;
    }
    org.jline.terminal.Terminal t = terminal.get();
    page(lines, height, () -> readKey(t), out);
  }

  /**
   * Shows {@code lines} a screen at a time: {@code height - 1} lines, then the prompt on the last
   * row. Text that fits is printed without a prompt.
   */
  static void page(List<String> lines, int height, Keys keys, PrintWriter out) {
    int rows = Math.max(1, height - 1);
    int shown = Math.min(rows, lines.size());
    lines.subList(0, shown).forEach(out::println);
    while (shown < lines.size()) {
      String prompt = String.format(PROMPT, shown * 100 / lines.size());
      out.print(prompt);
      out.flush();
      int key;
      try {
        key = keys.next();
      } catch (IOException e) {
        key = -1;
      }
      out.print("\r" + " ".repeat(prompt.length()) + "\r");
      int more =
          switch (key) {
            case ' ', 'f' -> rows;
            case '\r', '\n', 'j' -> 1;
            case 'q', 'Q', CTRL_C, -1 -> -1;
            default -> 0;
          };
      if (more < 0) {
        break;
      }
      int end = Math.min(lines.size(), shown + more);
      lines.subList(shown, end).forEach(out::println);
      shown = end;
    }
    out.flush();
  }

  /** The text's lines, without the empty one a trailing newline leaves. */
  static List<String> lines(String text) {
    List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
    if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1);
    }
    return lines;
  }

  /**
   * The terminal's height when it reports one, else {@code LINES}, else {@link #DEFAULT_HEIGHT}.
   */
  static int height(int reported, Map<String, String> env) {
    if (reported > 1) {
      return reported;
    }
    return Optional.ofNullable(env.get("LINES"))
        .map(String::strip)
        .filter(v -> v.matches("\\d{1,4}"))
        .map(Integer::parseInt)
        .filter(h -> h > 1)
        .orElse(DEFAULT_HEIGHT);
  }

  /** One key in raw mode, restoring the terminal's settings straight after. */
  private static int readKey(org.jline.terminal.Terminal terminal) throws IOException {
    Attributes previous = terminal.enterRawMode();
    try {
      return terminal.reader().read();
    } finally {
      terminal.setAttributes(previous);
    }
  }
}
