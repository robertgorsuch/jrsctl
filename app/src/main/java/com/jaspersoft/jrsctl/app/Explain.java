package com.jaspersoft.jrsctl.app;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionStrategy;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

/**
 * {@code --explain} for every command (spec §14 Phase 8, §17 "Embedded help"). Invariants: the
 * option is added once, programmatically, to every command in the tree (including commands added in
 * later phases), so no command class declares it; when it is matched anywhere on the command line
 * the long-form explanation of the deepest command named is printed to that command's standard
 * output and the process exits 0 without constructing a {@code Bootstrap}, touching the home or
 * running any command code (required options and positional parameters are not validated, exactly
 * as for {@code --help}); the text is the command's {@code ### `jrsctl <path> ...`} section of the
 * embedded operator guide, so the explanation and the documentation cannot drift apart; a group
 * command prints the sections of all its visible subcommands and the root prints every visible
 * command section; a hidden command (bundle authoring, #62) is explained only when named; on a
 * terminal the section is rendered as plain text, and piped it stays the guide's Markdown (#60).
 */
final class Explain {

  static final String OPTION = "--explain";

  /**
   * Leading command-path words of a heading: lowercase identifiers, never a flag or placeholder.
   */
  private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9-]*");

  private static final Pattern BLANK = Pattern.compile("\\s+");

  private static final Pattern BLANK_LINE = Pattern.compile("\\R");

  private Explain() {}

  /** Adds {@code --explain} to every command under {@code root} and installs the strategy. */
  static CommandLine install(CommandLine root) {
    addOption(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    root.setExecutionStrategy(new Strategy());
    return root;
  }

  private static void addOption(CommandLine cmd, Set<CommandSpec> seen) {
    CommandSpec spec = cmd.getCommandSpec();
    if (seen.add(spec) && spec.findOption(OPTION) == null) {
      spec.addOption(
          OptionSpec.builder(OPTION)
              .help(true)
              .description(
                  "Explain this command: what it does and mutates, how it rolls back, its exit"
                      + " codes and flags; then exit without running it.")
              .build());
    }
    for (CommandLine sub : cmd.getSubcommands().values()) {
      addOption(sub, seen);
    }
  }

  /** The explanation for a command path such as {@code "hotfix apply"} ({@code ""} = root). */
  static Optional<String> text(String commandPath) {
    return text(commandPath, Set.of());
  }

  /**
   * As {@link #text(String)}, leaving out the sections of {@code hidden} command paths unless the
   * path names one of them exactly.
   */
  static Optional<String> text(String commandPath, Set<String> hidden) {
    Map<String, Section> sections = sections();
    Section exact = sections.get(commandPath);
    if (exact != null) {
      return Optional.of(exact.render());
    }
    String prefix = commandPath.isEmpty() ? "" : commandPath + " ";
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Section> e : sections.entrySet()) {
      if (e.getKey().startsWith(prefix) && !hiddenUnder(e.getKey(), hidden)) {
        if (sb.length() > 0) {
          sb.append(System.lineSeparator());
        }
        sb.append(e.getValue().render());
      }
    }
    return sb.length() == 0 ? Optional.empty() : Optional.of(sb.toString());
  }

  /** True when {@code path} is a hidden command or lies under one. */
  private static boolean hiddenUnder(String path, Set<String> hidden) {
    return hidden.stream().anyMatch(h -> path.equals(h) || path.startsWith(h + " "));
  }

  /** Relative paths (e.g. {@code "hotfix build"}) of every hidden command under {@code cmd}. */
  static Set<String> hiddenPaths(CommandLine cmd) {
    Set<String> out = new TreeSet<>();
    collectHidden(cmd, out, Collections.newSetFromMap(new IdentityHashMap<>()));
    return out;
  }

  private static void collectHidden(CommandLine cmd, Set<String> out, Set<CommandSpec> seen) {
    CommandSpec spec = cmd.getCommandSpec();
    if (!seen.add(spec)) {
      return;
    }
    if (spec.usageMessage().hidden() && spec.parent() != null) {
      out.add(relative(spec.qualifiedName(" ")));
    }
    for (CommandLine sub : cmd.getSubcommands().values()) {
      collectHidden(sub, out, seen);
    }
  }

  private static String relative(String qualifiedName) {
    return qualifiedName.equals("jrsctl") ? "" : qualifiedName.substring("jrsctl ".length());
  }

  /** True when the guide has a section whose heading names exactly this command path. */
  static boolean hasSection(String commandPath) {
    return sections().containsKey(commandPath);
  }

  /** Every {@code ### `jrsctl ...`} section of the embedded operator guide, keyed by path. */
  static Map<String, Section> sections() {
    List<String> lines = EmbeddedDocs.lines("operator-guide").orElse(List.of());
    Map<String, Section> out = new LinkedHashMap<>();
    String key = null;
    String heading = null;
    List<String> body = new ArrayList<>();
    for (String line : lines) {
      if (line.startsWith("#") && !line.startsWith("####")) {
        if (key != null) {
          out.putIfAbsent(key, new Section(heading, List.copyOf(body)));
        }
        key = null;
        body = new ArrayList<>();
        if (line.startsWith("### ")) {
          Optional<String> path = commandPath(line.substring(4));
          if (path.isPresent()) {
            key = path.get();
            heading = line.substring(4).trim();
          }
        }
      } else if (key != null) {
        body.add(line);
      }
    }
    if (key != null) {
      out.putIfAbsent(key, new Section(heading, List.copyOf(body)));
    }
    return out;
  }

  /** {@code `jrsctl hotfix apply <bundle> [--plan]`} becomes {@code "hotfix apply"}. */
  static Optional<String> commandPath(String headingText) {
    String text = headingText.replace("`", "").trim();
    if (!text.equals("jrsctl") && !text.startsWith("jrsctl ")) {
      return Optional.empty();
    }
    List<String> words = new ArrayList<>();
    for (String token : BLANK.splitAsStream(text.substring("jrsctl".length()).trim()).toList()) {
      if (token.isEmpty() || !WORD.matcher(token).matches()) {
        break;
      }
      words.add(token);
    }
    return Optional.of(String.join(" ", words));
  }

  /** One command section of the guide: the heading text (without {@code ###}) and its body. */
  record Section(String heading, List<String> body) {
    String render() {
      StringBuilder sb = new StringBuilder();
      sb.append(heading.replace("`", "")).append(System.lineSeparator());
      sb.append("=".repeat(Math.min(heading.replace("`", "").length(), 100)))
          .append(System.lineSeparator());
      int start = 0;
      int end = body.size();
      while (start < end && body.get(start).isBlank()) {
        start++;
      }
      while (end > start && body.get(end - 1).isBlank()) {
        end--;
      }
      for (String line : body.subList(start, end)) {
        sb.append(line).append(System.lineSeparator());
      }
      return sb.toString();
    }
  }

  /**
   * Prints the explanation and exits 0 when {@code --explain} was given anywhere; otherwise runs
   * the last command like picocli's default {@link CommandLine.RunLast}.
   */
  static final class Strategy implements IExecutionStrategy {
    @Override
    public int execute(ParseResult parseResult) {
      Integer help = CommandLine.executeHelpRequest(parseResult);
      if (help != null) {
        return help;
      }
      if (!requested(parseResult)) {
        return new CommandLine.RunLast().execute(parseResult);
      }
      ParseResult deepest = parseResult;
      while (deepest.hasSubcommand()) {
        deepest = deepest.subcommand();
      }
      CommandLine target = deepest.commandSpec().commandLine();
      String path = deepest.commandSpec().qualifiedName(" ");
      Optional<String> text = text(relative(path), hiddenPaths(root(target)));
      if (text.isEmpty()) {
        PrintWriter err = target.getErr();
        err.println(
            "error: no explanation is embedded for '"
                + path
                + "'; see `jrsctl docs operator-guide` or `"
                + path
                + " --help`");
        err.flush();
        return ExitCodes.USAGE;
      }
      PrintWriter out = target.getOut();
      // #60: Markdown is unreadable in a terminal; piped output keeps it for tools and tests
      if (Terminal.present()) {
        // issue #187: a long section is paged unless --no-pager was given at any level
        Pager.print(
            TerminalMarkdown.render(
                BLANK_LINE.splitAsStream(text.get()).toList(), TerminalMarkdown.width(Env.vars())),
            out,
            !noPager(parseResult));
      } else {
        out.print(text.get());
        out.flush();
      }
      return ExitCodes.SUCCESS;
    }

    private static boolean noPager(ParseResult parseResult) {
      for (ParseResult pr = parseResult; pr != null; pr = pr.subcommand()) {
        if (pr.hasMatchedOption("--no-pager")) {
          return true;
        }
      }
      return false;
    }

    private static CommandLine root(CommandLine cmd) {
      CommandLine top = cmd;
      while (top.getParent() != null) {
        top = top.getParent();
      }
      return top;
    }

    private static boolean requested(ParseResult pr) {
      for (ParseResult p = pr; p != null; p = p.hasSubcommand() ? p.subcommand() : null) {
        if (p.hasMatchedOption(OPTION)) {
          return true;
        }
      }
      return false;
    }
  }
}
