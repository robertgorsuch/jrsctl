package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.state.StateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #186: a run can be named by any unambiguous part of its id. */
class RunRefTest {

  private static final String A = "r-20260901-100000-ab12";
  private static final String B = "r-20260902-110000-cd34";
  private static final String C = "r-20260902-120000-ef56";

  @TempDir Path tmp;

  private StateStore store;

  @BeforeEach
  void setUp() {
    store = StateStore.open(new JrsctlHome(tmp), Clock.systemUTC());
    Instant t = Instant.parse("2026-09-01T10:00:00Z");
    for (String id : List.of(A, B, C)) {
      store.recordRunStart(id, "hotfix.apply", Optional.empty(), t);
    }
  }

  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    store.close();
  }

  private String found(String given) {
    RunRef.Resolved r = RunRef.resolve(store, given);
    assertThat(r).as(given).isInstanceOf(RunRef.Resolved.Found.class);
    return ((RunRef.Resolved.Found) r).run().runId();
  }

  @Test
  void should_find_the_run_when_the_full_id_is_given() {
    assertThat(found(A)).isEqualTo(A);
  }

  @Test
  void should_find_the_run_when_its_trailing_hex_is_given() {
    assertThat(found("ab12")).isEqualTo(A);
    assertThat(found("-cd34")).isEqualTo(B);
  }

  @Test
  void should_find_the_run_when_a_leading_part_is_given_with_or_without_the_prefix() {
    assertThat(found("20260901")).isEqualTo(A);
    assertThat(found("r-20260902-12")).isEqualTo(C);
  }

  @Test
  void should_list_the_candidates_when_the_part_matches_more_than_one_run() {
    RunRef.Resolved r = RunRef.resolve(store, "20260902");

    assertThat(r).isInstanceOf(RunRef.Resolved.Ambiguous.class);
    assertThat(((RunRef.Resolved.Ambiguous) r).ids()).containsExactlyInAnyOrder(B, C);
  }

  @Test
  void should_report_unknown_when_nothing_matches() {
    assertThat(RunRef.resolve(store, "zz99")).isInstanceOf(RunRef.Resolved.Unknown.class);
    assertThat(RunRef.resolve(store, " ")).isInstanceOf(RunRef.Resolved.Unknown.class);
  }
}
