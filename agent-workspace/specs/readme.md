# specs

Bodies of work: concrete, actionable specifications.

**Layout: `specs/` holds only directories, one per spec, and this readme.** Each spec is a directory `spec-<nnn>-<slug>/`. The main document is `spec.md`.

```
specs/
  readme.md
  spec-001-example/
    spec.md        # what is being built, for whom, what is decided, how we know it is done
    designs/       # screens and behaviour, when there are any to show
    research/      # optional source material
    phases/
      phase-1.md   # only for work too large to do in one go
```

Each spec says which backlog entries it was promoted from, and those entries point back at it.

## Spec structure

- A spec is delivered in one or more phases. Each phase can be committed on its own, has its tests, and leaves both apps working.
- A spec says how it will be checked: which tests, and what is looked at in the desktop app and in the web app.
- State is the `Status` field (`draft` → `in-review` → `accepted` → `in-progress` → `done`).

| # | Spec | Status |
|---|------|--------|
| 001 | [spec-001-settings-window](spec-001-settings-window/spec.md): a settings window, its own on the desktop, a panel in the web app | accepted |
