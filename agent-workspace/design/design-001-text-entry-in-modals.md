# design-001: Text entry goes in a modal; switches and choices apply in place

| | |
|---|---|
| Status | accepted |
| Decided by | Mase, 2026-11-19, while reviewing spec-001's Remote Hosts |
| Applies to | the settings window (spec-001) and anything after it that asks for typed input |

## The rule

- **Switches and single choices apply in place, at once.** A switch, a choice from a short list, or a theme picked in a list: one atomic change, applied and saved as it is made. No Save button.
- **Anything that needs typing goes in a modal, with Save and Cancel.** Examples: names, addresses, a host's label or addresses together with its icon. The modal holds only that form:
  - Enter saves and Escape cancels;
  - the first field has focus when it opens;
  - Save is disabled until the form is valid;
  - an error the app or the server reports (for example a `conflict` on a duplicate label) is shown next to the field it concerns, and the modal stays open;
  - Cancel, or a click outside the modal, discards the changes. The modal asks first if anything was changed.
- **The page behind stays read-and-act.** It shows state and offers actions: status, Try Again Now, Move Up and Move Down, Remove, and an **Edit…** button that opens the modal. It has no fields of its own.

## Why

- **Mixing models confuses.** A switch has taken effect when it's flipped. An inline form next to it looks as if its fields have too, until a "Save Changes" button further down says otherwise.
- **Several fields are only valid together, and the server can refuse them.** A modal makes that a single step that succeeds or fails, with the reason in place.
- **Rex for Mac does the same:** a host's ⓘ opens a sheet over its settings. So does Tono already: renaming a tab, pane or session uses one shared dialog (`RenameDialog` in `tono/src/RenameTab.kt`).
- **Phones:** a single focused form works with the on-screen keyboard; a long page with fields scattered through it doesn't.

## How it looks

- **Desktop and wide web:** a dialog over the window or panel, about 480 dp wide, with the title, the fields, then Cancel and Save at the bottom right. It's built on the same frame as `RenameDialog` (Material 3 `AlertDialog`), generalised for several fields where needed.
- **Narrow, and phones** (`RexUiState.narrow`, or the settings' narrow layout): a full-screen sheet with Cancel at the top left, the title, and Save at the top right. The browser's back acts as Cancel.

## Not covered by this rule

- **Shortcut recording** (backlog-042, spec-001's Shortcuts) is a capture panel, not text entry. It stays as it is.
- **Search fields** filter as you type and commit nothing, so they stay in place.
- **The command palette** is its own overlay.

## Where it applies now

spec-001's Remote Hosts: **Edit…** and **Add Remote Host…** open the same modal. It holds the label, the addresses (for a host in the home list), and the icon. In Add, the servers found on the tailnet are listed inside the modal, and choosing one fills the fields. The host details page keeps status, Try Again Now, where the host comes from, Move Up and Move Down, and Remove Host…. Phase 4's Client Info label follows the same rule.
