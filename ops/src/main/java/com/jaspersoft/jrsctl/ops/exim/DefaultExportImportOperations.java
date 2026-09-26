package com.jaspersoft.jrsctl.ops.exim;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.jrs.api.Capability;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.jrs.strategy.Strategies;
import com.jaspersoft.jrsctl.ops.ConfigShow;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.semver4j.Semver;

/**
 * Plans exports and imports (spec §9). An export plan is exactly the chosen strategy's export steps
 * in the {@code export} phase. An import plan has three phases: {@code precheck}, the strategy's
 * leading read-only import steps (keystore fingerprint comparison, vendor-tool location) moved
 * ahead of everything so a failed check exits 2 with nothing mutated; {@code backup}, a {@link
 * PreImportSnapshot} of the affected subtree taken with the same strategy as the import (the uris
 * recorded in the archive's sidecar, else the whole repository; a full-server export when {@code
 * --update} targets the root); and {@code import}, whose first step is the {@link
 * RestoreFromPreImportSnapshot} anchor followed by the strategy's mutating import steps, so a
 * failure anywhere in the phase re-imports the snapshot. The snapshot is taken with the server
 * running, so a vendor import stops the service once, around {@code js-import} (ADR-0040); only a
 * plan rebuilt from arguments an earlier jrsctl stored stops it around the snapshot too. With
 * {@code --no-snapshot} the backup phase is the listing alone and the import phase's anchor is
 * {@link RemoveImportAdditions}. Invariants: planning never mutates (beyond the audit row of an
 * override) and needs the server only to read its identity and capabilities; the snapshot lives
 * under {@code snapshots/pre-import/} with a name derived from the archive's hash, so {@code runs
 * recover} rebuilds an identical plan from the stored options; the summary always carries {@link
 * #ROLLBACK_WARNING}, or {@link #NO_SNAPSHOT_WARNING} without a snapshot; the fingerprint covers
 * the server identity, the archive or output path (and the archive's SHA-256), the request flags
 * and the resolved configuration.
 */
public final class DefaultExportImportOperations implements ExportImportOperations {

  public static final String EXPORT_OPERATION = "export";
  public static final String IMPORT_OPERATION = "import";
  public static final String EXPORT_PHASE = "export";
  public static final String PRECHECK_PHASE = "precheck";
  public static final String BACKUP_PHASE = "backup";
  public static final String IMPORT_PHASE = "import";
  public static final String SNAPSHOT_DIR = "pre-import";

  /**
   * Issue #116 (administrator guide p.266): what {@code js-export --everything} already carries, in
   * every full-server export plan summary.
   */
  static final String FULL_SERVER_COVERS =
      "a full-server export already carries the repository, users, roles, permissions, report jobs,"
          + " calendars and the settings changed in the UI; events are left out unless"
          + " --access-events, --audit-events or --monitoring is given";

  /** Issue #116: {@code --users-roles} on a full-server export adds nothing. */
  static final String USERS_ROLES_REDUNDANT =
      "--users-roles is redundant with --full-server, which already includes users and roles";

  /** Spec §9.4 (issue #100, ADR-0031), verbatim in every import plan summary. */
  public static final String ROLLBACK_WARNING =
      "Rollback deletes the resources the failed import created under the snapshotted folders"
          + " (judged against a listing taken just before the import; anything anyone else creates"
          + " there in between is deleted with them) and re-imports the pre-import snapshot, which"
          + " puts back what the import overwrote.";

  /** ADR-0040: in place of {@link #ROLLBACK_WARNING} in a plan made with {@code --no-snapshot}. */
  public static final String NO_SNAPSHOT_WARNING =
      "--no-snapshot: no pre-import snapshot is taken, so a failed import cannot put back what it"
          + " overwrote. Rollback only deletes the resources the failed import created under the"
          + " archive's folders (judged against a listing taken just before the import; anything"
          + " anyone else creates there in between is deleted with them).";

  /**
   * REST reference 10.1 p.118: "Jaspersoft does not recommend uploading files greater than 2 GB".
   */
  static final long REST_UPLOAD_LIMIT = 2L * 1024 * 1024 * 1024;

  private final Services services;
  private final Strategies strategies;
  private final long restUploadLimit;

