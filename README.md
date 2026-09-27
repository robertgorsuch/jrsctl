# jrsctl

**The safe way to look after JasperReports Server.** From  Jaspersoft.

jrsctl checks your server's health, installs hotfixes, backs up and moves report content between servers, and upgrades the server to a new version. Before it changes anything it shows you exactly what it will do and asks you to confirm. If something fails partway, it puts things back the way they were.

It comes as one download with everything it needs inside. There is nothing else to install, and it works on servers with no internet access.

---

## What you can do with it

| You want to… | Command |
|---|---|
| Check the server is healthy | `jrsctl doctor` |
| Back up reports and folders | `jrsctl export` |
| Copy content to another server | `jrsctl import` |
| Install a hotfix, or take one out | `jrsctl hotfix apply` / `jrsctl hotfix rollback` |
| Upgrade to a new version | `jrsctl upgrade` |
| Get a support bundle for a ticket | `jrsctl runs support-bundle <id>` |

---

## Before you start

- **Run jrsctl on the JasperReports Server machine itself** for health checks, hotfixes and upgrades. Backing up and copying content (`export`, `import`) also works from another machine: set it up there with `jrsctl init --remote https://your-server/jasperserver-pro`.
- **Use an account that is allowed to stop and start the server.** On Windows, open **Command Prompt** with **Run as administrator**. On Linux, use the account that runs the server, or `root`.
- **Have the JasperReports Server admin password ready** (`superuser` on the Commercial edition, `jasperadmin` on the Community edition; `init` proposes the right one).
- **No Java to install.** The download includes its own Java, used only by jrsctl. Nothing is installed on the machine, and jrsctl does not change the Java that JasperReports Server uses. For the server's own scripts (buildomatic) jrsctl uses the Java that came with your server, or the one you set as `vendor.javaHome`: Java 8 for 7.x, 11 for 8.x, 17 for 9.x and 10.x.
- **Supported:** JasperReports Server 7.1 to 10.x, Community and Commercial editions, on Apache Tomcat, on Windows or Linux (64-bit). JBoss EAP, WildFly and servers in containers are not supported: `init` says so and stops (backing up and copying content with `init --remote` still works against them).

---

## Quick start

### Step 1: Download and unpack

