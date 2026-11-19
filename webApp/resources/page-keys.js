// Keeps the keyboard with the app.
//
// A browser acts on a key the page lets through, and most of a terminal's keys mean
// something to a browser: Control+P prints, Control+S saves the page, Control+F searches it,
// Control+Shift+P prints through the system's dialog. The app takes a key by cancelling it,
// which it does for keys that reach a terminal, but not for a key it has no use for at that
// moment (a shortcut pressed while a dialog is open), and not at all while the page's focus
// is outside its canvas, as it is when the page has just loaded. Three things here close
// those gaps, a fourth is for a keyboard on a screen, a fifth for a mouse's side buttons, a sixth for its middle one, and a seventh for its right one. Each is about the page rather than
// the app, which is why they are not in it.
(() => {
  'use strict';

  // --- 1. Nothing with Control or Alt, and no function key, is left for the browser ---
  //
  // This runs after the app has seen the key, and cancels it whether or not the app wanted
  // it, so a shortcut that does nothing right now does nothing, rather than something else.
  const mac = /Mac|iPhone|iPad/.test(navigator.platform);
  /** The keys that stay the browser's, because what the browser does with them is wanted. */
  function leftToBrowser(event) {
    // The system's modifier, and on a Mac the browser's own: none of the app's keys use it.
    if (event.metaKey) return true;
    // AltGr, and Option on a Mac, type characters.
    if (event.getModifierState('AltGraph') || (mac && event.altKey && !event.ctrlKey)) return true;
    const functionKey = /^F\d+$/.test(event.key);
    // Typing, and moving about.
    if (!event.ctrlKey && !event.altKey && !functionKey) return true;
    const control = event.ctrlKey && !event.altKey;
    // Editing in a text field: copy, cut, paste, select all, undo, redo.
    if (control && ['KeyC', 'KeyX', 'KeyV', 'KeyA', 'KeyZ', 'KeyY'].includes(event.code)) {
      // With Shift, C and A are the app's, for copying from a terminal and selecting all of
      // it, and a browser has other uses for them: Chromium inspects the page and searches tabs.
      return !(event.shiftKey && ['KeyC', 'KeyA'].includes(event.code));
    }
    // Reloading the page, which is the way out when something has gone wrong, and the
    // developer tools. A terminal that has the keyboard takes these first, as it should.
    if (event.key === 'F5' || (control && event.code === 'KeyR')) return true;
    if (event.key === 'F12' || (control && event.shiftKey && ['KeyI', 'KeyJ'].includes(event.code))) return true;
    return false;
  }
  addEventListener('keydown', (event) => {
    if (!leftToBrowser(event)) event.preventDefault();
  });

  // --- 2. The page's focus stays in the app ---
  //
  // The app hears keys only while its canvas, or a text field of its own, has the page's
  // focus. A page starts with the focus on nothing, and so the first shortcut pressed went
  // to the browser instead. Whenever the focus is on nothing, it is put on the canvas.
  function canvas() {
    for (const element of document.querySelectorAll('*')) {
      const found = element.shadowRoot && element.shadowRoot.querySelector('canvas');
      if (found) return found;
    }
    return null;
  }
  function keepFocus() {
    if (document.activeElement && document.activeElement !== document.body) return;
    const target = canvas();
    if (target) target.focus({ preventScroll: true });
  }
  // The app makes its canvas a moment after the page loads.
  const waiting = setInterval(() => {
    if (!canvas()) return;
    clearInterval(waiting);
    keepFocus();
  }, 100);
  addEventListener('focus', () => setTimeout(keepFocus, 0));
  document.addEventListener('focusout', () => setTimeout(keepFocus, 0));
  addEventListener('keydown', keepFocus, true);

  // --- 3. A tab asks before it closes on a key meant for a terminal ---
  //
  // Some keys never reach a page in a browser tab at all: the browser acts on them first.
  // Control+W is one, and it closes the tab; in a shell the same key deletes a word, so it
  // gets pressed a lot. The page can't take the key back, but it can tell that this is what
  // happened: it saw Control go down and then nothing, where any key it is allowed to have
  // would have followed. Leaving the page then gets the browser's "leave site?" question,
  // and reloading or closing it any other way doesn't.
  //
  // An installed app window and full screen give the page those keys, and then this never fires.
  let controlHeld = false;
  let onlyModifiersSince = false; // nothing but modifier keys seen since Control went down
  const isModifier = (event) => ['Control', 'Shift', 'Alt', 'Meta'].includes(event.key);
  addEventListener('keydown', (event) => {
    if (event.key === 'Control') { controlHeld = true; onlyModifiersSince = true; }
    else if (!isModifier(event)) onlyModifiersSince = false;
  }, true);
  addEventListener('keyup', (event) => { if (event.key === 'Control') controlHeld = false; }, true);
  addEventListener('blur', () => { controlHeld = false; });
  addEventListener('beforeunload', (event) => {
    if (controlHeld && onlyModifiersSince) event.preventDefault();
  });

  // --- 4. The page ends where a keyboard on the screen begins ---
  //
  // Some phones draw their keyboard over the bottom of the page rather than making the page
  // shorter, and the bottom of a terminal is where the prompt is. The part still in view is
  // known, so the page is made that tall, and the app lays itself out in what is left.
  // Not while the page is zoomed, when the part in view is smaller for another reason.
  if (window.visualViewport) {
    const fit = () => {
      const covered = visualViewport.scale === 1 && innerHeight - visualViewport.height > 1;
      const height = covered ? visualViewport.height + 'px' : '';
      document.documentElement.style.height = height;
      document.body.style.height = height;
      if (covered) scrollTo(0, 0);
    };
    visualViewport.addEventListener('resize', fit);
    visualViewport.addEventListener('scroll', fit);
  }

  // --- 5. The mouse's back and forward buttons stay in the app ---
  //
  // The two buttons on the side of a mouse take a browser back and forward through the
  // pages it has shown, which here means off the app and onto whatever was open before it.
  // The app has its own use for them, going back and forward through its tabs. It hears the
  // press either way; cancelling it here is what keeps the browser where it is. Browsers
  // differ on whether they act on the press or the release, so both are cancelled.
  for (const type of ['mousedown', 'mouseup', 'auxclick']) {
    addEventListener(type, (event) => {
      if (event.button === 3 || event.button === 4) event.preventDefault();
    });
  }

  // --- 6. The middle button does only what the app says ---
  //
  // A browser has its own uses for the middle button: on Windows and where it is turned on
  // elsewhere, pressing it starts scrolling the page by moving the mouse. The app hears the
  // press and decides for itself whether it pastes into a terminal (its Primary Selection
  // setting); cancelling the press here keeps the browser from doing anything besides. A
  // text field of the page's own keeps what the browser does there.
  addEventListener('mousedown', (event) => {
    if (event.button !== 1) return;
    const target = event.composedPath ? event.composedPath()[0] : event.target;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
    event.preventDefault();
  });

  // --- 7. The right button opens the app's menu, and not the browser's as well ---
  //
  // A right click on a pane opens the pane's own menu (Copy, Paste, Split Right and the
  // rest), and with Shift held it goes to a program that tracks the mouse. The browser's
  // menu, with its Back, Reload and Save As, would open on top of either. The app hears
  // the press whatever happens here; cancelling the browser's menu is what keeps it away.
  // A text field of the page's own keeps the browser's menu, which is where its paste is.
  //
  // Firefox opens its own menu on Shift and a right click whatever a page says, which is
  // its way out of pages that do this. There the program still gets the click, under it.
  addEventListener('contextmenu', (event) => {
    const target = event.composedPath ? event.composedPath()[0] : event.target;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
    event.preventDefault();
  });
})();
