# Contributing to jrsctl

jrsctl is the JasperReports Server lifecycle tool from Jaspersoft. It stops services,
swaps files under `WEB-INF`, runs vendor upgrade scripts and re-imports repositories, so the
project puts execution safety, idempotent steps, honest rollback and credential hygiene ahead of
everything else. This guide says how to build, test and submit a change that keeps those
promises. The contract is `docs/spec.md` (Draft 1.2); `CLAUDE.md` at the repository root is the
short form every contributor and agent follows.

## Before you start

- Read `docs/spec.md` before changing architecture, and `docs/decisions/` before proposing a new
  third-party dependency, a new platform, or a change to the module layout. The approved
  dependency list is spec §13.3; anything else needs an ADR first.
- `docs/BUILD_STATUS.md` says what is done, what is stubbed and what is known to be missing.
  `docs/reviews/` holds the code reviews and the assessment that drive the current follow-ups.

## Repository layout

Six Maven modules, dependencies flowing one way (spec §4):

| Module | Owns |
|---|---|
| `core` | configuration and schema, secrets, `Platform` (file, process and service abstractions), the SQLite run journal, snapshots, the compatibility matrix, redaction, the sealed `Event` types, the engine (`Plan`, `Step`, `Runner`, retry, cancellation, `EventBus`), the run lock |
| `jrs` | REST v2 client, the capability-driven `RestJrsAdapter`, probes, the export/import strategies, vendor-tool wrappers, keystore inspection |
| `ops` | `hotfix`, `export`, `import`, `upgrade`, `customizations` as plans; `init`, `doctor`, `smoke` as reports |
| `app` | picocli commands, `--json`, the progress renderer, guided mode, the support bundle, `Main` |
| `dist` | jlink image, portable ZIP and tar.gz, SBOM, checksums; signing runs in CI only |
| `acceptance` | `PhaseNXxxTest` classes tagged `phaseN`, run against the shaded jar `app/target/jrsctl.jar` |

Package root `com.jaspersoft.jrsctl.<module>`. The acceptance module never imports app classes: it
drives the packaged jar the way an operator would.

## Building

- Java 21 and Maven 3.9 or newer. The machine's default `java` may be older; the wrapper scripts
  select JDK 21 for you.
- Windows: `scripts\mvn.cmd`; Linux: `scripts/mvn.sh`. Both delegate to the Maven Wrapper
  (`mvnw`, `mvnw.cmd`), which you can also call directly once `JAVA_HOME` points at JDK 21.
  On Linux, `scripts/mvn.sh` needs `JRSCTL_JDK` or `JAVA_HOME` set to a JDK 21 home.
