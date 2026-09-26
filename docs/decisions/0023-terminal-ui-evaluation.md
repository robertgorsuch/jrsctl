# ADR-0023: a full-screen terminal UI is not built for now

Status: accepted, 2026-09-26 (proposed 2026-09-16): option A, no full-screen terminal UI. Issue #75 closed. See the amendment of 2026-09-26 for the reasons that apply to 2.x; the context below describes 1.x and is kept for the record.

## Context

The field tester used jrsctl only through the CLI on a Linux VM with no desktop and asked whether a console-based GUI could be considered alongside the browser console. Since then, two changes cover much of that need without a full-screen UI:

- **SSH tunnel (#64).** The web console works from any machine through `ssh -L 7420:127.0.0.1:7420`, and `jrsctl console` prints that command on a server without a display.
- **Guided mode (#71).** `jrsctl` with no command opens a menu that asks for each job's inputs and runs the ordinary command. It works over SSH, needs no library, and leaves scripts unaffected.

A full-screen terminal UI (a dashboard, live run progress, run history, recovery) would add what the menu does not: a live view of a run and of the server that updates without re-typing commands.

## Options evaluated

| Option | What it takes | Concerns |
|---|---|---|
| **A. No full-screen UI** (guided mode plus SSH tunnel to the web console) | Nothing further | No live terminal dashboard; progress is the CLI's line-by-line output |
| **B. JLine 3** (line editing, terminal control, key bindings) | A new dependency (ADR, spec §13.3); screens built on top | Needs a native console provider on Windows. JNA is not approved (spec §13.3), so a JNI or FFM provider would have to work inside the jlink image; licence to be confirmed as BSD-style; the screens themselves are still hand-written |
| **C. Lanterna 3** (full-screen text UI toolkit with widgets) | A new dependency (ADR, spec §13.3) | Licence to be confirmed (reported as LGPL-3.0; compatibility with GPL-3.0-only to be checked); Windows console support to be verified inside the jlink image, since its fallback terminal uses Swing (`java.desktop`), which the image may not include (ADR-0008); a larger API surface to keep reviewed |
| **D. Hand-written ANSI screens** | No dependency | Raw input handling (key reading without echo, resize) is not possible on Windows from pure Java without a native console API; a partial implementation would behave differently per platform |

Every option other than A must also:
- work in the jlink image on Windows (cmd.exe, PowerShell, Windows Terminal) and on Linux over SSH;
- degrade to `--ascii`;
- keep the redaction rules on every line drawn;
- drive runs through the same `RunService` as the CLI and the web console.

## Recommendation

Option A for 1.6.0. The reported need (a usable interface on a server without a desktop) is met by #64 and #71, at no dependency cost and with the same behaviour on both operating systems.

If testers of 1.6.0 still ask for a live terminal dashboard, build it with option B:
- first prove, in a spike, a JNI or FFM console provider that works in the jlink image on Windows without JNA;
- confirm the licence;
- then write the dependency ADR and scope a first version to a dashboard and live run progress, leaving configuration to guided mode.

## Consequences

- #75 stays open until the maintainers accept option A (close the issue) or ask for the option B spike.
- No dependency is added and the jlink image is unchanged.

## Amendment, 2026-09-22 (#102)

A 1.6.0 tester asked again, meeting the trigger above, but only for line editing and path completion —
not the full-screen dashboard this ADR evaluates. That narrower spike and its result are ADR-0037:
JLine 3 (`jline-terminal-jni`, no JNA) adopted for `Prompter.path` only. This ADR's recommendation
(option A, no full-screen UI) is otherwise unchanged; #75 stays open.

## Amendment, 2026-09-26: accepted for 2.x (#75 closed)

The context above no longer holds: 2.0.0 removed the web console (ADR-0038), so the SSH tunnel it relied on is gone, and jrsctl is a terminal tool by design. The decision stands for different reasons.

- **The trigger never fired.** #75 asked for this to be settled by the tester who raised it, or the next field test. The same support engineer ran two more (v1.6.0 on 2026-09-18, v2.0.0 on 2026-09-25; recorded under #36). Every interactive request was for the terminal jrsctl already has, and each is done: tab completion and cursor movement at prompts (ADR-0037), a clearer guided menu (field test 2 G1, G5 to G7; v2.1.0), a pager for long output (#187). Neither review asked for a dashboard, and the v2.1.0 list of open findings names none.
- **What a dashboard would show is already there:** live progress while a run executes, `doctor` for health, `runs list` with filters and short run ids (#186) for history, `runs recover` for recovery, and guided mode for everything interactive.
- **It would be a second front end.** Spec §3 keeps one engine and one front end (the CLI and guided mode), which ADR-0038 reinforced. A full-screen UI would need Lanterna (licence and Windows console support unresolved) or substantial JLine screen work, tested in the jlink image on Windows and Linux, over SSH and with `--ascii`.

**What would reopen it:** a field test or customer asking for a live, full-screen view that the commands above do not give. The first step would then be JLine screens on the shared JNI terminal (`app.SystemTerminal`, ADR-0037), which settles option B's Windows provider concern without a new dependency; a new ADR would scope it.
