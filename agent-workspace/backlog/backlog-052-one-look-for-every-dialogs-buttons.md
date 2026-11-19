# backlog-052: One look for every dialog's buttons, and a focus ring that hugs its button

| | |
|---|---|
| Status | done (commit `e8a2e9d`, 2026-11-18) |
| Area | Dialogs |
| Size | small |
| Source | Mase, looking at the Close Pane confirmation |

## What

Every modal's buttons are the ones the Add Remote Host form has: Cancel, quiet, left of a
filled button for what the dialog is there to do, at the bottom right, each saying which key
presses it. The ring that shows which button has the keyboard is that button's own outline.

## Why

The ring around "Close Pane" didn't follow the button's edge. It was drawn around the room
Material gives a button for a finger (48 high), not around the button as drawn (40 high), so
it stood 3 off the button's ends and 7 off its top and bottom, and read as a shape of its own.

## What was built

The audit, of everything in `tono/src` that stands over the window:

| Where | Buttons | Before | Now |
|---|---|---|---|
| The main window's question (`RexApp.kt`): Close Pane, Close Tab, Close Other Tabs, Close Session; Replace and Reset from Change Shortcut… | Cancel, the action | its own `AlertDialog` with the shared buttons | `ConfirmationDialog` (`Dialogs.kt`) |
| The settings' question (`Settings.kt`): Reset All Shortcuts, Remove Host, Replace and Reset | Cancel, the action | a second copy of the same | the same `ConfirmationDialog` |
| `FormDialog` as a dialog: Add and Edit Host, Client Info's label, Rename Tab, Pane and Session; and its Discard question | Cancel or Keep Editing, the action | the shared buttons | the same, and Escape now cancels from a button too |
| `FormDialog` as a sheet (narrow) | Cancel top left, Save top right, plain text | | left as it is: where thumbs reach, as design-001 has it |
| Change Shortcut… (`ChangeShortcut.kt`) | none: a list, chosen with Enter | | nothing to change; its questions are the first two rows |
| The command palette and its pickers, the path browser, the pane menu, the menus | none: lists | | nothing to change |
| The search bar, the debug log, the notification question, the settings' cards | toolbar and text buttons in a bar or a card, not a modal | | left |

So there was one look already, kept in two copies; there is now one copy. What changed for
the eye is the ring.

- **The ring** (`keyboardFocusRing`) is drawn from the button's own shape and size: 2 dp off
  it, 2 dp thick, corners grown with it (`ringOutline`), in the theme's text color. The button
  inside `DialogButtonPlace` is the size it is drawn at, and the place around it keeps the
  room it had before (48 for a finger, and 6 around), so no dialog's layout moved and nothing
  shifts when the ring appears.
- **One confirmation dialog**, `ConfirmationDialog(pending, onDismiss)`, for the main window
  and the settings.

Decisions made while building, none of them asked for:

- A dialog now asks for the keyboard from inside itself (the confirming button; a form's
  first field). Asked for from outside, the request could come before the dialog had drawn
  what was to be given it, which is what happened in offscreen pictures. A form as a dialog
  is now pressed through offscreen too (`SettingsHostsRenderTest`, at 700 wide).
- Escape on either button backs out, in the questions and the forms. A browser doesn't always
  hand Escape to the dialog; before, only the confirming button of a question listened.
- Tab goes field, action, Cancel, which is the order Material's dialog gives its buttons. Left.

Checked:

- Offscreen (`DialogButtonsRenderTest`, pictures in `build/dialog-renders/`, looked at), in
  Retro 82 and Tokyo Night: Close Pane (destructive) and Replace (not), with the keyboard on
  the action and after Tab on Cancel; Rename Tab with the ring on each button; a form's
  Discard question. The test measures the ring against the button: 2 of ring, 2 of dialog,
  then the button, top and bottom, and that the filled button doesn't move when the ring does.
  Enter on Cancel cancels; Enter on the action does it.
- The web app, headless Chromium, against a scratch Rex server with one scratch session:
  Close Pane from the palette; the ring on Close Pane, then on Cancel after Tab, then back;
  Enter, Space and Escape on Cancel and Escape on Close Pane closed nothing and typing went to
  the terminal again; Enter on Close Pane closed the pane. No page errors.
- `task test` passes.

Not checked: the desktop app in a real window (the offscreen pictures are drawn by the same
code); Firefox and Safari; the Control+Shift+W key itself in the browser, which closes the
headless browser's window before the page hears it, so the palette was used.
