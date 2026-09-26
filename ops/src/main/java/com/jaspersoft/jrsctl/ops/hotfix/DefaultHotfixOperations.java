package com.jaspersoft.jrsctl.ops.hotfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.config.ConfigWriter;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.RunIds;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.keys.KeyRing;
import com.jaspersoft.jrsctl.core.platform.DiskSpace;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import com.jaspersoft.jrsctl.jrs.service.ServiceSteps;
import com.jaspersoft.jrsctl.ops.ClusterNotice;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.db.DefaultJdbcConnector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The hotfix subsystem entry point (spec §8): builds, verifies, plans the application and the
 * rollback of signed bundles, and lists what is installed. Invariants: nothing here mutates the
 * server; every mutation lives in a {@link Step} of the returned {@link Plan}; a plan is refused
 * with {@link HotfixException} when the bundle is unreadable, its manifest invalid, its hashes
 * wrong or its signature untrusted (unless {@code allowUnsigned}), and a rollback plan is refused
 * for unknown, non-installed or LIFO-blocked ids (unless {@code cascade}); fingerprints cover the
 * server identity, the bundle, every target file and the effective configuration.
 */
public final class DefaultHotfixOperations implements HotfixOperations {

  static final String APPLY_OPERATION = "hotfix.apply";
  static final String ROLLBACK_OPERATION = "hotfix.rollback";
  static final String STRATEGY = "snapshot";

  private final HotfixRuntime rt;
  private final ManifestValidator validator = new ManifestValidator();
  private final BundleWorkspace bundles;

  public DefaultHotfixOperations(Services services) {
    this(
        new HotfixRuntime(
            services,
            new SnapshotStore(services.home(), services.platform().files(), services.clock()),
            new DefaultJdbcConnector(),
            new KeyRing(services.home()),
            HttpProbe.rest(services),
            Sleeper.system()));
  }

  DefaultHotfixOperations(HotfixRuntime rt) {
    this.rt = Objects.requireNonNull(rt, "rt");
    this.bundles = new BundleWorkspace(rt.home());
  }

  @Override
  public Path build(Path bundleDir, SecretRef privateKeyRef, Path out) {
    return new HotfixBuilder(rt.files(), rt.services().secrets())
        .build(bundleDir, privateKeyRef, out);
  }

  @Override
  public VerifyReport verify(Path bundle) {
    Source source = source(bundle);
    return bundles.with(
        source.bundle(),
        b -> {
          BundleVerifier.Signature signature = rt.verifier().signature(b);
          Optional<String> signedBy = signature.signedBy().map(KeyRing.TrustedKey::name);
          List<String> hashProblems = new ArrayList<>();
          List<String> applicability = new ArrayList<>();
          String id = "";
          String title = "";
          switch (validator.validate(b.manifestJson())) {
            case ManifestValidator.Result.Valid v -> {
              id = v.manifest().id();
              title = v.manifest().title();
              hashProblems.addAll(rt.verifier().hashProblems(b, v.manifest()));
              applicability.addAll(applicability(v.manifest()));
            }
            case ManifestValidator.Result.Invalid i -> {
              for (String p : i.problems()) {
                hashProblems.add("manifest: " + p);
              }
              JsonNode tree = lenientTree(b.manifestJson());
              id = tree.path("id").asText("");
              title = tree.path("title").asText("");
            }
          }
          return new VerifyReport(
              signature.valid(),
              signedBy,
              hashProblems.isEmpty(),
              hashProblems,
              applicability.isEmpty(),
              applicability,
              id,
              title,
              source.official(),
              source.sha256());
        });
  }

  @Override
  public Plan planApply(Path bundle, ApplyOptions options) {
    Objects.requireNonNull(options, "options");
    Source source = source(bundle);
    return bundles.with(source.bundle(), b -> planApply(source, b, options));
  }

  /**
   * The bundle a command works from, what the operator must be told about its origin, and the
   * SHA-256 of the file the operator gave (empty when it does not exist; the workspace reports
   * that).
   */
  private record Source(Path bundle, boolean official, List<String> notes, String sha256) {
    Source {
      notes = List.copyOf(notes);
    }
  }

  static final String NEITHER_SHAPE =
      " is neither an official Jaspersoft hotfix package (readme.txt beside jasperserver[-pro].zip,"
          + " js-install.zip or an unpacked jasperserver[-pro]/ tree) nor a jrsctl hotfix bundle"
          + " (manifest.json at the root)";

