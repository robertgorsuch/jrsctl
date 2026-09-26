package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** Spec §14 Phase 8 "offline docs embedded": {@code jrsctl docs} lists and prints them. */
class DocsCommandTest {

  @Test
  void should_list_every_embedded_document_when_no_name_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    assertThat(cmd.execute("docs")).isZero();
    assertThat(out.toString())
        .contains("operator-guide")
        .contains("jrsctl operator guide")
        .contains("hotfix-authoring")
        .contains("security")
        .contains("readme");
  }

  /**
   * Field test 2, H3: the authoring guide is for authors, and the listing says so and puts it last.
   */
  @Test
  void should_list_the_authoring_guide_last_and_mark_it_for_authors() {
    StringWriter out = new StringWriter();
    picocli.CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));

    assertThat(cmd.execute("docs")).isZero();
    String text = out.toString();
    assertThat(text).contains("(for bundle authors)");
    assertThat(text.indexOf("hotfix-authoring"))
        .isGreaterThan(text.indexOf("readme"))
        .isGreaterThan(text.indexOf("security"));
  }

  @Test
  void should_emit_json_array_when_json_flag_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    assertThat(cmd.execute("docs", "--json")).isZero();
    String json = out.toString().trim();
    assertThat(json).startsWith("[").endsWith("]");
    assertThat(json).contains("\"name\" : \"operator-guide\"").contains("\"bytes\" :");
    assertThat(json).contains("\"title\" : \"jrsctl operator guide\"");
  }

  @Test
  void should_print_document_when_name_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    assertThat(cmd.execute("docs", "hotfix-authoring")).isZero();
    assertThat(out.toString())
        .startsWith("# jrsctl hotfix authoring guide")
        .contains("manifest.json");
  }

  /** Issue #60: a document can be read as plain text, for example through a pager. */
  @Test
  void should_print_plain_text_without_markup_when_format_text_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    assertThat(cmd.execute("docs", "operator-guide", "--format", "text")).isZero();
    assertThat(out.toString())
        .startsWith("jrsctl operator guide" + System.lineSeparator() + "=====")
        .contains("jrsctl hotfix apply")
        .doesNotContain("### ")
        .doesNotContain("|---")
        .doesNotContain("**");
  }

  /**
   * Issue #187: without a terminal nothing is paged, so the text is the same with or without
   * --no-pager, and the flag is accepted on docs and beside --explain on any command.
   */
  @Test
  void should_print_the_same_text_unpaged_when_no_terminal_or_no_pager_is_given() {
    StringWriter plain = new StringWriter();
    CommandLine a = Main.commandLine();
    a.setOut(new PrintWriter(plain));
    assertThat(a.execute("docs", "operator-guide", "--format", "text")).isZero();

    StringWriter noPager = new StringWriter();
    CommandLine b = Main.commandLine();
    b.setOut(new PrintWriter(noPager));
    assertThat(b.execute("docs", "operator-guide", "--format", "text", "--no-pager")).isZero();

    assertThat(noPager.toString()).isEqualTo(plain.toString()).doesNotContain("-- more (");

    StringWriter explained = new StringWriter();
    CommandLine c = Main.commandLine();
    c.setOut(new PrintWriter(explained));
    assertThat(c.execute("runs", "list", "--explain", "--no-pager")).isZero();
    assertThat(explained.toString()).contains("runs list").doesNotContain("-- more (");
  }

  @Test
  void should_print_the_markdown_source_when_format_markdown_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));
    assertThat(cmd.execute("docs", "operator-guide", "--format", "markdown")).isZero();
    assertThat(out.toString()).startsWith("# jrsctl operator guide").contains("### `jrsctl");
  }

  @Test
  void should_exit_usage_and_list_names_when_name_unknown() {
    StringWriter err = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setErr(new PrintWriter(err));
    assertThat(cmd.execute("docs", "no-such-doc")).isEqualTo(ExitCodes.USAGE);
    assertThat(err.toString()).contains("no-such-doc").contains("operator-guide");
  }

  @Test
  void should_report_size_of_embedded_document_when_described() {
    EmbeddedDocs.Doc doc = EmbeddedDocs.describe("security").orElseThrow();
    assertThat(doc.title()).isEqualTo("jrsctl security notes");
    assertThat(doc.bytes()).isGreaterThan(1000);
    assertThat(EmbeddedDocs.describe("nope")).isEmpty();
  }
}
