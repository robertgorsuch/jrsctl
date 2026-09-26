package com.jaspersoft.jrsctl.core.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationsTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);

  @TempDir Path tmp;

  /** A downgraded binary must not read and write a schema it does not know (assessment item E6). */
  @Test
  void should_refuse_a_database_written_by_a_newer_build() throws Exception {
    Path db = tmp.resolve("state.db");
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
      Migrations.apply(c, CLOCK, List.of("V001__init.sql"));
      try (Statement s = c.createStatement()) {
        s.execute(
            "INSERT INTO schema_version(version, name, applied_at)"
                + " VALUES (99, 'V099__future.sql', '2026-09-13T00:00:00Z')");
      }

      assertThatThrownBy(() -> Migrations.apply(c, CLOCK, List.of("V001__init.sql")))
          .isInstanceOf(StateStoreException.class)
          .hasMessageContaining("99")
          .hasMessageContaining("newer than this build");
    }
  }

  /**
   * Review findings 1.14 and 1.15: rows written before V002 carry variable-width timestamps and no
   * path key. The migration rewrites the former at millisecond precision and fills the latter, so
   * an existing installation orders and matches correctly after the upgrade.
   */
  @Test
  void should_normalise_legacy_timestamps_and_fill_path_keys_when_migrating_from_v1()
      throws Exception {
    Path db = tmp.resolve("state.db");
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
      Migrations.apply(c, CLOCK, List.of("V001__init.sql"));
      try (Statement s = c.createStatement()) {
        s.execute(
            "INSERT INTO hotfixes_installed(id, version, title, installed_run_id, state,"
                + " installed_at) VALUES ('HF-1','1','t','r1','INSTALLED','2026-09-08T10:15:00Z')");
        s.execute(
            "INSERT INTO hotfixes_installed(id, version, title, installed_run_id, state,"
                + " installed_at) VALUES ('HF-2','1','t','r1','INSTALLED',"
                + "'2026-09-08T10:15:00.500123Z')");
        s.execute(
            "INSERT INTO hotfix_files(hotfix_id, path, action)"
                + " VALUES ('HF-1', '/opt/jrs/WEB-INF/lib/foo.jar', 'replace')");
        s.execute(
            "INSERT INTO customizations(path, original_sha256, registered_at)"
                + " VALUES ('/opt/jrs/WEB-INF/classes/x.properties', 'aa',"
                + " '2026-09-08T10:15:00.500000000Z')");
      }

      Migrations.apply(c, CLOCK);

      assertThat(Migrations.currentVersion(c)).isEqualTo(3);
      // ADR-0030 (issue #99): every row written before V003 was applied by jrsctl itself
      try (Statement s = c.createStatement();
          var rs = s.executeQuery("SELECT origin FROM hotfixes_installed WHERE id='HF-1'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("origin")).isEqualTo("JRSCTL");
      }
      try (Statement s = c.createStatement();
          ResultSet rs =
              s.executeQuery(
                  "SELECT id, installed_at FROM hotfixes_installed ORDER BY installed_at")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("HF-1");
        assertThat(rs.getString(2)).isEqualTo("2026-09-08T10:15:00.000Z");
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("HF-2");
        assertThat(rs.getString(2)).isEqualTo("2026-09-08T10:15:00.500Z");
      }
      try (Statement s = c.createStatement();
          ResultSet rs = s.executeQuery("SELECT path_key FROM hotfix_files")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1))
            .isEqualTo(PathKeys.key(Path.of("/opt/jrs/WEB-INF/lib/foo.jar")));
      }
      try (Statement s = c.createStatement();
          ResultSet rs = s.executeQuery("SELECT path_key, registered_at FROM customizations")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1))
            .isEqualTo(PathKeys.key(Path.of("/opt/jrs/WEB-INF/classes/x.properties")));
        assertThat(rs.getString(2)).isEqualTo("2026-09-08T10:15:00.500Z");
      }
    }
  }

  @Test
  void should_fold_case_only_where_the_file_system_does_when_building_a_key() {
    String key = PathKeys.key(Path.of("/opt/JRS/WEB-INF/./lib/../lib/Foo.jar"));

    assertThat(key).doesNotContain("\\").doesNotContain("/./").doesNotContain("/../");
    if (Path.of("A").equals(Path.of("a"))) {
      assertThat(key).endsWith("/opt/jrs/web-inf/lib/foo.jar");
    } else {
      assertThat(key).endsWith("/opt/JRS/WEB-INF/lib/Foo.jar");
    }
  }
}
