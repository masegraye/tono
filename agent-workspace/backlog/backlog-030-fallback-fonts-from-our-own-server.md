# backlog-030: Serve fallback fonts ourselves

| | |
|---|---|
| Status | captured |
| Area | The web app |
| Size | medium |
| Source | earlier sessions |

## What

Get the fonts for characters the desktop's font lacks (Chinese, emoji) from the page's own server.

## Why

Compose fetches them from Google's font service, which is the one request the page makes to the internet, and it says which scripts appear in your terminals.

## Builds on

Compose has a downloader for these that can be replaced (`defaultFallbackFontDownloader`). The desktop has Noto fonts installed that the server could hand over.

## Promote when

If that request matters, or the web app has to work without the internet.

## Not known yet

Whether that downloader is public API or internal, and so whether it is safe to depend on. Seen in the library's symbols, not in its documentation.
