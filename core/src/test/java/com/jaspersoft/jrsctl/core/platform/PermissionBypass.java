package com.jaspersoft.jrsctl.core.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Whether this process ignores POSIX permission bits (issue #188): root, or any account holding
 * {@code CAP_DAC_OVERRIDE}, reads a mode-000 file and writes into a read-only directory, so a test
 * that removes a permission and expects jrsctl to be refused cannot pass there. Such a test calls
 * {@code assumeFalse(PermissionBypass.active(), ...)} and is skipped, not failed; for every other
 * account it runs unchanged. The answer is found by trying (create a file, take away every bit,
 * read it), not by the user's name, so a capability-holding non-root account is caught as well. A
 * file system without POSIX permissions (Windows) never bypasses them. Computed once per test JVM.
 */
final class PermissionBypass {

  private static final boolean ACTIVE = probe();

  private PermissionBypass() {}

  /** True when a file this process has made unreadable can still be read by it. */
  static boolean active() {
    return ACTIVE;
  }

  private static boolean probe() {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      return false;
    }
    try {
      Path dir = Files.createTempDirectory("jrsctl-dac-probe");
      Path file = Files.writeString(dir.resolve("probe"), "x");
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try (InputStream in = Files.newInputStream(file)) {
          return in.read() >= 0;
        } catch (AccessDeniedException denied) {
          return false;
        }
      } finally {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.delete(file);
        Files.delete(dir);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot probe whether permission bits are enforced", e);
    }
  }
}
