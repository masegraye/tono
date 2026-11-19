# backlog-046: Living without a bundled server

| | |
|---|---|
| Status | promoted to spec-001 (phase 4) |
| Area | Connecting to servers |
| Size | medium |
| Source | Mase, 2026-11-19, comparing with Rex for Mac's Server settings |

## What

Rex for Mac ships with its own Rex server and manages it (Settings › Server: Rex Server, Remote Address, Tailscale, Client Info). Tono doesn't: on Linux the server is a separate install (`rex`, started by the `rex.socket` user unit or `rex server start`). What falls out of that:

- **Show what can be learned about a server**, for the home server and any connected host. `server.status`, `server.version` and `server.whoami` give the version and API revision, process id and start time, the account it runs as, session count, its listeners (the Unix socket, a TCP address, the login server), Tailscale (state, name on the tailnet, HTTPS address, account), and the configuration file's generation. The settings window's Server item shows this.
- **When there's no local server**, say so plainly instead of a host that "Couldn't connect" forever. Find out whether `rex` is installed (on `PATH`), and whether `rex.socket` is enabled or the server running. Then offer what fits: start it (`rex server start`), start it at login (`rex server install`), or install Rex (link to the Rex docs for Linux). Never start or install anything without being asked.
- **Client Info:** the label this client gives itself (`client.set_label`), as the Mac's Settings › Server › Client Info has.
- **Tailscale:** the server can sign its Tailscale device in (`server.tailscale.login` gives a link), turn Tailscale on and off and rename it. Show its state; offer sign-in when it's signed out.

## Why

A first run on a machine without a Rex server shows a red "Local" and nothing else. And a server's state is useful to see without the CLI.

## Builds on

spec-001 (the Server item at the top of the settings list). The server's API (`rex api describe server.status` etc.).

## Promote when

Now; promoted into spec-001 as phase 4 (Mase, 2026-11-19).