  public DefaultExportImportOperations(Services services) {
    this(services, Strategies.standard(services.platform(), services.redactor()));
  }

  public DefaultExportImportOperations(Services services, Strategies strategies) {
    this(services, strategies, REST_UPLOAD_LIMIT);
  }

  /** A test can lower the size above which an import leaves REST (issue #115). */
  DefaultExportImportOperations(Services services, Strategies strategies, long restUploadLimit) {
    this.services = Objects.requireNonNull(services, "services");
    this.strategies = Objects.requireNonNull(strategies, "strategies");
    this.restUploadLimit = restUploadLimit;
  }

  // ---------------------------------------------------------------- export

  @Override
  public Plan planExport(ExportOptions options) {
    Objects.requireNonNull(options, "options");
    Path out = options.out().toAbsolutePath().normalize();
    ExportRequest request =
        new ExportRequest(
            options.fullServer() ? ExportRequest.Scope.EVERYTHING : ExportRequest.Scope.REPOSITORY,
            options.uris(),
            options.usersRoles(),
            options.accessEvents(),
            options.auditEvents(),
            options.monitoring(),
            options.settings(),
            options.fullServer(),
            out,
            options.stopService(),
            options.keyAlias(),
            options.organization(),
            options.skipDependentResources(),
            options.skipFavoriteResources());
    JrsAdapter adapter = services.adapter().get();
    ServerIdentity identity = adapter.identity();
    refuseMissingUris(adapter, request);
    Strategies.Selection selection =
        strategies.select(services.config(), adapter, request, options.strategy());
    requireLocalInstallation(selection, options.strategy(), options.fullServer(), "export");
    List<Step> steps = selection.strategy().exportSteps(request);

    List<String> warnings = new ArrayList<>();
    boolean stops = selection.strategy().requiresServiceStop() && request.stopService();
    if (stops) {
      warnings.add(
          "the service will be stopped for the vendor export and started again afterwards");
    } else if (selection.strategy().requiresServiceStop()) {
      warnings.add(
          "the service keeps running while js-export reads the repository; pass --stop-service"
              + " for an export taken with the service stopped");
    }
    if (Files.exists(out)) {
      warnings.add(out + " exists and will be replaced");
    }
    if (request.fullServer() || request.scope() == ExportRequest.Scope.EVERYTHING) {
      warnings.add(FULL_SERVER_COVERS);
      if (request.includeUsersRoles()) {
        warnings.add(USERS_ROLES_REDUNDANT);
      }
    }
    PlanSummary summary =
        new PlanSummary(
            EXPORT_OPERATION,
            target(request),
            List.of(out, Sidecar.pathFor(out)),
            sortedUris(request),
            stops,
            List.of(),
            Map.of(EXPORT_PHASE, "delete the partial archive and its sidecar"),
            strategyLine(selection),
            warnings);
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("server", identity.fingerprintInput());
    inputs.put("out", out.toString());
    inputs.put("request", describe(request));
    inputs.put("strategy", options.strategy().map(Enum::name).orElse("auto"));
    inputs.put("config", configHash());
    return new Plan(planId(EXPORT_OPERATION), steps, summary, PlanFingerprint.of(inputs));
  }

  // ---------------------------------------------------------------- import

