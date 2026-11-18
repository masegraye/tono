// Keeps the keyboard with the app.
//
// A browser acts on a key the page lets through, and most of a terminal's keys mean
// something to a browser: Control+P prints, Control+S saves the page, Control+F searches it,
// Control+Shift+P prints through the system's dialog. The app takes a key by cancelling it,
// which it does for keys that reach a terminal, but not for a key it has no use for at that
// moment (a shortcut pressed while a dialog is open), and not at all while the page's focus
// is outside its canvas, as it is when the page has just loaded. Three things here close
// those gaps, and a fourth is for a keyboard on a screen. Each is about the page rather than
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
    if (control && ['KeyC', 'KeyX', 'KeyV', 'KeyA', 'KeyZ', 'KeyY'].includes(event.code)) return true;
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
})();
