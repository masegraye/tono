# Instructions for Coding Agents

Tono is a desktop and web client for [Rex](https://www.superlogical.com/rex/), written in Kotlin Multiplatform. This file says where things are. The details live in the documents it points to; read them, rather than this summary, before acting on them.

## Read first

1. [agent-workspace/readme.md](agent-workspace/readme.md): how work is tracked, and the **rules for agents**. They're binding. Mase gathered them from earlier sessions, and several came from mistakes made while testing.
2. [agent-workspace/ops/subagents.md](agent-workspace/ops/subagents.md): how to test without disturbing Mase's machine. It covers scratch Rex servers, nested X displays for desktop checks, ports, and what workers share. It applies to any agent, not only subagents.
3. [docs/development.md](docs/development.md): the modules, the tasks, the tests, and how to measure scrolling.

## Where Tono is going, and what's decided

- [big-ideas/big-idea-001](agent-workspace/big-ideas/big-idea-001-tono-on-every-platform.md): Tono on Linux (AUR), iOS, macOS and later Android; the web client served by Tono itself.
- [decisions/](agent-workspace/decisions/readme.md): choices later work rests on. For example, adr-001: the app id is `dev.mg.tono`, and where Tono keeps its files.
- [design/](agent-workspace/design/readme.md): how Tono looks and behaves. For example, design-001: typed input goes in a modal, and switches apply in place.
- [specs/](agent-workspace/specs/readme.md), [backlog/](agent-workspace/backlog/readme.md), [investigations/](agent-workspace/investigations/readme.md): what's being built, what could be, and what's been looked into.
- [CHANGELOG.md](CHANGELOG.md): what's new for users. A change users would notice adds a line under Unreleased.

## Rules that are easy to get wrong

These are spelled out in the documents above; they're repeated here because breaking them affects Mase directly.

- **Rex for Mac is the reference** for names, shortcuts and behaviour (Ctrl or Ctrl+Shift where the Mac has Cmd). Rex Rune comes second. Read the Rex docs before guessing.
- **Never touch Mase's Rex sessions, host list or files.**
  - Test against a scratch Rex server of your own (`rex server run --listen unix:///tmp/<you>/rex.sock`), with `REX_SERVER` or a `--workspace` file pointing at it.
  - A web or desktop app started any other way connects to all of Mase's machines.
- **Never use the real desktop.** No keys, pointer, focus, clipboard, browser or notifications on display `:0`.
  - Desktop checks run on a nested `Xwayland` display on a hidden workspace (see ops/subagents.md).
  - Tests can't reach the desktop: the JVM build holds back links, the clipboard, sounds and notifications while JUnit runs. Don't work around that.
- **Don't run `task install`** unless you were asked to. It replaces the app Mase uses.
- **Tono's own files** live where adr-001 says (on Linux, `~/.config/tono/` and `~/.local/state/tono/`). Never edit the real ones; use temporary directories or `--workspace`.

## The build

This project uses the [Kotlin Toolchain](https://kotlin-toolchain.org/) (a dedicated build tool for Kotlin) through the `./kotlin` wrapper. Run `./kotlin --help` to see the available commands, or `./kotlin <command> --help` for one command's options. Common entry points are in `Taskfile.yml` (`task --list`):
- `task test` runs every module's tests;
- `task package` builds the desktop jar;
- `./kotlin build -m webApp` builds the web app.

## UI development with Compose Hot Reload

The Compose Hot Reload MCP server can shorten the loop for UI work: it can interact with the app, capture screenshots and inspect the semantic tree. Run `./kotlin compose-hot-reload-mcp-server --help` to configure it, and prefer headless mode.

**Run the app against a scratch Rex server, not Mase's.** Give it a `--workspace` file whose only host is your scratch server's socket (see "Rules that are easy to get wrong" above). Start it in the background with `./kotlin run -m <compose-hot-reloadable-module> --compose-hot-reload`, then call the MCP `status` tool until it reports `connected: true` (the first build can take minutes). `status` is the only readiness signal; don't tail the run log or write sleep loops. If startup fails, check `lastError` in `status` and the MCP `get_logs` tool. Once connected, edit the UI and apply changes with MCP `reload` (or `await_reload` if `buildContinuous: true`).

Offscreen render tests (`tono/test@jvm/*RenderTest.kt`) are the other way to see UI changes without a window, and they're what most checks here use.
