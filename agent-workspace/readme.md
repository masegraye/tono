# agent-workspace

Working memory for the people and agents working on Tono. This is where we keep track of where Tono is going, the work that gets it there, and the decisions made on the way.

## Structure

| Directory | Short code | Purpose |
|-----------|-----------|---------|
| `big-ideas/` | `big-idea` | Where Tono is going: high-level, directional documents |
| `product/` | `product` | What Tono is and who it is for |
| `design/` | `design` | How Tono should look and behave, screen by screen, before a spec says how to build it |
| `architecture/` | `arch` | How the running system is put together, grounded in the code |
| `backlog/` | `backlog` | Things Tono could do and doesn't yet, formed enough to build later |
| `investigations/` | `inv` | Audits and analyses that change no code, feeding the backlog, design and specs |
| `specs/` | `spec` | Bodies of work: concrete, actionable specifications |
| `ops/` | `ops` | How to run, test and ship what exists today, updated in place |
| `decisions/` | `adr` | Decisions and their reasons, one per file |
| `handoffs/` | `handoff` | Where a session left off, so the next one can pick up |

Only `backlog/`, `investigations/` and `ops/` have documents so far. The rest have a readme that says what belongs there.

## Naming convention

Every file follows the same pattern, all lowercase, kebab-case:

```
<short-code>-<nnn>-<slug>.md
```

- `<short-code>`: `big-idea`, `product`, `design`, `arch`, `backlog`, `inv`, `spec` or `adr` (matches the directory)
- `<nnn>`: a zero-padded number, unique within its directory (`001`, `002`, ...)
- `<slug>`: a short kebab-case description of the contents

Handoffs are named by date instead: `handoff-<yyyy-mm-dd>-<slug>.md`.

Examples:

```
backlog/backlog-001-program-copies-reach-the-clipboard.md
investigations/inv-001-rex-rune-feature-audit.md
specs/spec-001-example/spec.md
```

## Workflow

1. An idea, or a gap found by an investigation, becomes a **backlog** entry.
2. When it is next to be built and is more than a small change, it is promoted to a **spec**: `specs/spec-NNN-<slug>/spec.md`. Small entries can be built straight from the backlog.
3. A choice that later work will rest on is written down as a **decision** before anything treats it as settled.
4. Documents carry a `Status` field so their state is visible over time. Specs use `draft`, `in-review`, `accepted`, `in-progress`, `done`, `superseded`. The backlog uses `captured`, `promoted`, `done`, `declined`.
5. Cross-reference by short code ("informed by inv-001", "promoted from backlog-002").

## Rules for agents

Standing instructions from Mase, gathered from earlier sessions:

- **Rex for Mac is the reference.** Action names, shortcuts and behaviour follow it, with Control or Control+Shift where the Mac has Command. Rex Rune is the second reference. Read the Rex documentation before guessing how the server behaves.
- **Never disturb Mase's sessions or his other clients.** Test only in a throwaway session named `kt-*`, made with `rex session new` and removed with `rex session kill`. A focused client that shows someone's terminal takes over its size.
- **Go for the outcome that was wanted**, not the literal wording of the request.
- **Renaming to Tono covers what users see.** File, class and package names, the directory, and the configuration path stay as they are.

Practices that came out of mistakes made while testing:

- **Fold every other session away in a test's workspace**, so that a key meaning "tab 1" or "next tab" stays in the scratch session. A test that sent Control+1 once went to a real tab and acted there.
- **Headless checks first.** A check that needs a real, focused window is announced before it runs, kept short, and cleans up after itself.
- **A key reaching the page is not the same as the browser leaving it alone.** A test of keys in a browser checks both.
- **Say what was looked at.** A report on a change to how something looks says whether the desktop app, the web app, or both were seen.

For the documents here:

- Never renumber or rename existing documents; numbers are permanent.
- New documents take the next free number in their directory.
- Update the `Status` field as work progresses rather than deleting documents.
- A superseded document stays in place, marked `superseded`, with a pointer to its replacement.
