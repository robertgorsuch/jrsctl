package com.jaspersoft.jrsctl.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class DefaultHomeTest {

  @Test
  void should_use_the_system_home_when_it_exists_and_is_writable(@TempDir Path base)
      throws IOException {
    Path systemHome = Files.createDirectory(base.resolve(DefaultHome.DIR));

    DefaultHome.Choice choice = DefaultHome.choose(base);

    assertThat(choice.home()).isEqualTo(systemHome.toAbsolutePath().normalize());
    assertThat(choice.perUser()).isFalse();
    assertThat(choice.systemHomeUnwritable()).isFalse();
  }

  @Test
  void should_use_the_system_home_when_it_does_not_exist_yet_but_the_base_is_writable(
      @TempDir Path base) {
    DefaultHome.Choice choice = DefaultHome.choose(base);

    assertThat(choice.home()).isEqualTo(base.resolve(DefaultHome.DIR).toAbsolutePath().normalize());
    assertThat(choice.perUser()).isFalse();
    assertThat(choice.systemHomeUnwritable()).isFalse();
  }

  @Test
  void should_fall_back_per_user_when_the_system_base_does_not_exist(@TempDir Path tmp) {
    DefaultHome.Choice choice = DefaultHome.choose(tmp.resolve("absent"));

    assertThat(choice.home())
        .isEqualTo(Path.of(System.getProperty("user.home")).resolve(DefaultHome.PER_USER_DIR));
    assertThat(choice.perUser()).isTrue();
    assertThat(choice.systemHomeUnwritable())
        .as("there is no system home to be shut out of")
        .isFalse();
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void should_flag_an_existing_system_home_this_user_cannot_write(@TempDir Path base)
      throws IOException {
    assumeFalse(PermissionBypass.active(), "root writes into a read-only directory (#188)");
    Path systemHome = Files.createDirectory(base.resolve(DefaultHome.DIR));
    Files.setPosixFilePermissions(systemHome, PosixFilePermissions.fromString("r-xr-xr-x"));

    DefaultHome.Choice choice = DefaultHome.choose(base);

    assertThat(choice.systemHomeUnwritable()).isTrue();
    assertThat(choice.systemHome()).isEqualTo(systemHome.toAbsolutePath().normalize());
    assertThat(choice.home())
        .isEqualTo(Path.of(System.getProperty("user.home")).resolve(DefaultHome.PER_USER_DIR));
  }
}