Download the archive for your system from the [releases page](https://github.com/robertgorsuch/jrsctl/releases):

- Windows: `jrsctl-<version>-windows-x64.zip`
- Linux: `jrsctl-<version>-linux-x64.tar.gz`

Unpack it on the server, then open a terminal in the unpacked folder:

```bash
:: Windows (Command Prompt, run as administrator)
tar -xf jrsctl-2.2.0-windows-x64.zip -C C:\Jaspersoft
cd C:\Jaspersoft\jrsctl-2.2.0
```

```bash
# Linux
tar -xzf jrsctl-2.2.0-linux-x64.tar.gz -C /opt
cd /opt/jrsctl-2.2.0
```

> **How to type the commands.** This guide writes every command as `jrsctl …`.
> On **Windows** type `bin\jrsctl.cmd …` and on **Linux** type `bin/jrsctl …`.
> For example, `jrsctl doctor` becomes `bin\jrsctl.cmd doctor` on Windows.
> For scheduled or scripted runs on Windows, use `powershell -NoProfile -ExecutionPolicy Bypass -File bin\jrsctl.ps1 …` instead: pressing Ctrl-C during a batch-file run makes Windows ask a question that stops the script from finishing.

### Step 2: Check the tool itself

```bash
jrsctl selfcheck
```

Every check should pass. This step doesn't touch your server. If one fails, the line names the problem; the download may be damaged, so download it again.

### Step 3: Connect jrsctl to your server

Tell jrsctl where JasperReports Server is installed. It finds the rest (ports, service, database) on its own and shows you what it found.

```bat
:: Windows
jrsctl init --install-dir "C:\Jaspersoft\jasperreports-server-pro-9.0.0"
```

```bash
# Linux
jrsctl init --install-dir /opt/jasperreports-server-pro-9.0.0
```

Then it asks you three things:

1. **Change any of these values?** Press Enter if they look right. Type `y` to go through them one by one: press Enter to keep a value, or type a new one.
2. **Store the passwords encrypted on this machine?** Press Enter (yes), then type the admin password and the database password. Nothing shows on screen as you type. The first time, you also choose a passphrase for the encrypted store. jrsctl asks for it when it needs a password, so keep it safe.
3. **Write config?** Type `y`.

Passwords are never saved in the settings file itself.

> Scheduled or scripted runs can't answer prompts. Set `JRSCTL_PASSPHRASE` for them, or skip the encrypted store and set `JRS_PASSWORD` and `JRS_DB_PASSWORD` in the environment instead (`init` tells you which names to set).

### Step 4: Run a health check

```bash
jrsctl doctor
```

You get a list of checks marked **PASS**, **WARN** or **FAIL**. Every problem comes with a line starting `->` that tells you how to fix it. Fix any **FAIL** items and run `jrsctl doctor` again.

**Run `jrsctl doctor` before every change.** It only reads; it never changes anything.

### Step 5: Do the job

Every command that changes something works the same way:

1. **It shows you the plan:** every step, whether the server will be restarted, and what gets backed up.
2. **It asks** `Run this plan? [y/N]`. Type `y` to go ahead. Anything else stops, and nothing has changed.
3. **It runs the steps**, one line per step, then says what happened and what to do next.

Want to see the plan without being asked to run it? Add `--plan` to any command.

The examples below show the most common jobs.

---

## Common jobs

The examples use Windows paths. On Linux, use paths such as `/backups/samples.zip` instead.

### Back up your reports

Back up the whole repository (the server keeps running):

```bash
jrsctl export --out C:\Backups\repository-2026-09-15.zip
```

Back up just one folder, such as the sample reports:

```bash
jrsctl export --uri /public/Samples --out C:\Backups\samples.zip
```

Take a complete backup with users, roles and settings. The server keeps running; add `--stop-service` if you want it stopped while the backup is taken:

```bash
jrsctl export --full-server --out C:\Backups\full-server.zip
```

Each backup writes two files: the `.zip` and a small `.zip.jrsctl.json` beside it. **Keep them together.**

### Copy content to another server

On the second server, after completing Steps 1 to 4 there, copy both files across and run:

```bash
jrsctl import C:\Backups\samples.zip --update
```

`--update` replaces reports that already exist on this server. Leave it out to keep existing ones untouched. jrsctl takes a backup of this server's copy first, and restores it if the import fails.

> If you see `keystore fingerprint mismatch`, the two servers use different encryption keys. Run `jrsctl import --explain` to see how to bring the other server's key along.

### Install a hotfix

Hotfixes from Jaspersoft support come as a `.zip` package (`hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip`). Install it as downloaded. jrsctl prints the package's SHA-256 and asks you to confirm it matches the checksum on the support portal, shows what it will replace, add and delete, backs up every file it touches, and stops and restarts the server if the hotfix needs it:

```bash
jrsctl hotfix apply C:\Downloads\hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip
```

Unattended, pass `--allow-unsigned` after checking the checksum yourself. The readme's manual steps, such as SQL for particular databases, are printed as warnings; jrsctl runs none of them.

A hotfix bundle built with jrsctl (`JRS-9.0.0-HF-0002.zip`) installs the same way; `jrsctl hotfix verify <file>` checks either kind without changing anything.

See what is installed:

```bash
jrsctl hotfix list
```

### Take a hotfix out

```bash
jrsctl hotfix rollback JRS-9.0.0-HF-0002
```

The original files come back exactly as they were before the hotfix.

### Upgrade to a new version

Upgrades are the biggest change jrsctl makes. Please read this first:

- **What happens to the database.** In the default `newdb` mode jrsctl exports the whole repository first, the new version rebuilds the database from that export, and `jrsctl upgrade rollback <runId> --to-point B --restore-database` rebuilds the old database from the same export if you need to go back. In `samedb` mode the schema is changed in place and no export undoes that, so back up the database yourself first; the command asks you to confirm with `--db-backup-confirmed`.
- Download and unpack the new JasperReports Server version on the server first.
- Upgrades go one supported step at a time. For example, from 7.x you go to 8.x first, then to 10.x. jrsctl tells you if a step isn't supported, and stops before changing anything.

Look at the plan first, then rehearse: `--test` runs the vendor's own checks of the settings, the database connection and the package, and changes nothing.

```bash
jrsctl upgrade --to 10.0.0 --package C:\Downloads\jasperreports-server-pro-10.0.0-bin --plan
jrsctl upgrade --to 10.0.0 --package C:\Downloads\jasperreports-server-pro-10.0.0-bin --test
```

When you're happy with it, run the same command without `--plan`. At the end jrsctl tests the upgraded server. If that test fails, it offers to put the old version back:

```bash
jrsctl upgrade rollback <run id> --to-point B
```

Run `jrsctl upgrade --explain` for the full details, including the Java version the new release needs.

---

## If something goes wrong

jrsctl finishes every job by saying what happened and what to do next. The number it ends with tells you the result:

| Result | What it means | What to do |
|:---:|---|---|
| **0** | Done | Nothing |
| **2** | A check failed before anything changed | Read the message, fix it, run the command again |
| **3** | A step failed, and jrsctl put everything back | Read the message, fix the cause, run it again |
| **4** | A step failed, and jrsctl could not put everything back | Follow the "next action" in the message. `jrsctl docs recovery-runbook` walks you through it |
| **5** | You cancelled it, and jrsctl put everything back | Nothing |
| **6** | This server version, this upgrade step, or this kind of installation (JBoss, WildFly, a container) isn't supported | Nothing changed. Check what is supported under [Before you start](#before-you-start) |
| **7** | The hotfix isn't signed by a publisher jrsctl trusts, or the file was altered | Don't install it. Get a genuine copy from Jaspersoft |
| **8** | An earlier job was interrupted (a crash or power cut) | Run the `jrsctl runs recover …` command it prints, to finish or undo that job |
| **9** | Another jrsctl job is already running | Wait for it to finish |

Other useful commands:

```bash
jrsctl runs list                    # every job that has run, and how it ended
jrsctl runs list --status failed    # only the ones that failed (also --operation hotfix, --since 7d)
jrsctl runs show <run id>           # the step-by-step detail of one job; the last few characters of the id are enough
```

---

## Getting help

Not sure which command you need? Type `jrsctl` on its own: a menu walks you through the common jobs and shows the command it runs for each.

Everything is built in and works without internet access:

```bash
jrsctl help                       # list all commands
jrsctl hotfix apply --explain     # what a command does, what it changes and how it undoes it
jrsctl docs operator-guide        # the full operator guide, a screen at a time (Space, Enter, q)
jrsctl docs recovery-runbook      # what to do after any failure
```

The same documents are in [`docs/`](docs/): [operator guide](docs/operator-guide.md), [recovery runbook](docs/recovery-runbook.md), [security notes](docs/security.md), [hotfix authoring](docs/hotfix-authoring.md).

---

## Good to know

### Keep passwords encrypted

Instead of setting `JRS_PASSWORD` each time, store the password in jrsctl's encrypted store. The store can only be opened on this machine, with a passphrase you choose:

```bash
jrsctl secrets init
jrsctl secrets set JRS_PASSWORD
jrsctl secrets set JRS_DB_PASSWORD
```

Each command asks for the value without showing it on screen. Then open `config.yaml` (see below) and change the two password lines from `env:` to `enc:`:

```yaml
passwordRef: enc:JRS_PASSWORD      # under server.auth (was env:JRS_PASSWORD)
passwordRef: enc:JRS_DB_PASSWORD   # under database (was env:JRS_DB_PASSWORD)
```

jrsctl asks for the passphrase when it needs to open the store.

### Where jrsctl keeps its files

Settings, history and backups are kept in one folder, the **jrsctl home**:

- Windows: `C:\ProgramData\jrsctl`
- Linux: `/var/lib/jrsctl` (or `~/.jrsctl` if that isn't writable)

The main files are `config.yaml` (the settings `init` wrote), `snapshots\` (backups taken before each change) and `logs\jrsctl.log`. Passwords never appear in any of them. Back this folder up along with the server.

`jrsctl home show` says where the home is, how much room its volume has and what uses it. If it sits on a small volume, `jrsctl home set <dir>` moves it to a bigger one for everyone who uses this machine's jrsctl (`jrsctl home reset` undoes it); `--home <dir>` or the `JRSCTL_HOME` variable choose a home for one command or one shell.

### Checking your download

Each release file has a `.sha256` checksum beside it:

- Windows: `certutil -hashfile jrsctl-2.2.0-windows-x64.zip SHA256`
- Linux: `sha256sum -c jrsctl-2.2.0-linux-x64.tar.gz.sha256`

Releases are also signed by the Jaspersoft publisher key. See [`docs/security.md`](docs/security.md).

---

## For developers

You do not need to build jrsctl to use it: download the release archive above. Building from source (it needs JDK 21), the project rules and how to contribute are in [`CONTRIBUTING.md`](CONTRIBUTING.md). The design is in [`docs/spec.md`](docs/spec.md).

## Licence

Copyright (c) 2026 Actian Corporation. jrsctl is free software, licensed under the GNU General Public License, version 3 only (SPDX `GPL-3.0-only`). See [`LICENSE`](LICENSE) and [ADR-0010](docs/decisions/0010-gpl-3-licence.md). The libraries bundled in the download keep their own licences, listed in `LICENSE-THIRD-PARTY.txt` inside every archive.
