# ops

How to run, test and ship what exists today. Unlike a spec, which is a piece of work with an end, a document here describes the present and is updated in place when the facts change.

What belongs here, when it is written down:

- running the desktop app and the web app while working on them, and the tailnet front door
- testing without touching anyone's sessions: scratch sessions, the headless browser driver, the hidden workspace for checks that need a real window
- installing and packaging

Conventions:

- Written from commands that were actually run.
- History lives in git; a document here is in the present tense.

| Document | Purpose |
|----------|---------|
| [subagents.md](subagents.md) | Running workers on Tono: how to start one, what a fresh worktree costs, what workers share and how to keep them apart, and which backlog entries can run side by side |
