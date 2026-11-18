# backlog-007: Jump between shell prompts

| | |
|---|---|
| Status | captured |
| Area | Reading a terminal |
| Size | small |
| Source | inv-001 |

## What

Ctrl+Shift+Up and Down move the view to the previous and next shell prompt, without typing anything.

## Why

The quick way back to the start of a long command's output.

## Builds on

libghostty-vt marks prompt rows (`GHOSTTY_ROW_DATA_SEMANTIC_PROMPT` in `screen.h`), from the OSC 133 marks shells send. Rex Rune: `client.prompt.previous` and `client.prompt.next`.

## Promote when

With backlog-006, since both move the view through history.
