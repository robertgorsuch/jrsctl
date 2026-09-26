package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Turns an official Jaspersoft cumulative hotfix into a jrsctl bundle so the ordinary apply plan,
 * snapshot and rollback cover it (#66, ADR-0024). Invariants: the package is read as a stream and
 * the derived bundle is written once, so neither is held in memory; the derived manifest carries
 * the SHA-256 of every payload file, so the verifier checks the conversion as it checks any bundle;
 * an entry is {@code replace} only when the file exists on this server now and {@code add}
 * otherwise, so no plan is refused for replacing a file that is not there; deletes come from the
 * readme's "Deleted files" list and its "Important" globs, expanded against this installation and
 * never covering a file the package itself lays down; the derived bundle is unsigned, so applying
 * it takes a confirmed checksum or {@code --allow-unsigned} (ADR-0027); nothing on the server is
 * touched here.
 */
final class OfficialPackage {

  /**
   * The inner archive whose paths are relative to the installed webapp, by base name, with or
   * without a version or build suffix ({@code jasperserver-pro-10.0.0-hotfix.zip}); support's
   * packages have used both (field test 2, H1).
   */
  private static final Pattern WEBAPP_ZIP =
      Pattern.compile("(?i)^jasperserver(-pro)?(-[0-9][^/]*)?\\.zip$");

  /** The inner archive whose paths are relative to the installation (buildomatic and samples). */
  private static final Pattern INSTALL_ZIP =
      Pattern.compile("(?i)^js-install(-[0-9][^/]*)?\\.zip$");

  /** The webapp shipped unpacked instead of as an inner archive. */
  private static final Pattern WEBAPP_DIR = Pattern.compile("(?i)^jasperserver(-pro)?$");

  private static final String README = "readme.txt";
  private static final Pattern PRODUCT = Pattern.compile("^Product Name:\\s*(.+?)\\s*$");
  private static final Pattern RELEASE = Pattern.compile("^Release Version:\\s*([0-9.]+)\\s*$");
  private static final Pattern BUILD = Pattern.compile("^Build version:\\s*\\[?([0-9_]+)]?\\s*$");
  private static final Pattern BUILD_ID = Pattern.compile("^([0-9]{8})_([0-9]{4})$");

  /** A line of the readme that names one file, with or without a {@code *} in its last segment. */
  private static final Pattern LISTED_PATH = Pattern.compile("^[A-Za-z0-9._*/-]+$");

  private static final int BUFFER = 64 * 1024;

  private OfficialPackage() {}

  /**
   * What the conversion found: the derived bundle's identity and what the operator must know. It is
   * written beside the bundle, so a conversion reused by a later command still carries its
   * warnings.
   */
  record Converted(String id, String title, List<String> notes) {
    Converted {
      notes = List.copyOf(notes);
    }
  }

  /** Where the notes of the bundle derived at {@code bundle} are kept. */
  static Path notesFile(Path bundle) {
    return bundle.resolveSibling(bundle.getFileName() + ".notes.json");
  }

  /** The notes written beside an earlier conversion. */
  static Converted read(Path bundle) throws IOException {
    return Json.read(Files.readString(notesFile(bundle), StandardCharsets.UTF_8), Converted.class);
  }

  /** What {@code hotfix record} stores about a package applied by hand (ADR-0030, issue #99). */
  record Described(String id, String title, String release) {}

  /**
   * The identity the package's outer readme gives it, without converting anything: the same id,
   * title and release {@link #convert} would derive, so a hotfix recorded by hand and one applied
   * through jrsctl share an id and cannot both be in the ledger.
   */
  static Described describe(Path zip) throws IOException {
    Shape shape =
        shape(zip)
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK,
                        zip + " is not a readable hotfix package",
                        "point jrsctl at the hotfix ZIP as it was downloaded"));
    if (shape.readme().isEmpty() || !shape.payload()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          zip + NEITHER_SHAPE_SHORT,
          "point jrsctl at the hotfix ZIP as support published it");
    }
    try (InputStream in = Files.newInputStream(zip);
        ZipInputStream outer = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = outer.getNextEntry()) != null) {
        if (!entry.isDirectory() && shape.kind(entry.getName().replace('\\', '/')) == Kind.README) {
          Header header = Header.parse(readLines(outer));
          return new Described(header.id(), header.title(), header.release());
        }
      }
    }
    throw new HotfixException(
        HotfixException.PRECHECK,
        "no readme.txt in " + zip,
        "point jrsctl at the hotfix ZIP as it was downloaded, not at an unpacked copy");
  }

  static final String NEITHER_SHAPE_SHORT =
      " is not an official Jaspersoft hotfix package (readme.txt beside jasperserver[-pro].zip,"
          + " js-install.zip or an unpacked jasperserver[-pro]/ tree)";

  /** True when {@code zip} is an official package rather than a jrsctl bundle. */
  static boolean looksOfficial(Path zip) {
    return shape(zip).map(Shape::official).orElse(false);
  }

  /** What one entry of the outer archive is to the conversion. */
  enum Kind {
    /** The package readme, with the release and build. */
    README,
    /** An inner archive whose paths are relative to the webapp. */
    WEBAPP_ZIP,
    /** An inner archive whose paths are relative to the installation. */
    INSTALL_ZIP,
    /** A file of the webapp shipped unpacked under {@code jasperserver[-pro]/}. */
    WEBAPP_FILE,
    /** Anything else, left alone. */
    IGNORE
  }

  /**
   * How the outer archive is laid out, judged on base names so the case of the readme, one
   * directory of prefix around the package and a version in an inner archive's name all read as the
   * same package. Invariants: {@code root} is the directory of the outer readme, empty or ending in
   * {@code /}; {@link #official()} is true exactly when a readme and at least one payload were
   * seen; an entry named {@code manifest.json} at the top makes the archive a jrsctl bundle, never
   * a package.
   */
  record Shape(String root, Optional<String> readme, boolean payload) {

    boolean official() {
      return readme.isPresent() && payload;
    }

    Kind kind(String name) {
      if (readme.isPresent() && name.equals(readme.get())) {
        return Kind.README;
      }
      if (!name.startsWith(root)) {
        return Kind.IGNORE;
      }
      String rel = name.substring(root.length());
      int slash = rel.indexOf('/');
      if (slash < 0) {
        if (WEBAPP_ZIP.matcher(rel).matches()) {
          return Kind.WEBAPP_ZIP;
        }
        return INSTALL_ZIP.matcher(rel).matches() ? Kind.INSTALL_ZIP : Kind.IGNORE;
      }
      return WEBAPP_DIR.matcher(rel.substring(0, slash)).matches() && slash < rel.length() - 1
          ? Kind.WEBAPP_FILE
          : Kind.IGNORE;
    }

    /** For a {@link Kind#WEBAPP_FILE}: its path under the unpacked webapp directory. */
    String underWebapp(String name) {
      String rel = name.substring(root.length());
      return rel.substring(rel.indexOf('/') + 1);
    }
  }

  /** The layout of {@code zip}; empty when it is not a readable archive or is a jrsctl bundle. */
  static Optional<Shape> shape(Path zip) {
    if (!Files.isRegularFile(zip)) {
      return Optional.empty();
    }
    List<String> names = new ArrayList<>();
    try (InputStream in = Files.newInputStream(zip);
        ZipInputStream z = new ZipInputStream(in)) {
      ZipEntry e;
      while ((e = z.getNextEntry()) != null) {
        String name = e.getName().replace('\\', '/');
        if (name.equals(HotfixBundle.MANIFEST)) {
          return Optional.empty();
        }
        if (!e.isDirectory()) {
          names.add(name);
        }
      }
    } catch (IOException e) {
      return Optional.empty();
    }
    Optional<String> readme =
        names.stream()
            .filter(n -> baseName(n).equalsIgnoreCase(README))
            .min(
                Comparator.comparingInt((String n) -> n.length() - n.replace("/", "").length())
                    .thenComparing(n -> n));
    String root = readme.map(r -> r.substring(0, r.lastIndexOf('/') + 1)).orElse("");
    Shape probe = new Shape(root, readme, true);
    boolean payload =
        names.stream().map(probe::kind).anyMatch(k -> k != Kind.README && k != Kind.IGNORE);
    return Optional.of(new Shape(root, readme, payload));
  }

  private static String baseName(String name) {
    return name.substring(name.lastIndexOf('/') + 1);
  }

  /**
   * Converts {@code source} into a jrsctl bundle at {@code out} (overwritten), mapping webapp
   * entries onto {@code webappName} and installation entries onto the install directory.
   */
  static Converted convert(Path source, Path out, String webappName, HotfixPaths paths)
      throws IOException {
    Shape shape =
        shape(source)
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK,
                        source + " is not a readable hotfix package",
                        "point jrsctl at the hotfix ZIP as it was downloaded"));
    if (shape.readme().isEmpty()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "no readme.txt in " + source,
          "point jrsctl at the hotfix ZIP as it was downloaded, not at an unpacked copy");
    }
    if (!shape.payload()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "no jasperserver or js-install archive in " + source,
          "point jrsctl at the hotfix ZIP as it was downloaded");
    }
    String webappPrefix = HotfixPaths.WEBAPPS_PREFIX + webappName + "/";
    Header header = null;
    Readme treeReadme = null;
    List<Manifest.FileEntry> files = new ArrayList<>();
    Set<String> added = new LinkedHashSet<>();
    List<Readme> readmes = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    Files.createDirectories(out.getParent());
    try (InputStream in = Files.newInputStream(source);
        ZipInputStream outer = new ZipInputStream(in);
        OutputStream os = Files.newOutputStream(out);
        ZipOutputStream bundle = new ZipOutputStream(os)) {
      ZipEntry entry;
      while ((entry = outer.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName().replace('\\', '/');
        switch (shape.kind(name)) {
          case README -> header = Header.parse(readLines(outer));
          case WEBAPP_ZIP -> readmes.add(inner(outer, webappPrefix, paths, bundle, files, added));
          case INSTALL_ZIP -> readmes.add(inner(outer, "", paths, bundle, files, added));
          case WEBAPP_FILE -> {
            String under = shape.underWebapp(name);
            if (under.equalsIgnoreCase(README)) {
              treeReadme = Readme.parse(webappPrefix, readLines(outer));
            } else {
              copyEntry(outer, webappPrefix + under, entry.getName(), paths, bundle, files, added);
            }
          }
          case IGNORE -> {}
        }
      }
      if (header == null) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "no readme.txt in " + source,
            "point jrsctl at the hotfix ZIP as it was downloaded, not at an unpacked copy");
      }
      if (files.isEmpty()) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "no jasperserver or js-install archive in " + source,
            "point jrsctl at the hotfix ZIP as it was downloaded");
      }
      if (treeReadme != null) {
        readmes.add(treeReadme);
      }
      for (Readme r : readmes) {
        files.addAll(deletions(r, added, paths, notes));
        notes.addAll(r.notes());
      }
      Manifest manifest = manifest(header, files);
      writeManifest(bundle, manifest);
      notes.addAll(configNotes(files));
      Converted converted =
          new Converted(manifest.id(), manifest.title(), List.copyOf(new LinkedHashSet<>(notes)));
      Files.writeString(notesFile(out), Json.writePretty(converted), StandardCharsets.UTF_8);
      return converted;
    }
  }

  /** Copies one inner archive into the bundle payload and returns its parsed readme. */
  private static Readme inner(
      InputStream source,
      String prefix,
      HotfixPaths paths,
      ZipOutputStream bundle,
      List<Manifest.FileEntry> files,
      Set<String> added)
      throws IOException {
    Readme readme = Readme.empty();
    ZipInputStream zip = new ZipInputStream(source);
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      if (entry.isDirectory()) {
        continue;
      }
      String name = entry.getName().replace('\\', '/');
      if (name.equalsIgnoreCase(README)) {
        readme = Readme.parse(prefix, readLines(zip));
        continue;
      }
      copyEntry(zip, prefix + name, entry.getName(), paths, bundle, files, added);
    }
    return readme;
  }

  /**
   * Streams one file of the package into the bundle payload at {@code path}, hashing it on the way,
   * and records its manifest entry: {@code replace} when the file exists here now, else {@code
   * add}.
   */
  private static void copyEntry(
      InputStream in,
      String path,
      String original,
      HotfixPaths paths,
      ZipOutputStream bundle,
      List<Manifest.FileEntry> files,
      Set<String> added)
      throws IOException {
    if (!HotfixPaths.pathProblems(path).isEmpty()
        || HotfixBundle.entryNameProblem(HotfixBundle.PAYLOAD_DIR + "/" + path).isPresent()) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "the package holds an unusable path: " + original,
          "obtain the package again; it is not the layout jrsctl knows");
    }
    bundle.putNextEntry(new ZipEntry(HotfixBundle.PAYLOAD_DIR + "/" + path));
    MessageDigest md = sha256();
    DigestOutputStream digest = new DigestOutputStream(bundle, md);
    byte[] buffer = new byte[BUFFER];
    int read;
    while ((read = in.read(buffer)) != -1) {
      digest.write(buffer, 0, read);
    }
    bundle.closeEntry();
    Manifest.Action action =
        Files.isRegularFile(paths.resolve(path)) ? Manifest.Action.REPLACE : Manifest.Action.ADD;
    files.add(
        new Manifest.FileEntry(
            action, path, Optional.of(HexFormat.of().formatHex(md.digest())), List.of()));
    added.add(path);
  }

  /**
   * The readme's deletions as manifest entries: the listed files, and the "Important" globs
   * expanded against this installation. Only files that are here now are listed, and never one the
   * package itself lays down.
   */
  private static List<Manifest.FileEntry> deletions(
      Readme readme, Set<String> added, HotfixPaths paths, List<String> notes) {
    List<Manifest.FileEntry> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String path : readme.deleted()) {
      if (!added.contains(path) && seen.add(path) && Files.isRegularFile(paths.resolve(path))) {
        out.add(new Manifest.FileEntry(Manifest.Action.DELETE, path, Optional.empty(), List.of()));
      }
    }
    int fromGlobs = 0;
    for (String glob : readme.globs()) {
      for (String path : expand(glob, paths)) {
        if (!added.contains(path) && seen.add(path)) {
          out.add(
              new Manifest.FileEntry(Manifest.Action.DELETE, path, Optional.empty(), List.of()));
          fromGlobs++;
        }
      }
    }
    if (fromGlobs > 0) {
      notes.add(
          fromGlobs
              + " file(s) left by an earlier hotfix are deleted, as the readme's Important section"
              + " requires; they are in the snapshot and a rollback puts them back");
    }
    return out;
  }

  /** Files of {@code glob}'s directory whose names match it, as manifest paths. */
  private static List<String> expand(String glob, HotfixPaths paths) {
    int slash = glob.lastIndexOf('/');
    if (slash < 0) {
      return List.of();
    }
    String dir = glob.substring(0, slash);
    String name = glob.substring(slash + 1);
    Path directory = paths.resolve(dir + "/" + name.replace('*', '_')).getParent();
    if (directory == null || !Files.isDirectory(directory)) {
      return List.of();
    }
    Pattern pattern =
        Pattern.compile(
            Stream.of(name.split("\\*", -1))
                .map(Pattern::quote)
                .reduce((a, b) -> a + ".*" + b)
                .orElse(""));
    List<String> out = new ArrayList<>();
    try (Stream<Path> list = Files.list(directory)) {
      list.filter(Files::isRegularFile)
          .map(p -> p.getFileName().toString())
          .filter(f -> pattern.matcher(f).matches())
          .sorted()
          .forEach(f -> out.add(dir + "/" + f));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + directory, e);
    }
    return out;
  }

  /** Warnings about files that usually hold site settings and are about to be replaced. */
  private static List<String> configNotes(List<Manifest.FileEntry> files) {
    List<String> paths =
        files.stream()
            .filter(f -> f.action() == Manifest.Action.REPLACE)
            .map(Manifest.FileEntry::path)
            .filter(OfficialPackage::isSettingsFile)
            .sorted()
            .toList();
    if (paths.isEmpty()) {
      return List.of();
    }
    List<String> shown = paths.size() > 8 ? paths.subList(0, 8) : paths;
    String more =
        paths.size() > shown.size() ? " and " + (paths.size() - shown.size()) + " more" : "";
    return List.of(
        "settings you changed in these files are overwritten and must be applied again: "
            + String.join(", ", shown)
            + more);
  }

  /** True for the configuration files a site edits, as opposed to code the hotfix ships. */
  private static boolean isSettingsFile(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return (lower.endsWith(".xml") || lower.endsWith(".properties"))
        && !lower.contains("/web-inf/lib/");
  }

  private static Manifest manifest(Header header, List<Manifest.FileEntry> files) {
    boolean webInf = files.stream().anyMatch(f -> HotfixPaths.requiresServiceStop(f.path()));
    return new Manifest(
        header.id(),
        "1",
        header.title(),
        Optional.of(header.description()),
        new Manifest.Applies(List.of(header.release()), List.of(header.edition()), List.of()),
        List.of(),
        List.of(),
        List.copyOf(files),
        List.of(),
        List.of(),
        webInf ? Manifest.Restart.REQUIRED : Manifest.Restart.NONE,
        List.of(),
        List.of(),
        Manifest.Rollback.SNAPSHOT,
        Optional.empty());
  }

  private static void writeManifest(ZipOutputStream bundle, Manifest manifest) throws IOException {
    bundle.putNextEntry(new ZipEntry(HotfixBundle.MANIFEST));
    bundle.write(Json.writePretty(manifest).getBytes(StandardCharsets.UTF_8));
    bundle.closeEntry();
  }

  private static List<String> readLines(InputStream in) throws IOException {
    List<String> lines = new ArrayList<>();
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
    String line;
    while ((line = reader.readLine()) != null) {
      lines.add(line.strip());
    }
    return lines;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
    }
  }

  /** What the outer readme says about the package as a whole. */
  private record Header(String release, String edition, String build) {

    static Header parse(List<String> lines) {
      String release = "";
      String edition = "PRO";
      String build = "";
      for (String line : lines) {
        Matcher m = RELEASE.matcher(line);
        if (m.matches()) {
          release = m.group(1);
        }
        m = BUILD.matcher(line);
        if (m.matches()) {
          build = m.group(1);
        }
        m = PRODUCT.matcher(line);
        if (m.matches()) {
          edition = m.group(1).toLowerCase(Locale.ROOT).contains("pro") ? "PRO" : "CE";
        }
      }
      if (release.isEmpty() || build.isEmpty()) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "the package readme names no release and build version",
            "point jrsctl at an official cumulative hotfix ZIP");
      }
      return new Header(release, edition, build);
    }

    /** Derived id, for example {@code JRSHF-10.0.0-20260730-0457}. */
    String id() {
      Matcher m = BUILD_ID.matcher(build);
      if (!m.matches()) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            "the package build version is not a date and time: " + build,
            "point jrsctl at an official cumulative hotfix ZIP");
      }
      return "JRSHF-" + release + "-" + m.group(1) + "-" + m.group(2);
    }

    String title() {
      return "JasperReports Server "
          + (edition.equals("PRO") ? "Pro " : "")
          + release
          + " cumulative hotfix "
          + build;
    }

    String description() {
      return "Official Jaspersoft cumulative hotfix, converted by jrsctl from the package as"
          + " downloaded.";
    }
  }

  /** The parts of an inner readme jrsctl acts on: what to delete, and what the operator must do. */
  private record Readme(List<String> deleted, List<String> globs, List<String> notes) {

    static Readme empty() {
      return new Readme(List.of(), List.of(), List.of());
    }

    static Readme parse(String prefix, List<String> lines) {
      List<String> deleted = new ArrayList<>();
      List<String> globs = new ArrayList<>();
      int manual = 0;
      boolean conditions = false;
      Section section = Section.NONE;
      for (String line : lines) {
        Section heading = Section.of(line);
        if (heading != null) {
          section = heading;
          continue;
        }
        if (line.startsWith("=====")) {
          section = Section.NONE;
          continue;
        }
        if (line.isEmpty()) {
          continue;
        }
        boolean path = LISTED_PATH.matcher(line).matches();
        switch (section) {
          case DELETED -> {
            if (path && !line.contains("*")) {
              deleted.add(prefix + line);
            }
          }
          case IMPORTANT -> {
            if (path && line.contains("*")) {
              globs.add(prefix + line);
            } else {
              conditions = true;
            }
          }
          case NOTES -> manual++;
          case NONE -> {}
        }
      }
      List<String> notes = new ArrayList<>();
      if (conditions) {
        notes.add(
            "the package readme's Important section names conditions to check by hand (an earlier"
                + " build, source map files); read readme.txt in the package");
      }
      if (manual > 0) {
        notes.add(
            "the package readme's Additional Notes section describes manual steps, such as SQL for"
                + " some databases and optional properties; jrsctl runs none of them");
      }
      return new Readme(deleted, globs, notes);
    }

    private enum Section {
      NONE,
      DELETED,
      IMPORTANT,
      NOTES;

      static Section of(String line) {
        return switch (line) {
          case "Deleted files:" -> DELETED;
          case "IMPORTANT" -> IMPORTANT;
          case "Additional Notes:" -> NOTES;
          case "Added files:", "Modified files:", "Installation", "Uninstallation" -> NONE;
          default -> null;
        };
      }
    }
  }
}
