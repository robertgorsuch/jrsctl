package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Issue #187: long plain text is paged a screen at a time in a terminal. */
class PagerTest {

  private static final List<String> TWENTY =
      IntStream.rangeClosed(1, 20).mapToObj(i -> "line " + i).toList();

  private final StringWriter buffer = new StringWriter();
  private final PrintWriter out = new PrintWriter(buffer);

  /** Replays {@code keys}, then reports the end of input. */
  private static Pager.Keys keys(int... keys) {
    Deque<Integer> queue = new ArrayDeque<>();
    for (int k : keys) {
      queue.add(k);
    }
    return () -> queue.isEmpty() ? -1 : queue.poll();
  }

  /** What stays on screen: every line printed, with the erased prompts taken out. */
  private List<String> shown() {
    // the prompt is erased with a bare \r, which String.lines() would treat as a line break
    String kept = buffer.toString().replaceAll("-- more \\(\\d+%\\) --[^\\r]*\\r +\\r", "");
    return Pager.lines(kept);
  }

  private long prompts() {
    return buffer.toString().split("-- more \\(", -1).length - 1L;
  }

  @Test
  void should_print_text_that_fits_on_one_screen_without_a_prompt() {
    Pager.page(TWENTY.subList(0, 5), 10, keys(), out);

    assertThat(shown()).containsExactlyElementsOf(TWENTY.subList(0, 5));
    assertThat(prompts()).isZero();
  }

  @Test
  void should_not_page_text_exactly_one_screen_less_the_prompt_row() {
    Pager.page(TWENTY.subList(0, 9), 10, keys(), out);

    assertThat(prompts()).isZero();
    assertThat(shown()).hasSize(9);
  }

  @Test
  void should_show_a_page_then_the_next_page_on_space_and_one_line_on_enter() {
    Pager.page(TWENTY, 10, keys(' ', '\r', 'q'), out);

    assertThat(shown()).containsExactlyElementsOf(TWENTY.subList(0, 19));
    assertThat(buffer.toString()).contains("-- more (45%) --").contains("-- more (90%) --");
  }

  @Test
  void should_stop_early_on_q_and_at_the_end_of_input() {
    Pager.page(TWENTY, 10, keys('q'), out);
    assertThat(shown()).containsExactlyElementsOf(TWENTY.subList(0, 9));

    buffer.getBuffer().setLength(0);
    Pager.page(TWENTY, 10, keys(), out);
    assertThat(shown()).containsExactlyElementsOf(TWENTY.subList(0, 9));
  }

  @Test
  void should_ignore_other_keys_and_reach_the_end_without_a_final_prompt() {
    Pager.page(TWENTY, 10, keys('x', ' ', ' ', ' '), out);

    assertThat(shown()).containsExactlyElementsOf(TWENTY);
    assertThat(prompts()).isEqualTo(3);
  }

  @Test
  void should_erase_the_prompt_before_printing_more() {
    Pager.page(TWENTY, 10, keys(' ', 'q'), out);

    assertThat(buffer.toString()).contains("\r" + " ".repeat(10));
    assertThat(shown()).noneMatch(l -> l.contains("-- more"));
  }

  @Test
  void should_split_lines_without_the_empty_one_after_a_trailing_newline() {
    assertThat(Pager.lines("a\nb\n")).containsExactly("a", "b");
    assertThat(Pager.lines("a\r\nb")).containsExactly("a", "b");
  }

  @Test
  void should_take_the_height_from_the_terminal_then_lines_then_the_default() {
    assertThat(Pager.height(50, Map.of("LINES", "30"))).isEqualTo(50);
    assertThat(Pager.height(0, Map.of("LINES", "30"))).isEqualTo(30);
    assertThat(Pager.height(0, Map.of("LINES", "tall"))).isEqualTo(Pager.DEFAULT_HEIGHT);
    assertThat(Pager.height(0, Map.of())).isEqualTo(Pager.DEFAULT_HEIGHT);
  }

  @Test
  void should_print_unchanged_when_paging_is_off() {
    Pager.print("a\nb\n", out, false);

    assertThat(buffer.toString()).isEqualTo("a\nb\n");
  }
}