  /**
   * The bundle to work from: {@code given} itself, or, for an official Jaspersoft package, the
   * bundle derived from it under the jrsctl home (#66, ADR-0024). The derived bundle is named after
   * the package's own hash, so planning and then applying converts once.
   */
  private Source source(Path given) {
    if (!Files.isRegularFile(given)) {
      return new Source(given, false, List.of(), "");
    }
    if (!OfficialPackage.looksOfficial(given)) {
      if (HotfixBundle.lacksRootManifest(given)) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            given + NEITHER_SHAPE,
            "point jrsctl at the hotfix ZIP as support published it, or at a bundle built with"
                + " jrsctl hotfix build");
      }
      return new Source(given, false, List.of(), hash(given));
    }
    List<String> notes = new ArrayList<>();
    Config config = rt.config();
    HotfixPaths paths = HotfixPaths.from(config, rt.services().platform());
    String webappName =
        config
            .server()
            .webappName()
            .map(Config.WebappName::yamlValue)
            .orElse(Config.WebappName.JASPERSERVER_PRO.yamlValue());
    String sourceHash = hash(given);
    Path out =
        rt.home().runs().resolve("hotfix-official-" + sourceHash.substring(0, 16) + ".jrsctl.zip");
    notes.add(
        "official package "
            + given.getFileName()
            + " (sha256 "
            + sourceHash
            + "); compare it with the checksum on the support portal");
    try {
      OfficialPackage.Converted converted =
          Files.isRegularFile(out) && Files.isRegularFile(OfficialPackage.notesFile(out))
              ? OfficialPackage.read(out)
              : OfficialPackage.convert(given, out, webappName, paths);
      notes.add("converted to the jrsctl bundle " + converted.id() + " at " + out);
      notes.addAll(converted.notes());
      return new Source(out, true, notes, sourceHash);
    } catch (IOException e) {
      if (DiskSpace.outOfSpace(e)) {
        // field test 3: converting into a full home is not a bad download
        throw new HotfixException(
            HotfixException.PRECHECK,
            "the jrsctl home ran out of space converting " + given + " into " + out,
            DiskSpace.remedy(rt.home().root()),
            e);
      }
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot read the official hotfix package " + given + ": " + e.getMessage(),
          "check the download; it must be the ZIP as support published it",
          e);
    }
  }

  private Plan planApply(Source source, HotfixBundle b, ApplyOptions options) {
    Path bundle = source.bundle();
    List<String> warnings = new ArrayList<>(source.notes());
    BundleVerifier.Signature signature = rt.verifier().signature(b);
    if (!signature.valid()) {
      // --allow-unsigned waives a missing signature only; a present one that fails to verify may
      // be a tampered bundle and is refused whatever the flag says (assessment item H3).
      if (signature.present()) {
        throw new HotfixException(
            HotfixException.SIGNATURE,
            BundleSignatures.FAILING,
            "add the signer's public key with `jrsctl keys add <name> <file>`; --allow-unsigned"
                + " does not waive a signature that fails to verify");
      }
      String missing =
          source.official()
              ? "official hotfix packages carry no jrsctl signature"
              : BundleSignatures.MISSING;
      if (!options.allowUnsigned()) {
        throw new HotfixException(
            HotfixException.SIGNATURE,
            missing,
            source.official()
                ? "check the package against the checksum on the support portal, then re-run with"
                    + " --allow-unsigned"
                : "have the bundle signed, or re-run with --allow-unsigned");
      }
      warnings.add(
          switch (options.unsigned()) {
            case CHECKSUM_CONFIRMED ->
                missing
                    + "; accepted: checksum confirmed by the operator against the support portal"
                    + " (ADR-0027)";
            case ALLOW_UNSIGNED, REFUSED -> missing + "; accepted with --allow-unsigned";
          });
    }
    Manifest manifest =
        switch (validator.validate(b.manifestJson())) {
          case ManifestValidator.Result.Valid v -> v.manifest();
          case ManifestValidator.Result.Invalid i ->
              throw new HotfixException(
                  HotfixException.PRECHECK,
                  "manifest is not valid:\n  " + String.join("\n  ", i.problems()),
                  "obtain a bundle with a valid manifest");
        };
    List<String> hashProblems = rt.verifier().hashProblems(b, manifest);
    if (!hashProblems.isEmpty()) {
      throw new HotfixException(
          HotfixException.SIGNATURE,
          "bundle content does not match the manifest:\n  " + String.join("\n  ", hashProblems),
          "obtain the bundle again; it is corrupt or tampered with");
    }
    Config config = rt.config();
    HotfixPaths paths = HotfixPaths.from(config, rt.services().platform());
    List<FileTarget> targets = FileTarget.resolve(manifest, paths, rt.files());
    Optional<Config.DatabaseType> dbType = config.database().type();
    List<Manifest.SqlEntry> sqlScripts = dbType.map(manifest::sqlFor).orElse(List.of());
    ApplyInput in =
        new ApplyInput(
            bundle.toAbsolutePath().normalize(),
            hash(bundle),
            manifest,
            paths,
            targets,
            options.unsigned(),
            dbType,
            sqlScripts);

    Optional<ServerIdentity> identity = identity();
    identity.ifPresentOrElse(
        i -> warnings.addAll(Applicability.check(manifest, i)),
        () -> warnings.add("server unreachable at plan time; validate-manifest will refuse"));
    // review §3.2 (issue #112): a hotfix applied here reaches this node only
    ClusterNotice.warning(rt.services()).ifPresent(warnings::add);
    // review §3.3 (issue #113): a cumulative hotfix can bring or reset the telemetry switch
    TelemetryNotice.warning(manifest, paths, webappName(config)).ifPresent(warnings::add);
    if (!manifest.sql().isEmpty() && dbType.isEmpty()) {
      warnings.add("the manifest carries SQL but the database section is not configured");
    }
    if (manifest.rollback() == Manifest.Rollback.IRREVERSIBLE) {
      warnings.add(
          "SQL changes are irreversible: " + manifest.rollbackNote().orElse("no rollbackNote"));
      warnings.add("database rollback is the operator's responsibility");
    }

    List<Step> steps = new ArrayList<>();
    steps.add(new HotfixVerifySteps.VerifySignature(rt, in));
    steps.add(new HotfixVerifySteps.ValidateManifest(rt, in));
    steps.add(new HotfixVerifySteps.Preflight(rt, in));
    steps.add(new HotfixVerifySteps.RunChecks(rt, in, false));
    steps.add(new HotfixBackupSteps.TakeSnapshot(rt, in));
    // staging writes only under runs/<runId>, so it runs before the stop and the outage covers
    // the swap alone (issue #184)
    steps.add(new HotfixApplyPhaseSteps.StageFiles(rt, in));
    if (in.restartRequired()) {
      steps.add(ServiceSteps.stop(rt, ApplySteps.APPLY, ServiceSteps.STOP));
    }
    steps.add(new HotfixApplyPhaseSteps.AtomicSwap(rt, in));
    if (in.hasSql()) {
      steps.add(new HotfixApplyPhaseSteps.ApplySql(rt, in));
    }
    if (in.restartRequired()) {
      steps.add(ServiceSteps.start(rt, ApplySteps.APPLY, ServiceSteps.START));
      steps.add(ServiceSteps.waitForServer(rt, ApplySteps.APPLY, ServiceSteps.WAIT));
    }
    steps.add(new HotfixVerifySteps.RunChecks(rt, in, true));
    steps.add(new HotfixRecordSteps.RecordInstalled(rt, in));

    Path snapshotDir = rt.home().snapshots().resolve("{runId}").resolve(ApplySteps.SNAPSHOT);
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(ApplySteps.VERIFY, "nothing mutated");
    rollbackPoints.put(ApplySteps.BACKUP, "snapshot written, server untouched");
    rollbackPoints.put(
        ApplySteps.APPLY,
        "restore " + snapshotDir + (in.restartRequired() ? ", restart service" : ""));
    rollbackPoints.put(
        ApplySteps.RECORD,
        "a failure here undoes the whole apply: restore "
            + snapshotDir
            + (in.restartRequired() ? ", restart service" : "")
            + ", no state store row");
    PlanSummary summary =
        new PlanSummary(
            APPLY_OPERATION,
            manifest.id() + " " + manifest.title(),
            in.touched(),
            List.of(),
            in.restartRequired(),
            List.of(snapshotDir),
            rollbackPoints,
            STRATEGY,
            warnings);

    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("server", identity.map(ServerIdentity::fingerprintInput).orElse("unreachable"));
    inputs.put("bundle", in.bundleSha256());
    inputs.put("config", configHash(config));
    for (FileTarget t : targets) {
      inputs.put("target:" + t.manifestPath(), t.before().orElse("absent"));
      for (FileTarget.Sibling s : t.replaces()) {
        inputs.put(
            "target:" + t.manifestPath() + "/replaces/" + s.path().getFileName(),
            s.before().orElse("absent"));
      }
    }
    return new Plan(
        "hotfix-apply-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  @Override
  public Plan planRollback(String hotfixId, RollbackOptions options) {
    Objects.requireNonNull(hotfixId, "hotfixId");
    Objects.requireNonNull(options, "options");
    StateStore store = rt.store();
    HotfixInstalled target =
        store
            .hotfix(hotfixId)
            .orElseThrow(
                () ->
                    new HotfixException(
                        HotfixException.PRECHECK,
                        "unknown hotfix " + hotfixId,
                        "run jrsctl hotfix list"));
    if (target.state() != HotfixState.INSTALLED) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          hotfixId + " is not installed (state " + target.state() + ")",
          "run jrsctl hotfix list");
    }
    if (target.recorded()) {
      // ADR-0030 (issue #99): a recorded row owns no files and no snapshot
      throw new HotfixException(
          HotfixException.PRECHECK,
          hotfixId + " was applied outside jrsctl and only recorded; nothing to put back",
          "remove the hotfix by hand following the vendor's readme; the row stays as the inventory"
              + " of what was applied to this server");
    }
    List<HotfixInstalled> installed = store.installedHotfixes();
    Map<String, Integer> order = new LinkedHashMap<>();
    for (int i = 0; i < installed.size(); i++) {
      order.put(installed.get(i).id(), i);
    }
    List<String> chain =
        RollbackChain.of(
            store,
            target,
            order,
            options.cascade(),
            id ->
                store
                    .hotfix(id)
                    .flatMap(
                        h ->
                            storedManifest(
                                rt.home()
                                    .runDir(h.installedRunId())
                                    .resolve(ApplyInput.BUNDLE_DIR))));

    List<Step> steps = new ArrayList<>();
    List<Path> touched = new ArrayList<>();
    List<Path> backups = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    Map<String, String> inputs = new LinkedHashMap<>();
    boolean restart = false;
    Optional<Config.DatabaseType> dbType = rt.config().database().type();
    for (String id : chain) {
      HotfixInstalled hotfix = store.hotfix(id).orElseThrow();
      List<HotfixFile> files = store.hotfixFiles(id);
      String suffix = chain.size() > 1 ? ":" + id : "";
      String phase = chain.size() > 1 ? RollbackSteps.PHASE + ":" + id : RollbackSteps.PHASE;
      Path bundleDir = rt.home().runDir(hotfix.installedRunId()).resolve(ApplyInput.BUNDLE_DIR);
      Optional<Manifest> manifest = storedManifest(bundleDir);
      List<Manifest.SqlEntry> sql = manifest.flatMap(m -> dbType.map(m::sqlFor)).orElse(List.of());
      RollbackSteps.Input in =
          new RollbackSteps.Input(hotfix, files, manifest, bundleDir, sql, phase, suffix);
      boolean irreversible =
          manifest.map(m -> m.rollback() == Manifest.Rollback.IRREVERSIBLE).orElse(false);
      // Without the manifest the SQL rollback scripts and the restart requirement are unknown, and
      // a rollback that silently skipped them would report success with database changes or live
      // classes left behind (assessment item H1). Refuse, as planRollback for an upgrade refuses a
      // half-gone point B.
      if (manifest.isEmpty()) {
        throw new HotfixException(
            HotfixException.PRECHECK,
            id
                + ": no readable bundle copy under "
                + bundleDir
                + "; its manifest names the SQL rollback scripts and says whether the service must"
                + " be restarted, so a rollback without it cannot be complete",
            "restore "
                + bundleDir
                + " from a backup of the jrsctl home, or roll the hotfix back by hand from "
                + rt.home().snapshots().resolve(hotfix.installedRunId()));
      }
      if (irreversible) {
        warnings.add(
            id
                + ": SQL changes were declared irreversible: "
                + manifest.flatMap(Manifest::rollbackNote).orElse(""));
        warnings.add("database rollback is the operator's responsibility");
      }
      boolean stop = in.needsServiceStop();
      restart |= stop;
      if (stop) {
        steps.add(ServiceSteps.stop(rt, phase, ServiceSteps.STOP + suffix));
      }
      steps.add(new RollbackSteps.RestoreSnapshot(rt, in));
      if (!irreversible && !in.rollbackScripts().isEmpty()) {
        steps.add(new RollbackSteps.RunSqlRollback(rt, in));
      }
      if (stop) {
        steps.add(ServiceSteps.start(rt, phase, ServiceSteps.START + suffix));
        steps.add(ServiceSteps.waitForServer(rt, phase, ServiceSteps.WAIT + suffix));
      }
      steps.add(new RollbackSteps.RecordRolledBack(rt, in));
      touched.addAll(in.touched());
      backups.add(
          rt.home().snapshots().resolve(hotfix.installedRunId()).resolve(ApplySteps.SNAPSHOT));
      rollbackPoints.put(
          phase,
          "re-apply "
              + id
              + " from snapshots/{runId}/"
              + in.preRollbackStepId()
              + (stop ? ", restart service" : ""));
      inputs.put("hotfix:" + id, hotfix.installedRunId());
      for (HotfixFile f : files) {
        inputs.put("file:" + f.path(), FileTarget.hashOf(rt.files(), f.path()).orElse("absent"));
      }
    }
    Optional<ServerIdentity> identity = identity();
    inputs.put("server", identity.map(ServerIdentity::fingerprintInput).orElse("unreachable"));
    inputs.put("config", configHash(rt.config()));
    PlanSummary summary =
        new PlanSummary(
            ROLLBACK_OPERATION,
            String.join(", ", chain),
            touched,
            List.of(),
            restart,
            backups,
            rollbackPoints,
            STRATEGY,
            warnings);
    return new Plan(
        "hotfix-rollback-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  @Override
  public List<HotfixInstalled> list() {
    return rt.store().hotfixes();
  }

  static final String AUDIT_RECORDED = "hotfix.recorded";

  @Override
  public HotfixInstalled record(Path officialPackage) {
    Objects.requireNonNull(officialPackage, "officialPackage");
    if (!Files.isRegularFile(officialPackage)) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          officialPackage + " does not exist",
          "point jrsctl hotfix record at the hotfix ZIP as support published it");
    }
    OfficialPackage.Described described;
    try {
      described = OfficialPackage.describe(officialPackage);
    } catch (IOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot read " + officialPackage + ": " + e.getMessage(),
          "check the file, then run again");
    }
    StateStore store = rt.store();
    Optional<HotfixInstalled> existing = store.hotfix(described.id());
    if (existing.isPresent()) {
      HotfixInstalled h = existing.get();
      throw new HotfixException(
          HotfixException.PRECHECK,
          described.id()
              + " is already in the ledger ("
              + h.state()
              + ", "
              + (h.recorded() ? "recorded" : "applied by jrsctl in run " + h.installedRunId())
              + ")",
          "run jrsctl hotfix list; a rolled-back row must be removed before the same id is"
              + " recorded again");
    }
    HotfixInstalled row =
        new HotfixInstalled(
            described.id(),
            described.release(),
            described.title(),
            RECORDED_RUN_ID,
            Optional.empty(),
            HotfixState.INSTALLED,
            rt.services().clock().instant(),
            HotfixInstalled.Origin.RECORDED);
    store.recordHotfixInstalled(row, List.of());
    store.audit(
        rt.actor(),
        AUDIT_RECORDED,
        described.id() + " recorded from " + officialPackage.getFileName() + " (applied by hand)");
    return row;
  }

  /** The run id of a row no run wrote: recorded hotfixes have no run directory or bundle copy. */
  static final String RECORDED_RUN_ID = "recorded";

  private Optional<Manifest> storedManifest(Path bundleDir) {
    Path file = bundleDir.resolve(HotfixBundle.MANIFEST);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return switch (validator.validate(Files.readString(file, StandardCharsets.UTF_8))) {
        case ManifestValidator.Result.Valid v -> Optional.of(v.manifest());
        case ManifestValidator.Result.Invalid i -> Optional.empty();
      };
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private List<String> applicability(Manifest manifest) {
    try {
      return Applicability.check(manifest, rt.identity());
    } catch (JrsUnreachableException | RestException | ConfigException e) {
      return List.of("server unreachable: " + e.getMessage());
    }
  }

  private Optional<ServerIdentity> identity() {
    try {
      return Optional.of(rt.identity());
    } catch (JrsUnreachableException | RestException | ConfigException e) {
      return Optional.empty();
    }
  }

  private String hash(Path file) {
    try {
      return rt.files().sha256(file);
    } catch (IOException e) {
      throw new HotfixException(
          HotfixException.PRECHECK,
          "cannot hash " + file + ": " + e.getMessage(),
          "check the file is readable",
          e);
    }
  }

  static String configHash(Config config) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(md.digest(ConfigWriter.render(config).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static JsonNode lenientTree(String json) {
    try {
      return Json.mapper().readTree(json);
    } catch (IOException e) {
      return Json.mapper().createObjectNode();
    }
  }

  /** The deployed webapp's directory name, {@code jasperserver-pro} unless configured otherwise. */
  static String webappName(Config config) {
    return config
        .server()
        .webappName()
        .map(Config.WebappName::yamlValue)
        .orElse(Config.WebappName.JASPERSERVER_PRO.yamlValue());
  }
}
