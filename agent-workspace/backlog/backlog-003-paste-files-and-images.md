# backlog-003: Paste or drop files and images

| | |
|---|---|
| Status | captured |
| Area | Clipboard |
| Size | medium |
| Source | inv-001 |

## What

Pasting an image, or dropping a file on a pane, uploads it to the terminal's machine and types its path. This is how a screenshot gets into an agent.

## Why

Tono pastes text only.

## Builds on

`com.superlogical.terminal.upload_begin`, in the API since revision 11 (the servers here are on 12). Rex Rune's behaviour is in `docs/terminal.md` under Uploading files: nothing is typed unless every file arrived, a pasted image becomes `clipboard.png`, text wins when the clipboard has both.

## Promote when

After backlog-002, or sooner if pasting screenshots into agents is wanted first.

## Not known yet

Reading an image from the clipboard and taking a dropped file both work differently in a browser.
