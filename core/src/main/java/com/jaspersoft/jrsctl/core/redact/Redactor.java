package com.jaspersoft.jrsctl.core.redact;

import com.jaspersoft.jrsctl.core.secrets.Secret;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secrets from any text that leaves the process (spec §5.8). Two mechanisms: values
 * {@linkplain #register registered} at runtime are replaced in their raw, standard Base64 (padded
 * and unpadded), URL-encoded and JSON-string-escaped forms, and well-known patterns ({@code
 * password=}, {@code Authorization:}, {@code JSESSIONID=}, {@code Bearer <token>}, keystore
 * passwords, a 1.x console token) are masked even when nobody registered the value. Invariants:
 * thread-safe; longer values are replaced before shorter ones so a secret that contains another is
 * never left half-masked; replacement repeats until no registered form remains, so a mask boundary
 * can never re-create a secret; values shorter than four characters are ignored because masking
 * them would destroy the surrounding text. A value made only of letters (a word such as {@code
 * postgres}) is masked in its raw form only where it stands alone, not where a letter, digit or
 * underscore touches it, so {@code org.postgresql} and {@code jdbc:postgresql:} stay readable and
 * the mask does not spell out what it hides (issue #199); any other value, and every encoded form,
 * is masked wherever it occurs. Registered values are held as strings for matching, which is the
 * one place the process keeps a secret in immutable memory; the set is never exposed.
 */
public final class Redactor {

  public static final String MASK = "[redacted]";
  public static final int MIN_LENGTH = 4;

  private static final int MAX_PASSES = 4;
  private static final Redactor GLOBAL = new Redactor();

  private static final String QUOTED_OR_BARE = "(\"[^\"]*\"|'[^']*'|[^\\s&;,\"']+)";
  private static final List<Pattern> KEY_VALUE_RULES =
      List.of(
          // key=value / key: value, also as a quoted JSON key
          Pattern.compile(
              "(?i)((?:password|passwd|keystorepasswd|storepass|keypass|console\\.token)"
                  + "[\"']?\\s*[=:]\\s*)"
                  + QUOTED_OR_BARE),
          // keytool style: -storepass x -keypass y
          Pattern.compile("(?i)((?:keystorepasswd|storepass|keypass)\\s+)" + QUOTED_OR_BARE),
          Pattern.compile("(?i)((?:JSESSIONID)[\"']?\\s*[=:]\\s*)([^\\s;,\"']+)"),
          Pattern.compile("(?i)(authorization[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\r\\n]+)"),
          Pattern.compile("(?i)(\\bBearer\\s+)([A-Za-z0-9\\-._~+/]+=*)"));

  private static final String MASK_REPLACEMENT = Matcher.quoteReplacement(MASK);

  /** A character that makes an adjacent occurrence of a word secret part of a larger word. */
  private static final String WORD_CHAR = "[\\p{L}\\p{N}_]";

  private final Object lock = new Object();
  private final Set<String> raw = new LinkedHashSet<>();
  private volatile List<Form> forms = List.of();

  /**
   * One text to mask and the pattern, compiled at registration, that finds it: anywhere, or for a
   * word secret's raw form only where no word character touches it.
   */
  private record Form(String text, Pattern pattern) {
    static Form of(String text, boolean whole) {
      String quoted = Pattern.quote(text);
      return new Form(
          text,
          Pattern.compile(
              whole ? "(?<!" + WORD_CHAR + ")" + quoted + "(?!" + WORD_CHAR + ")" : quoted));
    }

    String apply(String in) {
      return pattern.matcher(in).replaceAll(MASK_REPLACEMENT);
    }
  }

  public Redactor() {}

  /** The process-wide instance used by the logback converter. */
  public static Redactor global() {
    return GLOBAL;
  }

  /** Registers the secret's value; false when it is too short to be masked safely. */
  public boolean register(Secret secret) {
    Objects.requireNonNull(secret, "secret");
    char[] chars = secret.chars();
    try {
      return register(new String(chars));
    } finally {
      Arrays.fill(chars, '\0');
    }
  }

  /** Registers a value; false when it is too short to be masked safely. */
  public boolean register(String value) {
    Objects.requireNonNull(value, "value");
    if (value.length() < MIN_LENGTH) {
      return false;
    }
    synchronized (lock) {
      if (!raw.add(value)) {
        return true;
      }
      // a text that is a word secret's raw form and also any other form is masked everywhere
      Map<String, Boolean> all = new LinkedHashMap<>();
      for (String v : raw) {
        boolean word = isWord(v);
        for (String f : formsOf(v)) {
          all.merge(f, word && f.equals(v), Boolean::logicalAnd);
        }
      }
      List<Form> sorted = new ArrayList<>(all.size());
      all.forEach((text, whole) -> sorted.add(Form.of(text, whole)));
      sorted.sort(
          Comparator.comparingInt((Form f) -> f.text().length())
              .reversed()
              .thenComparing(Form::text));
      forms = List.copyOf(sorted);
    }
    return true;
  }

  /** Number of registered values. */
  public int size() {
    synchronized (lock) {
      return raw.size();
    }
  }

  /** Forgets every registered value; for tests that share {@link #global()}. */
  public void clear() {
    synchronized (lock) {
      raw.clear();
      forms = List.of();
    }
  }

  /** The text with every registered form and every pattern-matched secret replaced by the mask. */
  public String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text == null ? "" : text;
    }
    String out = text;
    List<Form> snapshot = forms;
    if (!snapshot.isEmpty()) {
      for (int pass = 0; pass < MAX_PASSES; pass++) {
        String before = out;
        for (Form form : snapshot) {
          out = form.apply(out);
        }
        if (out.equals(before)) {
          break;
        }
      }
    }
    for (Pattern rule : KEY_VALUE_RULES) {
      out = rule.matcher(out).replaceAll(Redactor::maskValue);
    }
    return out;
  }

  private static String maskValue(MatchResult m) {
    String value = m.group(2);
    String masked;
    if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
      masked = "\"" + MASK + "\"";
    } else if (value.length() >= 2
        && value.charAt(0) == '\''
        && value.charAt(value.length() - 1) == '\'') {
      masked = "'" + MASK + "'";
    } else {
      masked = MASK;
    }
    return Matcher.quoteReplacement(m.group(1) + masked);
  }

  /** True for a value made only of letters, which is masked as a whole word (issue #199). */
  static boolean isWord(String value) {
    return !value.isEmpty() && value.codePoints().allMatch(Character::isLetter);
  }

  private static List<String> formsOf(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    List<String> out = new ArrayList<>(5);
    out.add(value);
    out.add(Base64.getEncoder().encodeToString(bytes));
    out.add(Base64.getEncoder().withoutPadding().encodeToString(bytes));
    out.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
    // The JSON outputs (--json, the support bundle) serialise first and redact
    // the text afterwards, so a value holding a quote or a backslash appears there in its escaped
    // form (assessment item S1). Only those two characters are escaped by default for a string.
    String json = value.replace("\\", "\\\\").replace("\"", "\\\"");
    if (!json.equals(value)) {
      out.add(json);
    }
    return out;
  }
}