  @Override
  public Plan planImport(ImportOptions options) {
    Objects.requireNonNull(options, "options");
    Path archive = options.archive().toAbsolutePath().normalize();
    if (!Files.isRegularFile(archive)) {
      throw new IllegalArgumentException("archive " + archive + " does not exist");
    }
    String archiveHash;
    try {
      archiveHash = services.platform().files().sha256(archive);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot hash " + archive + ": " + e.getMessage(), e);
    }
    JrsAdapter adapter = services.adapter().get();
    ServerIdentity identity = adapter.identity();
    List<String> sidecarWarnings = new ArrayList<>();
    Optional<Sidecar> sidecar = readSidecar(archive, sidecarWarnings);
    // issue #115: themes are repository resources and an old one may not fit a new major version
    boolean acrossMajor = sidecar.map(sc -> crossesMajor(sc, identity)).orElse(false);
    boolean themesDefaulted = acrossMajor && !options.skipThemes() && !options.keepThemes();
    ImportRequest request =
        new ImportRequest(
            archive,
            options.update(),
            options.skipUserUpdate(),
            options.accessEvents(),
            options.auditEvents(),
            options.monitoring(),
            options.settings(),
            options.skipThemes() || themesDefaulted,
            options.sourceKeystore().map(p -> p.toAbsolutePath().normalize()),
            options.sourceKeystorePassword(),
            options.brokenDependencies(),
            // an archive exported with a named key is decrypted with it (field test 2, E4, I1):
            // the sidecar remembers the alias, so the operator need not repeat it
            options.keyAlias().or(() -> sidecarKeyAlias(archive)),
            options.organization(),
            options.mergeOrganization());
    Strategies.Selection chosen =
        strategies.select(services.config(), adapter, request, options.strategy());
    requireLocalInstallation(chosen, options.strategy(), false, "import");
    List<String> warnings = new ArrayList<>();
    warnings.add(ROLLBACK_WARNING);
    Strategies.Selection selection =
        leaveRestAboveTheLimit(chosen, adapter, request, options, archive, warnings);
    ExportImportStrategy strategy = selection.strategy();
    if (options.keyAlias().isEmpty() && request.keyAlias().isPresent()) {
      warnings.add(
          "the archive was exported with key alias "
              + request.keyAlias().get()
              + " (recorded in its sidecar); the import decrypts it with that key, not with this"
              + " server's own");
    }
    warnings.addAll(sidecarWarnings);
    // issue #185: said at planning time too, so --plan shows it before anything runs
    sidecar
        .filter(sc -> !VerifyArchiveChecksum.matches(sc.sha256(), archiveHash))
        .ifPresent(
            sc ->
                warnings.add(
                    VerifyArchiveChecksum.mismatch(archive, sc.sha256(), archiveHash)
                        + "; the import stops at "
                        + VerifyArchiveChecksum.ID
                        + " (exit 2) before anything is changed"));
    sidecar.ifPresent(s -> refuseNewerCatalog(s, identity, options.forceVersion(), warnings));
    if (themesDefaulted) {
      warnings.add(
          "themes are not imported: the archive was exported from JasperReports Server "
              + sidecar.get().serverVersion()
              + " and this server is "
              + identity.version()
              + ", and a theme from another major version may not fit; pass --themes to import"
              + " them anyway");
    }
    // the folders a snapshot would cover; with --no-snapshot they are still listed, so the rollback
    // deletes what the failed import created there (ADR-0040)
    Optional<ExportRequest> covered =
        snapshotRequest(
            sidecar,
            options.update(),
            snapshotPath(archive, archiveHash),
            adapter,
            warnings,
            options.organization(),
            options.snapshotStopsService());
    Optional<ExportRequest> snapshot = options.noSnapshot() ? Optional.empty() : covered;
    if (options.noSnapshot()) {
      warnings.remove(ROLLBACK_WARNING);
      warnings.add(0, NO_SNAPSHOT_WARNING);
      auditNoSnapshot(archive, covered, warnings);
    }
    offerRest(selection, options, request, adapter, archive, warnings);
    List<String> newContentUris = newContentUris(sidecar, options.organization());
    Set<String> newRoots =
        newContentUris.isEmpty() ? Set.of() : RemoveNewContent.newRoots(adapter, newContentUris);
    for (String organisation : RemoveNewContent.organisations(newRoots)) {
      warnings.add(
          organisation
              + " is an organisation that does not exist yet; if the import fails it is not"
              + " removed (DELETE /rest_v2/organizations/"
              + organisation.substring(organisation.lastIndexOf('/') + 1)
              + ")");
    }
    Optional<ImportRequest> restore =
        snapshot.map(
            s ->
                new ImportRequest(
                    s.output(),
                    true,
                    false,
                    s.includeAccessEvents(),
                    s.includeAuditEvents(),
                    s.includeMonitoring(),
                    s.includeSettings(),
                    false,
                    Optional.empty(),
                    Optional.empty()));
    if (strategy.requiresServiceStop()) {
      if (snapshot.isPresent() && snapshot.get().stopService()) {
        warnings.add(
            "the service will be stopped for the vendor snapshot and import and started again"
                + " afterwards");
      } else {
        warnings.add(
            "the service will be stopped for the vendor import and started again afterwards"
                + (snapshot.isPresent()
                    ? "; the pre-import snapshot is taken first, with the server running"
                    : ""));
      }
    }
    if (options.update()) {
      warnings.add(
          "--update overwrites existing resources under "
              + snapshot
                  .map(PreImportSnapshot::describe)
                  .orElse("the archive's folders (none of which exists here yet)"));
    }

    List<Step> importSteps = strategy.importSteps(request);
    int firstMutating = 0;
    while (firstMutating < importSteps.size() && !importSteps.get(firstMutating).mutating()) {
      firstMutating++;
    }
    String importPhase =
        firstMutating < importSteps.size() ? importSteps.get(firstMutating).phase() : IMPORT_PHASE;
    List<Step> steps = new ArrayList<>();
    sidecar.ifPresent(
        sc -> steps.add(new VerifyArchiveChecksum(archive, sc.sha256(), archiveHash)));
    for (Step s : importSteps.subList(0, firstMutating)) {
      steps.add(Rephased.into(PRECHECK_PHASE, s));
    }
    if (snapshot.isPresent()) {
      List<Step> backup = new ArrayList<>(PreImportSnapshot.steps(strategy, snapshot.get()));
      // issue #100: the listing goes right after the announcement, while the server is up and
      // before a vendor snapshot (as an earlier jrsctl planned it) stops it, so the rollback knows
      // what the import added
      List<String> roots = RecordRepositoryListing.rootsOf(snapshot.get());
      backup.add(1, new RecordRepositoryListing(roots));
      steps.addAll(backup);
      steps.add(
          new RestoreFromPreImportSnapshot(
              importPhase, strategy, snapshot.get().output(), restore.get(), roots));
    } else if (covered.isPresent()) {
      // ADR-0040, --no-snapshot: nothing to re-import, but what the import adds is still deleted
      List<String> roots = RecordRepositoryListing.rootsOf(covered.get());
      steps.add(new RecordRepositoryListing(roots));
      steps.add(new RemoveImportAdditions(importPhase, roots));
    }
    if (!newContentUris.isEmpty()) {
      // issue #139: present whenever the sidecar names folders, so a plan rebuilt after the import
      // created them has the same steps; the step decides at run time which folders are new
      steps.add(new RemoveNewContent(importPhase, newContentUris));
    }
    steps.addAll(importSteps.subList(firstMutating, importSteps.size()));

    List<String> removable = RemoveNewContent.removable(newRoots);
    String removeNew =
        "delete "
            + String.join(", ", removable)
            + " with everything the import put there ("
            + (removable.size() == 1 ? "it does" : "they do")
            + " not exist yet)";
    Map<String, String> rollback = new LinkedHashMap<>();
    if (snapshot.isPresent()) {
      rollback.put(BACKUP_PHASE, "delete the pre-import snapshot");
      rollback.put(
          importPhase,
          "delete what the import created, then re-import the pre-import snapshot with update"
              + (removable.isEmpty() ? "" : "; also " + removeNew));
    } else if (covered.isPresent()) {
      rollback.put(
          importPhase,
          "delete what the import created under "
              + PreImportSnapshot.describe(covered.get())
              + "; what it overwrote is not put back (--no-snapshot)"
              + (removable.isEmpty() ? "" : "; also " + removeNew));
    } else if (!removable.isEmpty()) {
      rollback.put(importPhase, removeNew);
    } else {
      rollback.put(
          importPhase,
          "nothing to put back: none of the archive's resources existed before the import");
    }
    List<String> touched =
        snapshot
            .map(DefaultExportImportOperations::sortedUris)
            .orElseGet(
                () ->
                    sidecar
                        .map(sc -> List.copyOf(new TreeSet<>(sc.flags().uris())))
                        .orElse(List.of("/")));
    PlanSummary summary =
        new PlanSummary(
            IMPORT_OPERATION,
            archive.getFileName().toString(),
            List.of(),
            touched,
            strategy.requiresServiceStop(),
            snapshot.map(s -> List.of(s.output())).orElse(List.of()),
            rollback,
            strategyLine(selection),
            warnings);
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("server", identity.fingerprintInput());
    inputs.put("archive", archive.toString());
    inputs.put("archiveSha256", archiveHash);
    inputs.put("request", describe(request));
    inputs.put("strategy", options.strategy().map(Enum::name).orElse("auto"));
    inputs.put("config", configHash());
    // ADR-0040: only a choice that differs from before says so, so a plan rebuilt from arguments
    // an earlier jrsctl stored keeps the fingerprint it was journaled with
    if (options.noSnapshot()) {
      inputs.put("snapshot", "none");
    } else if (!options.snapshotStopsService()) {
      inputs.put("snapshot", "live");
    }
    return new Plan(planId(IMPORT_OPERATION), steps, summary, PlanFingerprint.of(inputs));
  }

