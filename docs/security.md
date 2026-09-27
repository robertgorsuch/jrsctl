# jrsctl security notes

This page collects the security rules the tool keeps and the reasoning behind them. The normative
text is spec §5.2 (secrets), §5.8 (redaction) and §11 (security); this page explains them for
operators and reviewers.

## Threat model

jrsctl runs on the machine that hosts JasperReports Server, usually as the administrator who owns
the installation. The assets are the server's files and database, the credentials in `config.yaml`
references, and the ability to start a mutating run. The threats the design answers:

| Threat | Where it comes from | What stops it |
|---|---|---|
| Secrets in output | Logs, `--json`, support bundles, error messages | One `Redactor` masks every configured secret (raw, Base64, URL-encoded, JSON-string-escaped) and the well-known patterns (`password=`, `Authorization:`, `JSESSIONID`, `Bearer`) on every stream that leaves the process |
| Replaying a stale plan | `runs recover` on a plan built against an earlier server state | Plans expire after 30 minutes, execute at most once, and are refused (exit 2) when their fingerprint no longer matches |
| Another local user on the host | reads the home or starts a run | the home jrsctl creates is owner-only, secrets.enc, keys and file: secrets are written owner-only before content, and a second process exits 9 on the run lock |

Out of scope: an attacker with the operator's own account (they can read the config), and
kernel- or hypervisor-level access to the host.

## What the support bundle contains

`jrsctl runs support-bundle <id> [--out <zip>]` writes a zip meant to be attached to a support
ticket:

| Entry | Content |
|---|---|
| `run.json` | The `runs show --json` document (steps, statuses, durations, failure block) |
| `transitions.jsonl` | Every `step_transitions` row of the run, one JSON object per line |
| `server.json` | `{baseUrl, reachable, identity}`, or `{baseUrl, reachable: false, error}` when the server cannot be probed |
| `doctor.json` | A fresh `doctor` run (not cached) |
| `config-redacted.yaml` | The effective configuration with secret references, never values |
| `logs/jrsctl.log` | The run's own lines of the JSON log (its `runId`, or written while it ran), at most 2,000 |
| `logs/tail-jrsctl.log` | The last 200 lines of the JSON log, for context |
| `vendor/buildomatic/js-*.log` | The newest buildomatic script log |
| `vendor/jasperserver.log` | The webapp's own log |
| `vendor/catalina.out` (or `catalina.<date>.log`) | Tomcat's own log |
| `vendor/installation.log` | The installer's log |
| `vendor/default_master.properties` | The buildomatic properties, password keys blanked |

Each `vendor/` file is tail-capped at 5 MB before it reaches the redactor.

Every entry passes through the redactor as it is written, so configured secrets, session cookies
and bearer tokens appear as `[redacted]` in any encoding the redactor knows. The
bundle never contains `secrets.enc`, private keys, keystore files, snapshots or exported archives.
Review `config-redacted.yaml` for host names and paths you consider sensitive before sharing.

## Key management

- Bundle signatures are Ed25519 over `manifest.json` only; the manifest carries the SHA-256 of
  every other file, so the signature transitively covers the whole bundle and any unlisted file
  fails verification.
- The trusted key ring is `$JRSCTL_HOME/keys/trusted/<name>.pub`, managed with `jrsctl keys
  add|remove|generate`; every change is audited. The Jaspersoft publisher key is a resource in the
  jar, cannot be removed, and is what `.sig` files on release archives are checked against. Its
  Ed25519 fingerprint is `245731f29b662027` (`jrsctl keys list` prints it); a release archive whose
  `.sig` does not verify against it did not come from the release job.
- `keys generate` writes the private key once, owner-only, and never again; it is not kept in the
  home, not in the state store and not in any log. Store it outside version control, or in
  `secrets.enc` via `secrets set <name> --from-file`.
- `hotfix apply --allow-unsigned` accepts a bundle that carries no signature for one run and writes an audit row; a signature that is present but fails to verify is refused regardless, since the bundle names no signer and a tampered bundle looks the same as an unknown one
  naming the bundle and the actor. It exists for bundles an operator built themselves; a support
  process should never require it.
- `secrets.enc` is AES-256-GCM with a PBKDF2-HMAC-SHA256 key derived from the passphrase and a
  machine-bound salt; copying the file to another host does not move the secrets. The identity
  is `/etc/machine-id` on Linux and the computer name on Windows (store version 2); a version 1
  store, bound to the DNS host name by builds before 2026-09-10, is rebound in place the first
  time it is unlocked and a version 2 store is never retried with the old identity.

