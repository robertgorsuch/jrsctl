package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.platform.Platform;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import org.apache.commons.compress.archivers.ArchiveEntry;

/**
 * Directory archives for the upgrade backups (spec §10.2 step 6): tar.gz on Linux, where POSIX
 * permissions, owner and group are stored in the tar header and re-applied on extraction, zip on
 * Windows, where ACLs are not archived (the webapp is restored under the same parent directory and
 * inherits its ACL). Invariants: everything streams through fixed-size buffers, never whole-file
 * byte arrays; entry names are relative, forward-slash separated and rejected on extraction if they
 * would escape the target directory; the cancellation token is checked once per entry.
 *
 * <p>An archive is only worth having if what comes back out is what went in, so nothing in the tree
 * may be silently dropped. A symbolic link becomes a link entry on Linux and is recreated as a
 * link, never as a copy of what it pointed at; on Windows, where the zip format has no portable way
 * to say "link", {@link #create} fails and names the link rather than writing an archive that
 * quietly loses it. Links are created only after every file and directory is in place, so an entry
 * can never be written through a link this extraction made. Owner, group and permissions are
 * re-applied last, deepest path first, because a directory restored to mode 0555 first would leave
 * nowhere to put its children; a process that lacks the rights to change owner says so once and
 * carries on, since a restored tree owned by the wrong user is still better than no restore.
 */
final class Archives {

  static final int BUFFER = 64 * 1024;

  private Archives() {}

  /** File-name suffix of the archive kind used on the given OS. */
  static String extension(Platform.OsFamily os) {
    return ArchiveFormat.of(os).extension();
  }

  /**
   * Writes every file under {@code sourceDir} into {@code archive}, through a {@code .part} file
   * that is renamed only once the last entry is written, so a crash never leaves a half archive
   * where a restore would find it.
   */
  static long create(Platform.OsFamily os, Path sourceDir, Path archive, CancellationToken cancel)
      throws IOException {
    Objects.requireNonNull(sourceDir, "sourceDir");
    Objects.requireNonNull(archive, "archive");
    Path root = sourceDir.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new IOException(root + " is not a directory");
    }
    Files.createDirectories(archive.toAbsolutePath().getParent());
    Path part = archive.resolveSibling(archive.getFileName() + ".part");
    Files.deleteIfExists(part);
    long count;
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(part), BUFFER)) {
      count = ArchiveFormat.of(os).write(root, raw, cancel);
    }
    Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING);
    return count;
  }

  /** Extracts {@code archive} into {@code targetDir}, which is created if missing. */
  static long extract(Platform.OsFamily os, Path archive, Path targetDir, CancellationToken cancel)
      throws IOException {
    Path root = targetDir.toAbsolutePath().normalize();
    Files.createDirectories(root);
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive), BUFFER)) {
      return ArchiveFormat.of(os).read(raw, root, cancel);
    }
  }

  /** Lists the entry names of an archive, for verification and reporting. */
  static List<String> entries(Platform.OsFamily os, Path archive) throws IOException {
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive), BUFFER)) {
      return ArchiveFormat.of(os).names(raw);
    }
  }

  @FunctionalInterface
  interface EntryWriter {
    void write(Path file, String relative, BasicFileAttributes attrs) throws IOException;
  }

  static void walk(Path root, CancellationToken cancel, EntryWriter writer) throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (!dir.equals(root)) {
              cancel.checkpoint();
              writer.write(dir, relative(root, dir), attrs);
            }
            return FileVisitResult.CONTINUE;
          }

          /*
           * Every non-directory the walk reaches, symbolic links included. The walk does not follow
           * links, so a link to a directory arrives here rather than being descended, and the
           * per-format writer decides whether it can be represented; nothing is dropped in silence.
           */
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            cancel.checkpoint();
            writer.write(file, relative(root, file), attrs);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static String relative(Path root, Path path) {
    return root.relativize(path).toString().replace('\\', '/');
  }

  /**
   * Where an entry lands. Refused: a blank name, an absolute root (a leading slash or backslash, or
   * a drive letter), and a {@code ..} segment. A {@code ..} or a {@code :} inside a segment is a
   * legal Linux file name and is kept, since a backup that cannot be restored is no backup
   * (assessment item O3); the create side refuses what the extract side would.
   */
  static Path resolve(Path root, ArchiveEntry entry) throws IOException {
    String name = entry.getName();
    if (name.isBlank() || isAbsoluteName(name) || hasParentSegment(name)) {
      throw new IOException("refusing archive entry '" + name + "'");
    }
    Path target = root.resolve(name).normalize();
    if (!target.startsWith(root)) {
      throw new IOException("archive entry '" + name + "' escapes " + root);
    }
    return target;
  }

  /**
   * Where a link entry may point: never at an absolute path, never outside the tree once resolved
   * from the link's own directory. Applied when archiving (the tree being backed up must not carry
   * such a link) and when extracting (the archive must not either).
   */
  static void checkLinkTarget(Path root, Path link, String linkTarget) throws IOException {
    if (linkTarget.isBlank() || isAbsoluteName(linkTarget)) {
      throw new IOException("refusing " + link + ": link target '" + linkTarget + "' is absolute");
    }
    Path parent = link.toAbsolutePath().normalize().getParent();
    Path resolved = parent.resolve(linkTarget.replace('\\', '/')).normalize();
    if (!resolved.startsWith(root.toAbsolutePath().normalize())) {
      throw new IOException(
          "refusing " + link + ": link target '" + linkTarget + "' leaves " + root);
    }
  }

  static boolean isAbsoluteName(String name) {
    return name.startsWith("/")
        || name.startsWith("\\")
        || (name.length() >= 2 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':');
  }

  static boolean hasParentSegment(String name) {
    String slashed = name.replace('\\', '/');
    int from = 0;
    while (from <= slashed.length()) {
      int to = slashed.indexOf('/', from);
      if (to < 0) {
        to = slashed.length();
      }
      if (slashed.substring(from, to).equals("..")) {
        return true;
      }
      from = to + 1;
    }
    return false;
  }

  static void copy(Path file, OutputStream out) throws IOException {
    byte[] buffer = new byte[BUFFER];
    try (InputStream in = Files.newInputStream(file)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    }
  }

  static void write(InputStream in, Path target) throws IOException {
    byte[] buffer = new byte[BUFFER];
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), BUFFER)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    }
  }
}