- The build works from any of these (#188):
  - a clone: `git clone`, then the scripts above;
  - a source archive (GitHub's "Download ZIP", or a release's source code archive), which has
    no `.git` and, unpacked by most tools, no executable bits: run the scripts through their
    shell, `bash scripts/mvn.sh verify` (`scripts\mvn.cmd verify` on Windows). They call the
    wrapper through `sh` and skip the git hook setup, and `Phase0SkeletonTest` skips its check of
    the git index. Nothing else in the build reads git metadata;
  - as root on Linux (a container, a build VM): root ignores permission bits, so the few tests
    that take a permission away and expect jrsctl to be refused skip themselves there
    (`PermissionBypass` in the core tests finds this out by reading a mode-000 file). CI's
    `archive-as-root` job builds an unpacked `git archive` ZIP as root on every push. A normal
    account is still the better choice: the tests that skip as root only run there.
- `scripts\mvn.cmd verify` is the gate: compile with `-Werror` and Error Prone, unit tests,
  Spotless (google-java-format), then every acceptance phase against the shaded jar. It takes
  about eight minutes on a laptop.
- One acceptance phase only: `scripts\mvn.cmd verify -Dphase=N`.
- After any change to a pom (a dependency bump, a plugin, a property), gate with
  `scripts\mvn.cmd clean verify`. An incremental build reuses compiled classes for unchanged
  sources, so a deprecation that the new dependency introduces under `-Werror` shows up in CI's
  fresh checkout and not on your machine.
- Coverage: `verify` fails a module whose line coverage falls below the `jacoco.line.minimum`
  in its own pom (core 0.80, ops 0.78, the others unset). The report is at
  `<module>/target/site/jacoco/index.html`. Raise the floor when you raise the coverage; never
  lower it to make a build pass.
- What is stale: `scripts\mvn.cmd -N versions:display-dependency-updates` and
  `scripts\mvn.cmd -N versions:display-plugin-updates`. The ruleset in `build/version-rules.xml`
  keeps pre-release versions out of the report. Nothing is ever bumped automatically; Dependabot
  opens the pull requests and a human decides, and a major version is its own change with its own
  gate.
- Format before committing: `scripts\mvn.cmd spotless:apply`. The pre-commit hook in `.githooks`
  runs the Spotless check on the staged Java files only; the build scripts set
  `core.hooksPath` to `.githooks` the first time they run, so the hook is active after one
  `scripts\mvn.cmd` call (or `git config core.hooksPath .githooks` by hand). Git runs the hook
  through its own `sh` on Windows too.
- Never commit per-developer files: `.claude/` (agent plugins and permissions) and
  `tmp-real-home/` (a scratch home for manual checks against a real server) are ignored, and
  `Phase0SkeletonTest` fails if either is tracked.
- Tests tagged `needs-jrs` (a real JasperReports Server) or `needs-docker` are excluded by default;
  they are release gates, not phase gates (spec §0 rule 10).
- Building is for contributors. Operators and testers use the release archive, which carries its
  own Java 21 runtime and needs no JDK on the host; the Java that buildomatic runs with is a
  separate setting (`vendor.javaHome`). jrsctl stays on Java 21: the code uses Java 21 language
  features, and the bundled runtime makes the host's Java irrelevant (#65).
- A test that fails on your machine but passes in CI is a bug, not something to skip: open an issue
  with the test name, the operating system and the report from `<module>/target/surefire-reports/`.
  Do not build with `-DskipTests` to try the tool; use the release archive instead.
- Distribution: `scripts\build-dist.cmd` or `scripts/build-dist.sh` (Maven profile `dist`).
- Windows and Linux on x86-64 are the supported platforms (ADR-0002); nothing else is built or
  tested.

## Rules that are not negotiable (spec §0, §6, §15)

- Mutations only inside a `Step`. Every `Step.execute(Context, EventSink)` is idempotent and has a
  `compensate` that converges from any partial state, or the step is `irreversible()` with a
  justification comment. The Runner compensates the failing step first, then the succeeded ones
  (ADR-0009), and `runs recover` re-runs compensations, so a compensation may run more than once.
- Read-only operations (`init`, `doctor`, `smoke`, `selfcheck`, `list`) are plain functions that
  return a report; they never write.
- Any change under `WEB-INF/lib` or `WEB-INF/classes` stops the service first, on both platforms.
- SQLite `state.db` is the only run journal: WAL, `synchronous=FULL`, append-only step
  transitions written before the matching event is emitted.
- No `Runtime.exec(String)`, no shell; arguments as lists through `Platform.processes()`. No
  `java.io.File`; streaming I/O only.
- Secrets live in `char[]`, are never logged, and every output stream passes the redaction filter.
  The Runner redacts every event before any subscriber sees it; do not rely on a sink to redact.
- No new third-party dependency without an ADR. BouncyCastle and JNA are not approved.
- When the spec is ambiguous, take the safer option, write `docs/decisions/NNNN-title.md`, and
  continue.

## Conventions

- Java 21: records and sealed interfaces; pattern-matching `switch` with no `default` over a
  sealed type; no `null` returns from public APIs (`Optional` or a sealed result).
- One-paragraph Javadoc stating the invariants on every public class.
- Test names read `should_<behaviour>_when_<condition>`. Acceptance tests use a verb phrase that
  names the observable outcome.
- Write the test first and watch it fail before writing the fix. A test that passes on the first
  run proves nothing; the reviews in `docs/reviews/` cite several defects that only a failing test
  would have caught.
- Every mutating `Step` needs an execute-twice test and a compensate-twice test;
  `IdempotencyCoverageTest` in `ops` scans the classpath and fails when a step has none. A step
  that can fail part-way (file swap, SQL, vendor script) also needs a test that drives that failure
  through the `Runner` and checks the server afterwards.
- Adapter changes are tested against WireMock fixtures recorded from real servers
  (`jrs/src/test/resources/fixtures/`, one per supported version and edition), and the adapter
  contract runs against every recorded server under `jrs/src/test/resources/recordings/` (one per
  compat-matrix row; see its README to record a new one from a live server).

## Commits and pull requests

- Conventional Commits, one logical change per commit: `fix(core): ...`, `feat(app): ...`,
  `test(ops): ...`, `docs: ...`. The subject says what changed; the body says why and how it was
  verified.
- One branch per phase or per fix. Work on `main` is accepted only for the maintainer's own phase
  boundaries.
- Before opening a pull request: `scripts\mvn.cmd verify` green locally; `docs/BUILD_STATUS.md`
  updated when a phase, a gap or a review follow-up changed; `docs/spec-changelog.md` and the
  affected ADR updated when the contract changed; `docs/operator-guide.md` updated when a command,
  flag or exit code changed (the guide is embedded in the jar and `--explain` prints its sections,
  so `Phase8ExplainDocsTest` fails when a command lacks its section).
- Keep the three kinds of built-in help apart (#61): `--help` is the synopsis, one line per flag,
  and 2-3 worked examples from `app/.../HelpExamples.java` (`HelpExamplesTest` fails when a
  runnable command has none, or when an example does not parse); `--explain` is what the command
  changes, how it is undone, its exit codes and flags, from the operator guide; `jrsctl docs` is
  the task guides. A new command needs all three.
- CI (`.github/workflows/ci.yml`) runs the full gate on Ubuntu and Windows, packages both
  archives, and audits dependencies. A pull request must be green on both operating systems.

## Cutting a release

The pom stays at a `-SNAPSHOT` version on `main`; a release is a tag, never a version bump by
hand. The only hand bump is the move to the next `-SNAPSHOT` after a tag (step 6).

1. Make sure `main` is green in CI on both operating systems and the dependency audit passed.
2. Bump `project.build.outputTimestamp` in the root pom to the release date (reproducible archives),
   commit, push, wait for green.
3. Tag the commit `vX.Y.Z` (or `vX.Y.Z-rc1` for a pre-release) and push the tag.
4. CI does the rest: every job runs `versions:set` from the tag, builds with the `release` profile
   (which refuses SNAPSHOT versions and SNAPSHOT dependencies), packages and smoke-tests both
   archives, checks that both carry the tag's version and match their checksums, signs the
   archives, checksums and SBOM with the CI-only key, and publishes the GitHub release. A tag
   containing `-` is published as a pre-release.
5. Never upload hand-built archives to a release and never rename a SNAPSHOT build: the `release`
   job is the only path that produces signed artefacts.
6. Once the tag is pushed, move `main` to the next development version:
   `scripts/mvn.sh versions:set -DnewVersion=X.Y+1.0-SNAPSHOT -DgenerateBackupPoms=false`, then
   commit. Otherwise every development build, its archive name and any support bundle claims a
   version older than the one just shipped.

Every action in the workflows is pinned to a commit and every job has a timeout;
`Phase0SkeletonTest` fails when either slips, and Dependabot's github-actions updates keep the
pins current.

## Licence

jrsctl is licensed under the GNU General Public License, version 3 only (`GPL-3.0-only`,
ADR-0010; text in `LICENSE`). By submitting a change you agree that it is contributed under that
licence and that you have the right to do so; there is no contributor licence agreement to sign.
Do not add a dependency whose licence is incompatible with GPL-3.0 (EPL-only, for example): spec
§13.3 lists the approved libraries and `dist/src/image/LICENSE-THIRD-PARTY.txt` names the arm of
each dual licence in use. `Phase0SkeletonTest` fails when any document names a different licence.

## Reporting a problem

- Bugs and feature requests: open a GitHub issue with the jrsctl version (`jrsctl --version`),
  the operating system, the JasperReports Server version and edition, the exact command, and the
  relevant lines of `logs/jrsctl.log` (already redacted). `jrsctl runs support-bundle <id>`
  contains all of that.
- Security vulnerabilities: do not open a public issue. Use GitHub's private vulnerability
  reporting for this repository (Security tab, "Report a vulnerability"), as described in
  `docs/security.md`.

## Code of conduct

`CODE_OF_CONDUCT.md` (Contributor Covenant 2.0) applies to every issue, pull request and
discussion. Reports of unacceptable behaviour go to the maintainer at gorsuchrobert@gmail.com, the
address named in that file, never to a public issue.