## Reporting a vulnerability

jrsctl is not maintained after 2.3.0, its final release: reports are no longer acted on and no
fixed version will follow (`SECURITY.md` at the repository root). The process below is how reports
were handled while it was maintained.

Do not open a public issue for a security problem. Use GitHub's private vulnerability reporting
for this repository (the Security tab, "Report a vulnerability"), which reaches the maintainers
without publishing the report. Include the jrsctl version (`jrsctl --version`), the operating
system, and what an attacker can do; a support bundle is redacted and safe to attach. Expect an
acknowledgement before a fix is discussed in public, and a fixed version before the report is.

## Hardening (Phase 8)

Controls that hold for every command, and what the two offline help features deliberately do not
expose:

| Control | What it guarantees |
|---|---|
| Redaction | One process-wide `Redactor` knows every configured secret (raw, Base64, URL-encoded, JSON-string-escaped) plus the well-known patterns (`password=`, `Authorization:`, `JSESSIONID`, `Bearer`) and filters **every** stream that leaves the process: terminal text, `--json`, the JSON log, vendor tool output and support bundles. `config show` and the bundle's `config-redacted.yaml` print references (`env:`, `file:`, `enc:`), never values. |
| Run lock | `runs.lock` in the home, held for the whole mutating run with the run id and pid inside; a second jrsctl process exits 9 and names the holder. A run that never reached a terminal state blocks every other mutating command (exit 8) until `runs recover` finishes it, so two processes can never interleave steps on the same installation. |
| Journal before effect | Every step transition is written to `state.db` (WAL, `synchronous=FULL`, append-only) before the step's effect is reported; a crash mid-step is recoverable because the interrupted step is known and every step is idempotent. |
| Plans are inert | `--plan` builds the plan and stops; a plan is stored 30 minutes, runs at most once, and is refused when its fingerprint (server identity, input artifact, target files, configuration) has changed. |
| Overrides are audited, never silent | `--allow-unsigned`, `--allow-unsupported`, `--db-backup-confirmed` and `--force` change behaviour but never the exit code, and each writes an audit row. |
| Retention never removes what rollback needs | Automatic and manual pruning (`runs prune`) skip every snapshot of an installed hotfix, a registered customization, the most recent successful upgrade and any run pending recovery; manual pruning takes the run lock and is audited (`runs.prune`). |
| Offline help is static | `jrsctl <command> --explain`, `jrsctl help` and `jrsctl docs` print text fixed at build time and exit. They open no `Bootstrap`: they read neither `config.yaml`, `secrets.enc`, the key ring, the state store nor the server, so they expose no value from the operator's environment, need no passphrase, and can be run by anyone who can execute the jar without learning anything about a particular installation. The embedded documents are the same Markdown files as in the repository, so a reader can diff them against the published version. |
| The home is private | A home jrsctl creates is `rwx------` on Linux and, on Windows, carries one owner entry inherited by everything below it with the parent's entries removed, so `state.db`, snapshots and logs are not readable by other local accounts. An existing home is left as the operator set it up; the operator guide gives the `icacls` command to restrict it. |
| Vendor tools get no jrsctl secrets | `js-export`, `js-import` and `js-ant` start without any inherited `JRSCTL_*` variable (`JRSCTL_PASSPHRASE` among them) and without the variables configured `env:` secret references name (ADR-0019). A secret kept in another environment variable is still inherited, which is one more reason to prefer `enc:` references. |
| Credentials stay out of URLs and strings where the server allows | Token mode can send the pre-authentication token as the `pp` header (`server.auth.tokenLocation: header`, ADR-0018) instead of a URL parameter that proxies and access logs record. The form login body is encoded from the password's `char[]` and zeroed after sending. The capability probe sends no login without credentials to any server the compat matrix lists. |
| Residual: `--storepass` on the vendor command line | `import --source-keystore` passes the source keystore password to `js-import` as `--storepass`, the only form the vendor tool accepts. jrsctl redacts it from its own output, but other local accounts can read it in the process list while the import runs (ADR-0020). Run such an import from a session no other user can inspect. |
| Least privilege | Nothing in the jar needs administrator rights except what the operation itself needs (stopping the service, writing under the installation); `init`, `doctor`, `smoke`, `selfcheck`, `docs`, `--explain` and every `list` run as any user who can read the installation. |