  /**
   * ADR-0040: {@code --no-snapshot} is an override like {@code --force-version}, so it is written
   * to the audit table; a failure to write it is a warning, not a refusal.
   */
  private void auditNoSnapshot(
      Path archive, Optional<ExportRequest> covered, List<String> warnings) {
    String what =
        "import of "
            + archive
            + " without a pre-import snapshot"
            + covered.map(c -> " of " + PreImportSnapshot.describe(c)).orElse("")
            + ": a failed import cannot put back what it overwrote";
    try {
      services.stateStore().get().audit("operator", "--no-snapshot", what);
    } catch (RuntimeException e) {
      warnings.add("cannot write the audit row for --no-snapshot: " + e.getMessage());
    }
  }

  /**
   * ADR-0040: a vendor import stops the service; when the operator forced the vendor tools on a
   * server that takes REST imports, and nothing else (a source keystore, an archive above the REST
   * limit) needs them, the plan says that the REST route needs no outage at all. The capability
   * probe is read-only; a probe that fails says nothing.
   */
  private void offerRest(
      Strategies.Selection selection,
      ImportOptions options,
      ImportRequest request,
      JrsAdapter adapter,
      Path archive,
      List<String> warnings) {
    if (selection.kind() != ExportImportStrategy.Kind.VENDOR_CLI
        || options.strategy().isEmpty()
        || options.strategy().get() != ExportImportStrategy.Kind.VENDOR_CLI
        || request.sourceKeystore().isPresent()) {
      return;
    }
    try {
      if (Files.size(archive) > restUploadLimit
          || !adapter.capabilities().contains(Capability.IMPORT_ASYNC)) {
        return;
      }
    } catch (IOException | RuntimeException e) {
      return;
    }
    warnings.add(
        "--strategy vendor stops the service for the import; this server takes REST imports"
            + " (IMPORT_ASYNC probe passed), which need no outage at all: leave out --strategy"
            + " vendor to import over REST with the server running");
  }

