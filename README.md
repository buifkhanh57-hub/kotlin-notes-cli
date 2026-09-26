# notably

**A professional note-taking workspace for the terminal — pure Kotlin, JVM stdlib only.**

![language](https://img.shields.io/badge/language-Kotlin-7F52FF)
![platform](https://img.shields.io/badge/platform-JVM%2017-4c8cbf)
![deps](https://img.shields.io/badge/dependencies-zero-brightgreen)
![license](https://img.shields.io/badge/license-MIT-blue)
![tests](https://img.shields.io/badge/tests-main%28%29%20harness-orange)
![version](https://img.shields.io/badge/version-1.0.0-success)

---

## Overview

`notably` is a single-binary command-line notebook. It keeps an index of
markdown notes in `~/.notably`, organizes them into notebooks and tags, and
gives you scored full-text search (plus a regex mode), per-note due-date
reminders, rich workspace statistics with sparklines and a day heatmap,
portable exports and an honest at-rest encryption lock.

Everything is built on the Kotlin standard library and the JDK alone:
the JSON parser/writer, the atomic-file store, the PBKDF2/AES-GCM crypto
envelope, the ANSI renderer and the markdown subset renderer are all
implemented in-tree. There are **zero third-party dependencies**.

Design goals, in order:

1. **Never lose a note.** Every write is temp-file + `fsync` + atomic rename,
   with rolling backups and transparent backup recovery on read.
2. **Never trap your data.** Notes live in plain JSON you can read with any
   editor; exports are standard markdown/CSV/JSON.
3. **Behave like a good Unix citizen.** Stable exit codes, `--no-color`
   support, machine-readable output formats, graceful degradation without a
   TTY.

## Features

- **Notes** — create/edit/show/trash/restore/purge with markdown bodies
  (fenced code, headings, lists, task boxes, quotes, links), id-prefix
  addressing, unique-prefix and title-substring resolution with
  "did you mean" suggestions.
- **Organization** — notebooks (create/move/rename-with-cascade/remove),
  normalized tags with auto-assigned stable accent colors, pinning,
  archiving, and a soft-delete trash with age-based purge.
- **Search** — field-weighted scoring (title 5.0 / tags 3.0 / notebook 1.5 /
  body 1.0, whole-word hits score double), phrase and exact-title bonuses,
  recency boost, highlighted snippets — plus `--regex` mode with per-note
  match counts.
- **Reminders** — attach one due date per note using human expressions
  (`tomorrow`, `+3d`, `friday`, `2026-06-01`), snooze from *now*, complete,
  reopen, and clear completed history.
- **Statistics** — per-notebook tables, tag cloud bars, monthly activity
  chart, an 8-level activity **sparkline**, current/longest writing
  **streaks**, an optional contribution-style **day heatmap**
  (`stats --heat [weeks]`), and reminder counters.
- **Export** — markdown bundle (front-mattered per-note files + index.md),
  single markdown document, RFC-4180 CSV, or the raw JSON array schema.
- **Encryption** — `notably lock` encrypts the note index with
  PBKDF2-HMAC-SHA256 (120k iterations) into AES-GCM when the JVM provides
  256-bit AES, with a clearly documented fallback and honest limitations.
- **Robust storage** — crash-safe atomic writes, timestamped rolling backups,
  automatic recovery from backups, corruption errors with line/column
  positions, and a max-depth guard against hostile JSON.
- **Terminal UX** — ANSI colors with `NO_COLOR`/`--no-color`/`--color`
  handling, visible-width-correct table alignment, relative timestamps,
  typo suggestions via edit distance, and stable per-failure exit codes
  (0/1/2/3/4/5/6/7).

## Requirements

| Requirement | Version |
|-------------|---------|
| JDK         | 17+ (toolchain is pinned in `build.gradle.kts`) |
| Gradle      | any wrapper-capable 7.6+ (only for building) |
| Kotlin      | 1.9.24 (fetched by the Gradle Kotlin plugin) |
| Terminal    | any; UTF-8 recommended for block glyphs |

Runtime dependencies: **none**. `javax.crypto`/`java.security` are part of
the JVM, not third-party artifacts.

## Installation

Build from source:

```sh
git clone <this repository> && cd notably
gradle jar            # produces build/libs/notably.jar (fat jar incl. stdlib)
```

Run it:

```sh
java -jar build/libs/notably.jar --help
```

Optional: install on your `PATH`:

```sh
sudo install -m 0755 build/libs/notably.jar /usr/local/bin/notably.jar
printf '#!/bin/sh\nexec java -jar /usr/local/bin/notably.jar "$@"\n' \
  | sudo tee /usr/local/bin/notably && sudo chmod +x /usr/local/bin/notably
```

## Quick Start

```sh
$ notably init
✓ workspace initialized at /home/you/.notably

  /home/you/.notably
  ├── notes.json          note index
  ├── notebooks.json      notebook registry
  ├── meta.json           schema version + tag colors
  ├── config.json         preferences (editor, date format, ...)
  ├── reminders.json      due-date reminders
  ├── backups/            rolling backups of every file
  └── exports/            default export destination

Try your first note:
  notably add "Welcome to notably" --tag meta

$ notably add "Kotlin coroutines" --notebook Work --tag kotlin,deep-dive --pin --body "Structured concurrency notes."
✓ created note k3x9q2v8n1p4 in 'Work'
  tags: [deep-dive] [kotlin]
  pinned — floats to the top of listings
  show it with: notably show k3x9q2v8n1p4

$ notably remind add k3x9 --due tomorrow --priority 3
✓ reminder set for 'Kotlin coroutines' (k3x9q2v8n1p4) — due tomorrow
  2026-03-12T23:59:00Z · priority 3 (urgent)
  review with: notably remind list
```

## Usage

```
notably [--workspace DIR] [--no-color|--color] <command> [arguments]
```

### Commands

| Command | Purpose |
|---------|---------|
| `init [--notebook NAME]` | create a workspace (default `~/.notably`, or `$NOTABLY_HOME`, or `--workspace`) |
| `add <title>` | create a note (`--body`, `--file`, editor, or inline stdin) |
| `show <id> [--raw]` | render (or print verbatim) one note |
| `edit <id> [--title S] [--body]` | change title and/or body |
| `rm <id> [--hard] [-y]` | move to trash, or purge irreversibly |
| `list [filters]` | filterable table; `-q "query"` switches to scored results |
| `search <query> [--regex]` | scored full-text search, or regex match counting |
| `tag add\|rm\|list` | manage tags; `list` shows usage bars |
| `notebook create\|list\|move\|rename\|describe\|rm` | manage containers |
| `pin` / `unpin <id>...` | keep notes on top of listings |
| `archive` / `unarchive <id>...` | tuck notes away without trashing |
| `trash <id>...` / `trash --empty [--days N]` | soft delete / purge trash |
| `restore <id>...` | bring trashed notes back |
| `export [--format bundle\|md\|csv\|json]` | portable copies (`--out`, `--force`) |
| `remind add\|list\|rm\|done\|reopen\|snooze\|clear-done` | due dates attached to notes |
| `stats [--months N] [--heat [WEEKS]]` | workspace insight report |
| `lock lock\|unlock\|status\|explain` | at-rest encryption for notes.json |
| `help [command]` | overview or per-command help |
| `version` | print version information |

Exit codes: `0` ok · `1` unexpected error · `2` usage error · `3` not found ·
`4` validation · `5` locked · `6` I/O · `7` crypto failure.

### Realistic output

```sh
$ notably list --notebook Work
Notes in 'Work'
ID           Flags  Title                Tags            Notebook  Updated
------------  -----  -------------------  --------------  --------  --------
k3x9q2v8n1p4  ★     Kotlin coroutines    [deep-dive] [kotlin]  Work  2h ago
a7b2m9z4k1e8        Sorting cheat sheet  [algorithms]    Work      3d ago
2 of 12 note(s) shown

$ notably search "kotlin coroutines"
Search results for 'kotlin coroutines'
 18.5  k3x9q2v8n1p4 ★Kotlin coroutines
      …Structured concurrency with ▮coroutines▮ on the JVM…
1 match(es), best score  18.5

$ notably search "\bkotlin\w*\b" --regex
Search results for '\bkotlin\w*\b'
  4.0  k3x9q2v8n1p4  Kotlin coroutines · 1 match(es)
  1.0  a7b2m9z4k1e8  Why we chose Kotlin · 1 match(es)
2 note(s) matched /\bkotlin\w*\b/
```

(`▮…▮` marks the bold/yellow snippet highlight; without colors the markers
are stripped.)

```sh
$ notably remind list
Reminders (2)
Note          Title                          Due           Priority    Status
------------  -----------------------------  ------------  ----------  ---------
k3x9q2v8n1p4  Kotlin coroutines              today         urgent      due soon
b4n7t1r6w2q9  Water the plants               2026-04-01    low         scheduled
2 pending, 0 overdue

$ notably stats
Workspace statistics
────────────────────────────────────────────
Notebooks
Name      Total  Active  Archived  Trashed  Pinned
--------  -----  ------  --------  -------  -------
Inbox         3       3         0        0        0
Work          9       8         1        0        1

Top tags
  kotlin               ████████████████████▌   9
  jvm                  ████████████            5
  ...

Notes created — last 12 month(s)
  2025-04  ██                       2
  2025-05  █▌                       1
  ...

Activity sparkline
  ▁▂▃▁▂▅▃▆▄▇▅█
  2025-04 → 2026-03
streak       current 4d · longest 11d

Totals
notes        12 (11 active, 1 archived, 0 trashed)
pinned       1
tags         6
words        4318
reminders    2 pending (0 overdue, 5 done)
busiest      2026-02 (4 notes)
oldest       'Meeting template' from 1y ago
```

### Reminders: due-date grammar

| Expression | Meaning |
|------------|---------|
| `now` | exactly now |
| `today` | today at 23:59 local time |
| `tomorrow` | tomorrow at 23:59 local time |
| `next week` | 7 days from today, 23:59 |
| `next month` | same day next month, 23:59 |
| `mon` … `sunday` | next occurrence of that weekday, 23:59 |
| `+30`, `+30m` | 30 minutes from now |
| `+6h` / `+3d` / `+2w` | hours / days / weeks from now (clock time kept) |
| `2026-03-01` | that date at 23:59 local time |
| `2026-03-01T09:30` | that local date-time |
| `2026-03-01 09:30` | same, with a space instead of `T` |

Date-only forms intentionally land at the **end of the day** so a due date
never means "already overdue at breakfast". Snoozing always counts from
*now*, not from the old due date, so repeated snoozes stay predictable.

## Encryption notes

`notably lock lock` replaces `notes.json` with a self-describing JSON
envelope (`notes.json.enc`): a fresh 16-byte salt per lock, PBKDF2-HMAC-SHA256
(120,000 iterations → 256-bit key), then **AES-GCM** (`AES/GCM/NoPadding`,
128-bit auth tag) when the JVM offers 256-bit AES, otherwise a SHA-256
keystream XOR fallback. A 16-byte passphrase verifier (constant-time
compared) rejects wrong passphrases cheaply.

**Honest limitations — read before trusting it with anything serious:**

- **PBKDF2 is not memory-hard.** GPUs/ASICs brute-force it far faster than
  Argon2id would allow; Argon2 is not available in the JVM standard library.
- **The XOR fallback is home-made.** No integrity, ciphertexts are
  malleable, and it is not a vetted construction. Treat the AES-GCM path as
  the only real encryption mode (`lock explain` prints which cipher is in
  use; `--force-xor` exists for reproducible tests).
- **A passphrase verifier is stored on disk**, enabling unlimited offline
  guessing of weak passphrases. Use a long, unique passphrase.
- **No rekeying / KDF rotation.** Re-lock to change the passphrase.
- **Locking protects `notes.json` at rest only.** Exports you made, editor
  temp files and (until `lock` removes them) prior rotated backups can hold
  plaintext copies. Locking deletes the plaintext index *and* its backups.
- **In-memory plaintext** exists while the CLI runs; JVM swap and core
  dumps are out of scope.

## Project Structure

```
notably/
├── build.gradle.kts                    66  build script: fat jar, selftest task
├── settings.gradle.kts                  3  root project name
├── README.md
└── src/
    ├── main/kotlin/notably/
    │   ├── Main.kt                    365  arg parsing, dispatch, help, edit-distance suggestions
    │   ├── commands/
    │   │   ├── NoteCommands.kt        432  CliArgs scanner + add/show/edit/rm/list/search
    │   │   ├── OrgCommands.kt         269  tag/notebook/pin/archive/trash/restore batch ops
    │   │   ├── RemindCommands.kt      218  remind add/list/rm/done/reopen/snooze/clear-done
    │   │   └── UtilCommands.kt        355  init/export/stats/lock commands
    │   ├── model/
    │   │   ├── Models.kt              310  About, exit codes, exception hierarchy, filters, sorts
    │   │   ├── Note.kt                226  the note entity: validation, JSON, slug
    │   │   └── Notebook.kt            144  notebook entity: normalization, JSON
    │   ├── service/
    │   │   ├── NoteService.kt         487  note CRUD + lifecycle, NotebookService registry
    │   │   ├── RemindService.kt       472  Reminder entity + due-date grammar + lifecycle
    │   │   ├── SearchService.kt       307  scored search, regex mode, snippets
    │   │   ├── ExportService.kt       248  bundle / md / csv / json exporters
    │   │   └── CryptoService.kt       347  PBKDF2 + AES-GCM/XOR envelope
    │   ├── storage/
    │   │   ├── Workspace.kt           429  layout, load/save round-trips, config + meta
    │   │   ├── AtomicFile.kt          182  temp + fsync + atomic rename + rolling backups
    │   │   └── Json.kt                532  JSON model, writer, recursive-descent parser
    │   └── ui/
    │       ├── Renderer.kt            317  Ansi + tables, bars, timestamps, highlight
    │       ├── Markdown.kt            219  markdown subset → ANSI, plain previews
    │       ├── Sparkline.kt           124  sparkline, day heatmap, streaks
    │       └── Prompt.kt              147  prompts, secrets, editor flow
    └── test/kotlin/notably/
        └── NotablyTest.kt             708  main()-based harness, ~120 checks, 14 suites
```

## Architecture

Four layers with strict one-directional imports:

```
commands  →  services  →  storage
    ↘            ↓          ↗
      ui (Ansi/Renderer/Markdown/Sparkline)
```

- **Main** parses global flags, builds one `AppContext` (workspace + all
  services + renderer/prompt), and dispatches. Exceptions map to exit codes
  via the exception hierarchy — commands never call `exitProcess` themselves.
- **Services** own the domain rules. `NoteService` holds the note index in
  memory and persists through `Workspace` after every mutation
  (single-user write-through). `RemindService` mirrors that pattern for
  `reminders.json`.
- **Storage** knows files, not domain rules: `Workspace` does JSON
  round-trips through `AtomicFile`, which guarantees crash safety
  (temp file + `fsync` + atomic rename) and rotates up to `N` backups per
  file. Reads transparently fall back to the newest readable backup.
- **UI** is side-effect-light and color-correct: cells are measured on
  *visible* width (ANSI excluded), markdown rendering takes an explicit
  `colors` flag so tests get escape-free output, and `Sparkline` never reads
  the clock — callers pass `today`, making visual output deterministic.

Key decisions:

1. **Immutable `Note`** — every mutation is a validated `copy(...)`, so
   invalid states (pinned without `pinnedAt`, trashed without `trashedAt`)
   cannot exist, even from hand-edited files.
2. **JSON kept in raw number form** — `1.50` round-trips exactly; no float
   drift in ids, versions or iteration counts.
3. **Storage is schema-agnostic** — `Workspace.loadReminderEntries` returns
   raw JSON objects; domain validation lives in the service layer.
4. **Scored search is transparent** — weights are public constants and the
   algorithm is documented in the KDoc so results are explainable.
5. **No third-party code anywhere** — parser, crypto, tables and charts are
   all readable, auditable, stdlib-only Kotlin.

## Testing

The test suite is a plain `main()`-based harness in
`src/test/kotlin/notably/NotablyTest.kt` — no JUnit, matching the
zero-dependency rule. It covers 14 suites and ~120 checks with hand-computed
expectations:

| Suite | Covers |
|-------|--------|
| json model / parser | round-trips, escapes, `deepEquals`, error line/column, depth guard, number grammar |
| model | Note/Notebook/Tag validation invariants, slugs, JSON round-trips |
| search / regex search | scoring weights, phrase bonus, snippets, match counting, invalid patterns |
| crypto | XOR + auto roundtrips, wrong passphrase, verifier, PBKDF2 determinism, tampered payload |
| atomic file | atomic writes, rotation, newest-first backup recovery |
| workspace | init, config round-trip, tag colors, id generation, reminder registry |
| note / notebook / remind services | CRUD, lifecycle invariants, ambiguity errors, due-date grammar, snooze semantics |
| sparkline / markdown / renderer | deterministic glyphs, streaks, heatmap alignment, previews, table alignment |

Run it with:

```sh
gradle selftest     # or: gradle check (the stock `test` task is intentionally disabled)
```

The harness prints one line per failure with its suite prefix and exits
non-zero if anything fails.

## FAQ

**Where is my data?** `~/.notably` by default; override with `NOTABLY_HOME`
or `--workspace DIR` (both expand `~`).

**Can I edit `notes.json` by hand?** Yes. The reader is lenient: unknown
keys are ignored, missing optional fields degrade to defaults, and only
truly broken entries (missing id/title) are rejected — with entry numbers.

**I forgot the passphrase.** There is no recovery by design. Delete
`notes.json.enc` (and restore from a pre-lock backup if you kept one) and
re-import from any export you made.

**How do ids work?** 12 lowercase base-36 chars. Any unique prefix works
(minimum 2 chars), and a unique title substring also resolves — ambiguous
references produce a listing of the candidates instead of a guess.

**Why does `search` ignore single-character words?** Tokens shorter than 2
characters are dropped to keep scoring meaningful; use `--regex` for exact
character-level matching.

**Does `stats --heat` use updated notes too?** No — the heatmap and streaks
count **creation** days of non-trashed notes.

**Is the trash versioned?** Trashed notes stay in `notes.json` with a
`trashedAt` stamp until `trash --empty` (or `--days N` aging) purges them;
rolling backups in `backups/` additionally cover the whole index.

**Windows support?** Colors, tables and logic are platform-neutral; block
glyphs need a UTF-8 terminal (Windows Terminal works). Atomic rename falls
back gracefully where `ATOMIC_MOVE` is unavailable.

## Roadmap

- [ ] `notably import` for markdown folders and CSV round-trips
- [ ] `--json` output mode for `list`/`search`/`remind list`
- [ ] Git-backed workspace sync (`commit`/`push` helpers)
- [ ] Attachment notes (file references with hash manifest)
- [ ] Recurring reminders (`every monday`)
- [ ] `notably doctor` — workspace integrity audit command
- [ ] Shell completions (bash/zsh/fish)
- [ ] Optional Argon2id via an optional plugin, keeping the core dependency-free

## License

MIT License — see the header of this repository. Copyright (c) 2026.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions: the above copyright
notice and this permission notice shall be included in all copies or
substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS",
WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

---
**by Bui Bao Khanh**
