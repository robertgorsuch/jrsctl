package com.jaspersoft.jrsctl.jrs.vendor;

/**
 * Every command-line flag jrsctl passes to the vendor {@code js-export} and {@code js-import}
 * scripts (spec §7.4, §9). The spellings follow the JasperReports Server Administrator Guide
 * ("Import and Export from the Command Line"). Invariant: no other class spells a vendor flag, so
 * when a vendor release renames or drops an option this file is the first and only place to check;
 * the {@code KEYSTORE}/{@code STOREPASS} pair used for the keystore import step (spec §9.3) is the
 * least certain of the set and is called out in {@link VendorTools#keystoreArgs}.
 */
public final class VendorFlags {

  private VendorFlags() {}

  // ---- js-export ----
  public static final String OUTPUT_ZIP = "--output-zip";
  public static final String EVERYTHING = "--everything";
  public static final String URIS = "--uris";
  public static final String REPOSITORY_PERMISSIONS = "--repository-permissions";
  public static final String USERS = "--users";
  public static final String ROLES = "--roles";
  // REST API reference 10.1 p.111: parameters of a URI-scoped export, issue #144
  public static final String SKIP_DEPENDENT_RESOURCES = "--skip-dependent-resources";
  public static final String SKIP_FAVORITE_RESOURCES = "--skip-favorite-resources";

  // ---- js-import ----
  public static final String INPUT_ZIP = "--input-zip";
  public static final String UPDATE = "--update";
  public static final String SKIP_USER_UPDATE = "--skip-user-update";
  public static final String SKIP_THEMES = "--skip-themes";
  // AG 10.1 p.271: `--broken-dependencies skip|include|cancel`; omitted for the default (cancel)
  public static final String BROKEN_DEPENDENCIES = "--broken-dependencies";

  // ---- shared event/settings switches ----
  public static final String INCLUDE_ACCESS_EVENTS = "--include-access-events";
  public static final String INCLUDE_AUDIT_EVENTS = "--include-audit-events";
  public static final String INCLUDE_MONITORING_EVENTS = "--include-monitoring-events";
  public static final String INCLUDE_SERVER_SETTINGS = "--include-server-settings";

  // ---- keystore (JRS 7.5+, spec §9.3) ----
  // Verified against the 10.0.0 importer's option bundle
  // (buildomatic/conf_source/iePro/bundles/ji-export-messages.properties, 2026-09-11): the
  // encryption key of an archive import comes either from a java keystore, `--keystore <file>
  // --storepass <pw>` with `--keyalias <alias> --keypass <pw>` naming the key inside it, or
  // directly as `--secret-key <hex>`. They are options of the archive import (`--input-zip`),
  // not a command of their own (review finding 2.6). The export side has `--destkeystore`,
  // `--deststorepass` and `--destkeypass`, unused here.
  public static final String KEYSTORE = "--keystore";
  public static final String STOREPASS = "--storepass";
  public static final String KEYALIAS = "--keyalias";
  public static final String KEYPASS = "--keypass";
  public static final String SECRET_KEY = "--secret-key";

  // one organisation on export (its URIs relative to it) and the target organisation on import,
  // merged when the ids differ (administrator guide 10.0 pp.263, 267)
  public static final String ORGANIZATION = "--organization";
  public static final String MERGE_ORGANIZATION = "--merge-organization";

  /** Separator js-export expects between repository URIs. */
  public static final String URI_SEPARATOR = ",";

  /** Environment variable buildomatic reads to find its JDK. */
  public static final String JAVA_HOME = "JAVA_HOME";

  /**
   * Search path; the wrappers start the export/import command with the first {@code java} on it
   * unless a {@code java} folder sits next to buildomatic, so {@code vendor.javaHome/bin} goes
   * first.
   */
  public static final String PATH = "PATH";

  /** Environment variable every vendor wrapper appends to the tool's JVM command line. */
  public static final String JAVA_OPTS = "JAVA_OPTS";

  /**
   * System property naming the Spring cache profile the export and import tools activate. {@code
   * js-export.bat} never sets it (issue #39).
   */
  public static final String CACHE_PROVIDER_PROPERTY = "js.cache.provider";
}