  /** Where the pre-import snapshot of {@code archive} goes; stable for the same archive bytes. */
  Path snapshotPath(Path archive, String archiveHash) {
    String name = archive.getFileName().toString();
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name;
    String tag = archiveHash.length() >= 12 ? archiveHash.substring(0, 12) : archiveHash;
    return services
        .home()
        .snapshots()
        .resolve(SNAPSHOT_DIR)
        .resolve("pre-import-" + base + "-" + tag + ".zip");
  }

  /**
   * Refuses an export whose {@code --uri} names nothing on the server (field test 2, E3): the
   * server would otherwise answer with an archive holding no resources, and the run would exit 0.
   * The root and a full-server export are not asked about.
   */
  private static void refuseMissingUris(JrsAdapter adapter, ExportRequest request) {
    if (request.fullServer()) {
      return;
    }
    List<String> missing = new ArrayList<>();
    for (String uri : sortedUris(request)) {
      if (!uri.equals("/") && !adapter.resourceExists(uri)) {
        missing.add(uri);
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          String.join(", ", missing)
              + (missing.size() == 1 ? " does" : " do")
              + " not exist on the server; check the folder in the server's repository browser and"
              + " pass --uri with the exact path");
    }
  }

  /**
   * The affected subtree: the sidecar's uris that exist on this server (the whole repository when
   * it lists none or was a full-server export), else "/"; a full-server snapshot when {@code
   * update} targets the root. Users and roles are included when the sidecar says the archive
   * carries them, or always when there is no sidecar to tell. Empty when the sidecar names folders
   * and none of them exists here yet (field test 2, E3): importing new content is the ordinary
   * case, and then there is nothing to put back, which the warnings say.
   */
  static Optional<ExportRequest> snapshotRequest(
      Optional<Sidecar> sidecar,
      boolean update,
      Path output,
      JrsAdapter adapter,
      List<String> warnings) {
    return snapshotRequest(sidecar, update, output, adapter, warnings, Optional.empty(), false);
  }

