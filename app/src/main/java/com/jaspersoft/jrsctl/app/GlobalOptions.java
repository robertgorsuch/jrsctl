package com.jaspersoft.jrsctl.app;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Option;

/**
 * Options every command accepts (spec §5.1, §5.2, §5.5), mixed into each subcommand so they may be
 * given after the command name. Invariants: {@code --set} keys are dotted configuration paths and
 * take precedence over environment and file; {@code --yes}/{@code --non-interactive} makes every
 * prompt fail closed instead of waiting; {@code --json} switches the command's stdout to a single
 * JSON document; colour is decided by {@link Ansi}, never here.
 */
public final class GlobalOptions {

  @Option(
      names = "--home",
      paramLabel = "<dir>",
      description =
          "jrsctl home directory: backups, snapshots, state and logs live here (default:"
              + " $JRSCTL_HOME, else /var/lib/jrsctl or %%ProgramData%%\\jrsctl, else ~/.jrsctl"
              + " when that does not exist). Put it on a volume with room for a full backup.")
  Path home;

  @Option(
      names = "--set",
      paramLabel = "key=value",
      description = "Override a configuration key (dotted path), e.g. --set server.baseUrl=...")
  Map<String, String> set = new LinkedHashMap<>();

  @Option(
      names = "--passphrase-file",
      paramLabel = "<file>",
      description = "File holding the passphrase for secrets.enc (non-interactive unlock).")
  Path passphraseFile;

  @Option(
      names = "--yes",
      description = "Answer yes to confirmations without asking; implies --non-interactive.")
  boolean yes;

  @Option(
      names = "--non-interactive",
      description =
          "Never prompt; exit 2 where a confirmation or a passphrase is needed. Does not confirm"
              + " anything: pair it with --yes to run a plan unattended.")
  boolean nonInteractive;

  /** When to colour text output (review 3.5). */
  public enum Color {
    AUTO,
    ALWAYS,
    NEVER
  }

  @Option(
      names = "--color",
      paramLabel = "<when>",
      description =
          "Colour text output: auto (a terminal that understands ANSI), always, never."
              + " Default: ${DEFAULT-VALUE}.")
  Color color = Color.AUTO;

  @Option(names = "--no-color", description = "Disable ANSI colour in text output (--color=never).")
  boolean noColor;

  @Option(
      names = "--ascii",
      description =
          "Use ASCII only in text output; the default uses tick and arrow glyphs when the output"
              + " encoding can carry them.")
  boolean ascii;

  @Option(names = "--json", description = "Emit the result as JSON instead of text.")
  boolean json;

  @Option(
      names = "--no-pager",
      description = "Print long --explain text at once instead of a screen at a time.")
  boolean noPager;

  public Optional<Path> home() {
    return Optional.ofNullable(home);
  }

  public Map<String, String> set() {
    return Map.copyOf(set);
  }

  public Optional<Path> passphraseFile() {
    return Optional.ofNullable(passphraseFile);
  }

  /** True only for {@code --yes}: the one flag that answers a confirmation (review 4.4). */
  public boolean yes() {
    return yes;
  }

  /** True when no prompt may be shown: {@code --non-interactive}, or {@code --yes} implying it. */
  public boolean nonInteractive() {
    return nonInteractive || yes;
  }

  public boolean noColor() {
    return noColor;
  }

  /** {@code --color} with {@code --no-color} folded in as {@code never} (review 3.5). */
  public Color color() {
    return noColor ? Color.NEVER : color;
  }

  /** True when the operator asked for ASCII-only output (review 3.5). */
  public boolean ascii() {
    return ascii;
  }

  public boolean json() {
    return json;
  }
}
