# backlog-029: Try the web app in Safari and on real phones

| | |
|---|---|
| Status | captured |
| Area | The web app |
| Size | small |
| Source | earlier sessions |

## What

Open the web app in Safari on a Mac, and on a real iPhone and Android phone, and fix what breaks.

## Why

None of these has been tried. The keyboard on a screen is tested only with made-up input, and the code that keeps the prompt above an iPhone's keyboard has never run on one.

## Builds on

`tono/src/ScreenKeyboard.kt` and `webApp/resources/page-keys.js`. The README's "What has been tried, and what hasn't".

## Promote when

Before relying on the web app from a phone.

## Not known yet

Sounds are Ogg files, which older Safari versions don't play.