  /**
   * As above; an import into {@code organization} that would otherwise snapshot the root is scoped
   * to that organisation's folder, never to {@code /} (field test 2, E5 and I4). {@code
   * stopService} is false for every new plan (ADR-0040: {@code js-export} reads the repository with
   * the server up, so a vendor import has one outage) and true only for a plan rebuilt from
   * arguments an earlier jrsctl stored.
   */
  static Optional<ExportRequest> snapshotRequest(
      Optional<Sidecar> sidecar,
      boolean update,
      Path output,
      JrsAdapter adapter,
      List<String> warnings,
      Optional<String> organization,
      boolean stopService) {
    Set<String> uris = new TreeSet<>();
    boolean usersRoles = true;
    boolean access = false;
    boolean audit = false;
    boolean monitoring = false;
    boolean settings = false;
    if (sidecar.isPresent()) {
      Sidecar.Flags flags = sidecar.get().flags();
      if (!flags.fullServer() && flags.scope() == ExportRequest.Scope.REPOSITORY) {
        uris.addAll(flags.uris());
      }
      usersRoles = flags.includeUsersRoles();
      access = flags.includeAccessEvents();
      audit = flags.includeAuditEvents();
      monitoring = flags.includeMonitoring();
      settings = flags.includeSettings();
    }
    if (organization.isPresent() && (uris.isEmpty() || uris.contains("/"))) {
      uris.clear();
      uris.add("/organizations/" + organization.get());
    }
    if (uris.isEmpty() || uris.contains("/")) {
      uris.clear();
      uris.add("/");
    } else {
      Set<String> present = new TreeSet<>();
      for (String uri : uris) {
        if (adapter.resourceExists(uri)) {
          present.add(uri);
        } else {
          warnings.add(
              uri
                  + " does not exist on this server yet, so there is nothing to snapshot for it;"
                  + " if the import fails, what it created there is deleted");
        }
      }
      if (present.isEmpty()) {
        warnings.add(
            "no pre-import snapshot: none of the archive's folders exists on this server yet, so"
                + " a failed import is rolled back by deleting what it created");
        return Optional.empty();
      }
      uris = present;
    }
    boolean root = uris.contains("/");
    boolean fullServer = update && root;
    return Optional.of(
        new ExportRequest(
            fullServer ? ExportRequest.Scope.EVERYTHING : ExportRequest.Scope.REPOSITORY,
            uris,
            usersRoles || fullServer,
            access,
            audit,
            monitoring,
            settings,
            fullServer,
            output,
            stopService));
  }

  /**
   * The folders a failed import of this archive may create from nothing (issue #139): the sidecar's
   * uris, or the organisation's folder for an organisation import; none when the archive holds the
   * whole repository, where the pre-import listing already covers every addition.
   */
  static List<String> newContentUris(Optional<Sidecar> sidecar, Optional<String> organization) {
    Set<String> uris = new TreeSet<>();
    if (sidecar.isPresent()) {
      Sidecar.Flags flags = sidecar.get().flags();
      if (!flags.fullServer() && flags.scope() == ExportRequest.Scope.REPOSITORY) {
        uris.addAll(flags.uris());
      }
    }
    if (organization.isPresent() && (uris.isEmpty() || uris.contains("/"))) {
      uris.clear();
      uris.add("/organizations/" + organization.get());
    }
    if (uris.isEmpty() || uris.contains("/")) {
      return List.of();
    }
    return List.copyOf(uris);
  }

