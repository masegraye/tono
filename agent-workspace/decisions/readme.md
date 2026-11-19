# decisions

Decisions and their reasons, one per file, so that later work can rest on them without reopening them.

## Naming

`NNNN-title.md`: four digits, a kebab-case title. Numbers are permanent and are not reused.

## Status

| Status | When |
|--------|------|
| Proposed | Written down, not yet agreed |
| Accepted | Agreed, or already what the code does by an explicit choice |
| Superseded | A later decision replaces it. The file stays, pointing at the replacement |

## When to write one

When a choice will shape later work and someone could reasonably have chosen otherwise: which client owns a terminal's size, why tab names are made by the client, why the web page's connections go through its own server. A decision says what was chosen, what else was considered, and why.

## Template

```
# NNNN: Title

Status: Proposed | Accepted | Superseded by NNNN
Date: yyyy-mm-dd

## Context
## Decision
## Consequences
```

| # | Decision | Status |
|---|----------|--------|
| | _none yet_ | |

The workspace readme's convention, `adr-<nnn>-<slug>.md`, is the one used: `adr-001-…`.

| # | Decision | Status |
|---|----------|--------|
| 001 | [adr-001-app-identifier-and-where-files-go.md](adr-001-app-identifier-and-where-files-go.md): the app id is `dev.mg.tono`; files go where inv-003 says, Application Support only on macOS; font size is a setting | accepted |
