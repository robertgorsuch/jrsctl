package com.jaspersoft.jrsctl.core.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Spec §5.8: a registered secret never survives redaction in raw, Base64 or URL-encoded form. */
class RedactorPropertyTest {

  @Property(tries = 500)
  void should_remove_every_encoding_when_secret_is_registered(
      @ForAll("secrets") String secret,
      @ForAll("surrounding") String prefix,
      @ForAll("surrounding") String suffix) {
    String b64 = Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    String url = URLEncoder.encode(secret, StandardCharsets.UTF_8);
    // a secret that is itself a substring of the mask can never be "removed"
    Assume.that(
        !Redactor.MASK.contains(secret)
            && !Redactor.MASK.contains(b64)
            && !Redactor.MASK.contains(url));
    // a word secret is masked only where it stands alone (issue #199), which the next property
    // covers; any other value is masked whatever touches it
    Assume.that(!Redactor.isWord(secret));

    Redactor redactor = new Redactor();
    assertThat(redactor.register(secret)).isTrue();

    for (String form : List.of(secret, b64, url)) {
      String out = redactor.redact(prefix + form + suffix);
      assertThat(out).doesNotContain(secret).doesNotContain(b64).doesNotContain(url);
      assertThat(out).contains(Redactor.MASK);
    }
  }

  @Property(tries = 500)
  void should_remove_a_word_secret_when_no_word_character_touches_it(
      @ForAll("words") String secret,
      @ForAll("surrounding") String prefix,
      @ForAll("separators") String before,
      @ForAll("separators") String after,
      @ForAll("surrounding") String suffix) {
    Assume.that(!Redactor.MASK.contains(secret));

    Redactor redactor = new Redactor();
    assertThat(redactor.register(secret)).isTrue();

    String out = redactor.redact(prefix + before + secret + after + suffix);
    assertThat(out).contains(before + Redactor.MASK + after);
  }

  @Property(tries = 200)
  void should_leave_text_unchanged_when_it_contains_no_form_of_the_secret(
      @ForAll("secrets") String secret, @ForAll("surrounding") String text) {
    String b64 = Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    String url = URLEncoder.encode(secret, StandardCharsets.UTF_8);
    Assume.that(!text.contains(secret) && !text.contains(b64) && !text.contains(url));
    Assume.that(
        !text.matches(
            "(?is).*(password|passwd|storepass|keypass|authorization|jsessionid|bearer|console\\.token).*"));

    Redactor redactor = new Redactor();
    redactor.register(secret);

    assertThat(redactor.redact(text)).isEqualTo(text);
  }

  @Provide
  Arbitrary<String> secrets() {
    return Arbitraries.strings().withCharRange(' ', '~').ofMinLength(4).ofMaxLength(40);
  }

  @Provide
  Arbitrary<String> words() {
    return Arbitraries.strings().alpha().ofMinLength(4).ofMaxLength(20);
  }

  @Provide
  Arbitrary<String> separators() {
    return Arbitraries.of(" ", ":", "@", "=", "&", "/", ".", "\"", "'", "(", ")", "-", ",", ";");
  }

  @Provide
  Arbitrary<String> surrounding() {
    return Arbitraries.strings().withCharRange(' ', '~').ofMaxLength(30);
  }
}