  /** The key alias the sidecar beside {@code archive} records, when there is one to read. */
  private static Optional<String> sidecarKeyAlias(Path archive) {
    try {
      return Sidecar.read(Sidecar.pathFor(archive)).flatMap(s -> s.flags().keyAlias());
    } catch (IOException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** True when both versions parse and their major numbers differ (issue #115). */
  private static boolean crossesMajor(Sidecar sidecar, ServerIdentity identity) {
    Semver source = Semver.coerce(sidecar.serverVersion());
    Semver target = Semver.coerce(identity.version());
    return source != null && target != null && source.getMajor() != target.getMajor();
  }

  /**
   * Issue #115 (REST reference 10.1 p.118): a large archive uploaded over REST fails inside the
   * server after a long transfer. Above {@link #REST_UPLOAD_LIMIT} an automatic choice of REST
   * becomes the vendor tools when this machine has them and a refusal (exit 2) when it has not; an
   * explicit {@code --strategy rest} is the operator's decision and only earns a warning.
   */
  private Strategies.Selection leaveRestAboveTheLimit(
      Strategies.Selection chosen,
      JrsAdapter adapter,
      ImportRequest request,
      ImportOptions options,
      Path archive,
      List<String> warnings) {
    if (chosen.kind() != ExportImportStrategy.Kind.REST) {
      return chosen;
    }
    long size;
    try {
      size = Files.size(archive);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "cannot read the size of " + archive + ": " + e.getMessage(), e);
    }
    if (size <= restUploadLimit) {
      return chosen;
    }
    String big =
        archive.getFileName()
            + " is "
            + String.format(Locale.ROOT, "%.1f", size / (1024.0 * 1024 * 1024))
            + " GiB and the vendor does not recommend uploading more than 2 GB over REST (REST"
            + " reference p.118); the server fails such an upload after the long transfer";
    if (options.strategy().isPresent()) {
      warnings.add(big + "; --strategy rest was given, so the upload is attempted anyway");
      return chosen;
    }
    Strategies.Selection vendor =
        strategies.select(
            services.config(), adapter, request, Optional.of(ExportImportStrategy.Kind.VENDOR_CLI));
    try {
      requireLocalInstallation(
          vendor, Optional.of(ExportImportStrategy.Kind.VENDOR_CLI), false, "import");
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          big
              + ", and the vendor import tools are not available here: "
              + e.getMessage()
              + "; pass --strategy rest to upload it anyway");
    }
    warnings.add(big + "; the vendor import tools are used instead");
    return new Strategies.Selection(
        vendor.strategy(), "vendor CLI: the archive is above 2 GB (REST reference p.118)");
  }

  /** The first version whose exports the vendor says cannot go into an older server. */
  static final String FIRST_ONE_WAY_VERSION = "10.1.0";

  /**
   * Release notes 10.1 p.6: "Resources exported from version 10.1.0 cannot be imported into older
   * versions" (issue #107). Refused at plan time, before the snapshot and the import, unless the
   * operator forces it; a forced import is a warning and an audit row. A version that does not
   * parse on either side is not judged.
   */
  private void refuseNewerCatalog(
      Sidecar sidecar, ServerIdentity identity, boolean force, List<String> warnings) {
    Semver source = Semver.coerce(sidecar.serverVersion());
    Semver target = Semver.coerce(identity.version());
    if (source == null
        || target == null
        || source.isLowerThan(FIRST_ONE_WAY_VERSION)
        || target.isGreaterThanOrEqualTo(FIRST_ONE_WAY_VERSION)) {
      return;
    }
    String what =
        "the archive was exported from JasperReports Server "
            + sidecar.serverVersion()
            + " and this server is "
            + identity.version()
            + ": resources exported from 10.1.0 or later cannot be imported into older versions"
            + " (release notes 10.1)";
    if (!force) {
      throw new IllegalArgumentException(
          what
              + "; import it into a 10.1 or later server, export the data from a server of this"
              + " version, or pass --force-version to try anyway (audited)");
    }
    warnings.add(what + "; --force-version was given, so the import is attempted anyway");
    try {
      services.stateStore().get().audit("operator", "--force-version", what);
    } catch (RuntimeException e) {
      warnings.add("cannot write the audit row for --force-version: " + e.getMessage());
    }
  }

  private static Optional<Sidecar> readSidecar(Path archive, List<String> warnings) {
    Path file = Sidecar.pathFor(archive);
    try {
      Optional<Sidecar> sidecar = Sidecar.read(file);
      if (sidecar.isEmpty()) {
        warnings.add(
            "no sidecar "
                + file.getFileName()
                + " next to the archive: the keystore fingerprint cannot be verified and the"
                + " whole repository is snapshotted");
      }
      return sidecar;
    } catch (IOException | IllegalArgumentException e) {
      warnings.add(
          "sidecar "
              + file
              + " is unreadable ("
              + e.getMessage()
              + "); the import precheck will"
              + " report it and the whole repository is snapshotted");
      return Optional.empty();
    }
  }

  // ---------------------------------------------------------------- helpers

  private static String target(ExportRequest request) {
    return request.fullServer() ? "full server" : String.join(", ", sortedUris(request));
  }

  /** The repository subtrees a request covers; the root for a full-server or unscoped request. */
  private static List<String> sortedUris(ExportRequest request) {
    if (request.fullServer() || request.uris().isEmpty()) {
      return List.of("/");
    }
    return List.copyOf(new TreeSet<>(request.uris()));
  }

  static String strategyLine(Strategies.Selection selection) {
    String kind =
        switch (selection.kind()) {
          case REST -> "rest";
          case VENDOR_CLI -> "vendor";
        };
    return kind + " (" + selection.reason() + ")";
  }

  /**
   * #68: REST needs only the server address, so it works from any machine; the vendor tools need
   * the installation on this one. A plan that would use them without one is refused while planning,
   * with the reason REST was not used, instead of failing when the tools cannot be found.
   */
  private void requireLocalInstallation(
      Strategies.Selection selection,
      Optional<ExportImportStrategy.Kind> forced,
      boolean fullServer,
      String operation) {
    if (selection.kind() != ExportImportStrategy.Kind.VENDOR_CLI
        || services.config().server().namesLocalInstallation()) {
      return;
    }
    String why;
    if (forced.isPresent() && forced.get() == ExportImportStrategy.Kind.VENDOR_CLI) {
      why = "--strategy vendor uses the vendor " + operation + " tools";
    } else if (fullServer) {
      why = "a full-server export uses the vendor tools (js-export)";
    } else {
      why =
          "REST cannot be used ("
              + selection.reason()
              + "), so the "
              + operation
              + " would need the vendor tools";
    }
    throw new IllegalArgumentException(
        why
            + ", which need a JasperReports Server installation on this machine, and this"
            + " configuration names none (server.installDir, server.tomcatDir or"
            + " server.buildomaticDir); run jrsctl on the JasperReports Server host, or use REST"
            + " from here");
  }

  private static String describe(ExportRequest r) {
    return "scope="
        + r.scope()
        + ";uris="
        + new TreeSet<>(r.uris())
        + ";usersRoles="
        + r.includeUsersRoles()
        + ";access="
        + r.includeAccessEvents()
        + ";audit="
        + r.includeAuditEvents()
        + ";monitoring="
        + r.includeMonitoring()
        + ";settings="
        + r.includeSettings()
        + ";fullServer="
        + r.fullServer()
        // only a live export says so, so a stopping plan keeps the fingerprint it had before #67
        + (r.stopService() ? "" : ";stopService=false")
        + r.keyAlias().map(a -> ";keyAlias=" + a).orElse("")
        + r.organization().map(o -> ";organization=" + o).orElse("");
  }

  private static String describe(ImportRequest r) {
    return "update="
        + r.update()
        + ";skipUserUpdate="
        + r.skipUserUpdate()
        + ";access="
        + r.includeAccessEvents()
        + ";audit="
        + r.includeAuditEvents()
        + ";monitoring="
        + r.includeMonitoring()
        + ";settings="
        + r.includeSettings()
        + ";skipThemes="
        + r.skipThemes()
        + ";sourceKeystore="
        + r.sourceKeystore().map(Path::toString).orElse("")
        + ";sourceKeystorePassword="
        + r.sourceKeystorePassword().map(ref -> ref.render()).orElse("")
        + r.keyAlias().map(a -> ";keyAlias=" + a).orElse("")
        + r.organization()
            .map(o -> ";organization=" + o + (r.mergeOrganization() ? ";merge" : ""))
            .orElse("");
  }

  private String configHash() {
    return sha256(ConfigShow.render(services.config()));
  }

  private static String sha256(String text) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String planId(String operation) {
    return operation + "-" + UUID.randomUUID();
  }
}
