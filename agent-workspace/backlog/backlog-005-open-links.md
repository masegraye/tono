# backlog-005: Open links with Ctrl+click

| | |
|---|---|
| Status | captured |
| Area | Reading a terminal |
| Size | medium |
| Source | inv-001 |

## What

Ctrl+click opens a URL, whether the program marked it as a link (OSC 8) or it is plain text. Holding Ctrl over one underlines it and shows where it really goes.

## Why

URLs in output can't be followed today.

## Builds on

`ghostty_grid_ref_hyperlink_uri` in `grid_ref.h`. Rex Rune opens `http`, `https`, `ftp` and `mailto` only, never `file://`, since output may come from another machine, and shows the real target so link text can't disguise it.

## Promote when

After backlog-002, which brings the plumbing for pointing at a cell.
