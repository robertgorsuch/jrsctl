package com.jaspersoft.jrsctl.core.redact;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.secrets.Secret;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class RedactorTest {

  private final Redactor redactor = new Redactor();

  @Test
  void should_replace_raw_base64_and_url_encoded_forms_when_value_is_registered() {
    String secret = "p@ss w0rd/+=";
    redactor.register(secret);
    String b64 = Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    String url = URLEncoder.encode(secret, StandardCharsets.UTF_8);

    String out = redactor.redact("raw=" + secret + " b64=" + b64 + " url=" + url + " end");

    assertThat(out).isEqualTo("raw=[redacted] b64=[redacted] url=[redacted] end");
  }

  @Test
  void should_replace_the_json_escaped_form_when_value_holds_a_quote_or_backslash() {
    String secret = "ab\"cd\\ef";
    redactor.register(secret);
    // What Jackson writes for that value inside a JSON string, redacted after serialisation.
    String serialised = "{\"detail\":\"password ab\\\"cd\\\\ef rejected\"}";

    String out = redactor.redact(serialised);

    assertThat(out).isEqualTo("{\"detail\":\"password [redacted] rejected\"}");
    assertThat(out).doesNotContain("ab");
  }

  @Test
  void should_register_from_secret_without_keeping_a_reference_when_secret_is_closed() {
    Secret s = Secret.fromString("hunter22");
    assertThat(redactor.register(s)).isTrue();
    s.close();

    assertThat(redactor.redact("pw hunter22!")).isEqualTo("pw [redacted]!");
  }

  @Test
  void should_ignore_values_shorter_than_four_chars_when_registering() {
    assertThat(redactor.register("abc")).isFalse();
    assertThat(redactor.size()).isZero();

    assertThat(redactor.redact("abc abc")).isEqualTo("abc abc");
  }

  @Test
  void should_mask_longer_value_first_when_one_secret_contains_another() {
    redactor.register("hunter2");
    redactor.register("hunter2-extended");

    assertThat(redactor.redact("a hunter2-extended b")).isEqualTo("a [redacted] b");
  }

  @Test
  void should_not_recreate_secret_when_mask_boundary_would_spell_it() {
    redactor.register("cted]Q");

    String out = redactor.redact("x cted]Q Q y");

    assertThat(out).doesNotContain("cted]Q");
  }

  @Test
  void should_mask_password_style_pairs_when_nothing_is_registered() {
    assertThat(redactor.redact("password=hunter2&user=admin"))
        .isEqualTo("password=[redacted]&user=admin");
    assertThat(redactor.redact("passwd: hunter2")).isEqualTo("passwd: [redacted]");
    assertThat(redactor.redact("-storepass \"my store\" -keypass k"))
        .isEqualTo("-storepass \"[redacted]\" -keypass [redacted]");
    assertThat(redactor.redact("keystorepasswd=abc;")).isEqualTo("keystorepasswd=[redacted];");
    assertThat(redactor.redact("JRS_PASSWORD=hunter2")).isEqualTo("JRS_PASSWORD=[redacted]");
    assertThat(redactor.redact("console.token: 0123abcd")).isEqualTo("console.token: [redacted]");
    assertThat(redactor.redact("{\"password\": \"hunter2\"}"))
        .isEqualTo("{\"password\": \"[redacted]\"}");
  }

  @Test
  void should_leave_secret_references_visible_when_they_are_not_values() {
    assertThat(redactor.redact("passwordRef: env:JRS_PASSWORD"))
        .isEqualTo("passwordRef: env:JRS_PASSWORD");
  }

  @Test
  void should_mask_headers_cookies_and_bearer_tokens_when_present() {
    assertThat(redactor.redact("Authorization: Basic amFzcGVyYWRtaW46amFzcGVyYWRtaW4="))
        .isEqualTo("Authorization: [redacted]");
    assertThat(redactor.redact("{\"Authorization\": \"Bearer abc.def\", \"x\": 1}"))
        .isEqualTo("{\"Authorization\": \"[redacted]\", \"x\": 1}");
    assertThat(redactor.redact("Cookie: JSESSIONID=1A2B3C4D; path=/"))
        .isEqualTo("Cookie: JSESSIONID=[redacted]; path=/");
    assertThat(redactor.redact("token Bearer eyJhbGciOi.xx-yy_zz== sent"))
        .isEqualTo("token Bearer [redacted] sent");
  }

  @Test
  void should_return_input_unchanged_when_nothing_matches() {
    redactor.register("hunter2");

    assertThat(redactor.redact("plain progress line 3/7")).isEqualTo("plain progress line 3/7");
    assertThat(redactor.redact("")).isEmpty();
  }

  @Test
  void should_leave_larger_words_readable_when_a_word_secret_occurs_inside_them() {
    // issue #199: the database password was a word that also occurs in the driver's package name
    redactor.register("postgres");

    assertThat(
            redactor.redact(
                "type: postgresql url: jdbc:postgresql://127.0.0.1:5433/jasperserver"
                    + " org.postgresql.util.PSQLException username: jrs_postgres Postgres"))
        .isEqualTo(
            "type: postgresql url: jdbc:postgresql://127.0.0.1:5433/jasperserver"
                + " org.postgresql.util.PSQLException username: jrs_postgres Postgres");
  }

  @Test
  void should_mask_a_word_secret_when_it_stands_alone() {
    redactor.register("postgres");

    assertThat(
            redactor.redact(
                "password=postgres jdbc:postgresql://user:postgres@db/js?pw=postgres&x=1"
                    + " \"postgres\" (postgres) postgres."))
        .isEqualTo(
            "password=[redacted] jdbc:postgresql://user:[redacted]@db/js?pw=[redacted]&x=1"
                + " \"[redacted]\" ([redacted]) [redacted].");
  }

  @Test
  void should_mask_encoded_forms_of_a_word_secret_even_inside_other_text() {
    redactor.register("postgres");
    String b64 = Base64.getEncoder().encodeToString("postgres".getBytes(StandardCharsets.UTF_8));

    assertThat(redactor.redact("h=x" + b64 + "y")).isEqualTo("h=x[redacted]y");
  }

  @Test
  void should_mask_a_secret_that_is_not_a_word_even_inside_other_text() {
    redactor.register("hunter22");

    assertThat(redactor.redact("abchunter22def")).isEqualTo("abc[redacted]def");
  }

  @Test
  void should_treat_only_all_letter_values_as_words_when_registering() {
    assertThat(Redactor.isWord("postgres")).isTrue();
    assertThat(Redactor.isWord("contraseña")).isTrue();
    assertThat(Redactor.isWord("postgres1")).isFalse();
    assertThat(Redactor.isWord("post gres")).isFalse();
    assertThat(Redactor.isWord("")).isFalse();
  }

  @Test
  void should_share_registrations_when_using_global_instance() {
    Redactor g = Redactor.global();
    g.register("global-secret-value");
    try {
      assertThat(Redactor.global().redact("x global-secret-value y")).isEqualTo("x [redacted] y");
    } finally {
      g.clear();
    }
  }
}
