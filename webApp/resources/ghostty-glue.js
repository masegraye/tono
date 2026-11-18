// The browser's side of libghostty-vt, Ghostty's terminal emulation core, built for
// WebAssembly (native/build-libghostty-vt-wasm.sh).
//
// On the desktop the app calls the library a function at a time. From Kotlin in a browser
// every such call crosses into JavaScript and back, and drawing a screen takes a dozen calls
// per cell. So the per-cell work is done here, next to the library, and a whole frame goes
// back to Kotlin as one string (see `render`). Bytes travel as strings of one character per
// byte for the same reason: a string crosses in one piece.
//
// It mirrors ghostty/src@jvm/GhosttyRenderer.kt, which is the version to read first.
// Struct layouts and enum values are not written out here: the library describes its own
// ABI (ghostty_type_json), and they are looked up in that by name.
(function () {
  'use strict';

  let ex = null; // the module's exports
  let T = null; // its types: sizes, field offsets, and enum values
  const US = '\u001f'; // between the fields of a record
  const RS = '\u001e'; // between records
  const OK = 0;

  const view = () => new DataView(ex.memory.buffer); // remade each time: memory moves when it grows
  const bytes = () => new Uint8Array(ex.memory.buffer);
  const E = (type, name) => {
    const value = T[type] && T[type].values[name];
    if (value === undefined) throw new Error(`libghostty-vt has no ${type}.${name}`);
    return value;
  };
  const F = (type, name) => {
    const field = T[type] && T[type].fields[name];
    if (!field) throw new Error(`libghostty-vt has no ${type}.${name}`);
    return field.offset;
  };
  const sizeOf = (type) => T[type].size;
  const check = (what, result) => {
    if (result !== OK) throw new Error(`ghostty_${what} failed: ${result}`);
  };

  // Scratch memory in the module, for arguments and results. Grown when something larger is needed.
  let scratch = 0;
  let scratchSize = 0;
  function room(size) {
    if (size > scratchSize) {
      if (scratch) ex.ghostty_wasm_free(scratch, scratchSize);
      scratchSize = Math.max(size, 4096);
      scratch = ex.ghostty_wasm_alloc(scratchSize);
    }
    return scratch;
  }
  // Small fixed slots, apart from the scratch area so they survive its growing.
  let slots = 0;
  const SLOT = { a: 0, b: 16, c: 32, style: 64, colors: 160, cursor: 960, codepoints: 992 };
  const CODEPOINT_ROOM = 64;

  /** Copies a string of one character per byte into the module, and returns where. */
  function put(binary) {
    const at = room(Math.max(binary.length, 1));
    const memory = bytes();
    for (let i = 0; i < binary.length; i++) memory[at + i] = binary.charCodeAt(i);
    return at;
  }
  function take(pointer, length) {
    const memory = bytes();
    let out = '';
    for (let from = pointer; from < pointer + length; from += 8192) {
      out += String.fromCharCode.apply(null, memory.subarray(from, Math.min(from + 8192, pointer + length)));
    }
    return out;
  }
  const rgbAt = (pointer) => {
    const memory = bytes();
    return (memory[pointer] << 16) | (memory[pointer + 1] << 8) | memory[pointer + 2];
  };
  function putRgb(pointer, color) {
    const memory = bytes();
    memory[pointer] = (color >> 16) & 255;
    memory[pointer + 1] = (color >> 8) & 255;
    memory[pointer + 2] = color & 255;
  }

  /** Loads the library. Everything else here needs this to have finished. */
  async function init(source) {
    if (ex) return;
    const binary = typeof source === 'string' ? await (await fetch(source)).arrayBuffer() : source;
    const { instance } = await WebAssembly.instantiate(binary, {});
    ex = instance.exports;
    const json = ex.ghostty_type_json();
    const memory = bytes();
    let end = json;
    while (memory[end]) end++;
    T = JSON.parse(new TextDecoder().decode(memory.subarray(json, end))).types;
    slots = ex.ghostty_wasm_alloc(1280);
  }

  // --- Terminals ---

  function terminalNew(columns, rows) {
    check('terminal_new', ex.ghostty_terminal_new(0, slots + SLOT.a, columns, rows));
    return view().getUint32(slots + SLOT.a, true);
  }

  /**
   * Restores a terminal from the snapshot at the start of `binary`. Returns the terminal and
   * how many bytes the snapshot took, as "terminal,bytes".
   */
  function terminalRestore(binary) {
    const data = ex.ghostty_wasm_alloc(Math.max(binary.length, 1));
    try {
      const memory = bytes();
      for (let i = 0; i < binary.length; i++) memory[data + i] = binary.charCodeAt(i);
      check('snapshot_decoder_new_buf', ex.ghostty_snapshot_decoder_new_buf(0, slots + SLOT.a, data, binary.length));
      const decoder = view().getUint32(slots + SLOT.a, true);
      try {
        check('snapshot_decoder_decode', ex.ghostty_snapshot_decoder_decode(decoder, slots + SLOT.b));
        const terminal = view().getUint32(slots + SLOT.b, true);
        check('snapshot_decoder_get', ex.ghostty_snapshot_decoder_get(decoder, E('GhosttySnapshotDecoderData', 'SOURCE_OFFSET'), slots + SLOT.c));
        return terminal + ',' + view().getUint32(slots + SLOT.c, true);
      } finally {
        ex.ghostty_snapshot_decoder_free(decoder);
      }
    } finally {
      ex.ghostty_wasm_free(data, Math.max(binary.length, 1));
    }
  }

  function terminalFree(terminal) {
    ex.ghostty_terminal_free(terminal);
  }

  function terminalWrite(terminal, binary) {
    if (binary.length === 0) return;
    ex.ghostty_terminal_vt_write(terminal, put(binary), binary.length);
  }

  function terminalResize(terminal, columns, rows, cellWidthPx, cellHeightPx) {
    check('terminal_resize', ex.ghostty_terminal_resize(terminal, columns, rows, cellWidthPx, cellHeightPx));
  }

  function terminalNumber(terminal, data, width) {
    check('terminal_get', ex.ghostty_terminal_get(terminal, E('GhosttyTerminalData', data), slots + SLOT.a));
    return width === 2 ? view().getUint16(slots + SLOT.a, true) : view().getUint32(slots + SLOT.a, true);
  }
  const terminalColumns = (terminal) => terminalNumber(terminal, 'COLS', 2);
  const terminalRows = (terminal) => terminalNumber(terminal, 'ROWS', 2);
  const terminalWidthPx = (terminal) => terminalNumber(terminal, 'WIDTH_PX', 4);
  const terminalHeightPx = (terminal) => terminalNumber(terminal, 'HEIGHT_PX', 4);

  /** Sets the colors used where a program hasn't chosen one. -1 leaves one unset; `ansiList` is 16 colors with commas between, or empty. */
  function terminalSetColors(terminal, foreground, background, cursor, ansiList) {
    const ansi = ansiList ? ansiList.split(',').map(Number) : [];
    const rgb = slots + SLOT.a;
    const set = (option, color) => {
      if (color >= 0) putRgb(rgb, color);
      check('terminal_set', ex.ghostty_terminal_set(terminal, E('GhosttyTerminalOption', option), color >= 0 ? rgb : 0));
    };
    set('COLOR_FOREGROUND', foreground);
    set('COLOR_BACKGROUND', background);
    set('COLOR_CURSOR', cursor);
    if (ansi.length === 16) {
      // The palette is set whole: start from the standard 256 colors and replace the first 16.
      const palette = room(256 * 3);
      ex.ghostty_color_palette_default(palette);
      ansi.forEach((color, index) => putRgb(palette + index * 3, color));
      check('terminal_set', ex.ghostty_terminal_set(terminal, E('GhosttyTerminalOption', 'COLOR_PALETTE'), palette));
    }
  }

  function terminalScroll(terminal, tag, delta) {
    const behavior = slots + SLOT.a;
    bytes().fill(0, behavior, behavior + sizeOf('GhosttyTerminalScrollViewport'));
    view().setInt32(behavior + F('GhosttyTerminalScrollViewport', 'tag'), E('GhosttyTerminalScrollViewportTag', tag), true);
    view().setInt32(behavior + F('GhosttyTerminalScrollViewport', 'value'), delta, true);
    ex.ghostty_terminal_scroll_viewport(terminal, behavior);
  }
  const terminalScrollBy = (terminal, rows) => terminalScroll(terminal, 'DELTA', rows);
  const terminalScrollToBottom = (terminal) => terminalScroll(terminal, 'BOTTOM', 0);

  /** Whether the terminal's program asked for pasted text to be bracketed (mode 2004). */
  function terminalBracketedPaste(terminal) {
    const config = slots + SLOT.a;
    view().setUint16(config + F('GhosttyTerminalModeConfig', 'mode'), 2004, true);
    check('terminal_get', ex.ghostty_terminal_get(terminal, E('GhosttyTerminalData', 'MODE'), config));
    return bytes()[config + F('GhosttyTerminalModeConfig', 'value')] !== 0;
  }

  /** Makes pasted text safe to send, as bytes. `binary` is the text's UTF-8. */
  function pasteEncode(binary, bracketed) {
    const capacity = binary.length + 16; // room for the bracketed-paste markers
    const at = room(binary.length + 1 + capacity);
    const memory = bytes();
    for (let i = 0; i < binary.length; i++) memory[at + i] = binary.charCodeAt(i);
    const out = at + binary.length + 1;
    check('paste_encode', ex.ghostty_paste_encode(at, binary.length, bracketed ? 1 : 0, out, capacity, slots + SLOT.a));
    return take(out, view().getUint32(slots + SLOT.a, true));
  }

  // --- Drawing ---

  /** What is kept between frames for one terminal's drawing. */
  function rendererNew() {
    check('render_state_new', ex.ghostty_render_state_new(0, slots + SLOT.a));
    const state = view().getUint32(slots + SLOT.a, true);
    // The iterators are handed to the library by address, so each gets a slot of its own.
    const iteratorRef = ex.ghostty_wasm_alloc(8);
    check('render_state_row_iterator_new', ex.ghostty_render_state_row_iterator_new(0, iteratorRef));
    check('render_state_row_cells_new', ex.ghostty_render_state_row_cells_new(0, iteratorRef + 4));
    renderers.set(state, {
      iteratorRef,
      cellsRef: iteratorRef + 4,
      known: new Set(), // the rows sent whole last frame, which the other side still holds
      everything: true,
      foreground: -1,
      background: -1,
    });
    return state;
  }
  const renderers = new Map();

  function rendererFree(state) {
    const renderer = renderers.get(state);
    if (!renderer) return;
    ex.ghostty_render_state_row_cells_free(view().getUint32(renderer.cellsRef, true));
    ex.ghostty_render_state_row_iterator_free(view().getUint32(renderer.iteratorRef, true));
    ex.ghostty_wasm_free(renderer.iteratorRef, 8);
    ex.ghostty_render_state_free(state);
    renderers.delete(state);
  }

  /** Forgets what was sent, so the next frame carries every row. Call it after changing colors. */
  function rendererInvalidate(state) {
    renderers.get(state).everything = true;
  }

  /** Whether a monospace font can be trusted to draw this character exactly one cell wide. */
  const isPlain = (c) => (c >= 0x20 && c <= 0x7e) || (c >= 0xa0 && c <= 0x17f) || (c >= 0x2500 && c <= 0x259f);
  /** Halfway between two colors, for faint text. */
  const blend = (color, towards) => {
    const channel = (shift) => (((color >> shift) & 255) + ((towards >> shift) & 255)) >> 1;
    return (channel(16) << 16) | (channel(8) << 8) | channel(0);
  };

  /**
   * Describes what to draw, as one string.
   *
   * The first record is "columns US background US foreground US cursor", where cursor is
   * empty when it is hidden and otherwise "column,row,shape,blinking,color" (color -1 for the
   * foreground). Then one record per visible row, top to bottom: "=" and the row's id when
   * the row is unchanged since the last frame, or "+" and its id followed by its runs, each
   * six fields: column, cells, foreground, background (-1 for none), flags (1 bold, 2 italic,
   * 4 underline, 8 strikethrough) and text. A row keeps its id as it scrolls.
   */
  function render(state, terminal) {
    const renderer = renderers.get(state);
    check('render_state_update', ex.ghostty_render_state_update(state, terminal));
    const get = (data, out) => check('render_state_get', ex.ghostty_render_state_get(state, E('GhosttyRenderStateData', data), out));

    const colors = slots + SLOT.colors;
    view().setUint32(colors, sizeOf('GhosttyRenderStateColors'), true);
    get('COLORS', colors);
    const background = rgbAt(colors + F('GhosttyRenderStateColors', 'background'));
    const foreground = rgbAt(colors + F('GhosttyRenderStateColors', 'foreground'));
    const cursorColor = bytes()[colors + F('GhosttyRenderStateColors', 'cursor_has_value')]
      ? rgbAt(colors + F('GhosttyRenderStateColors', 'cursor')) : -1;

    get('DIRTY', slots + SLOT.a);
    // Rows bake in the default colors, so a change of defaults invalidates all of them too.
    const everything = renderer.everything || view().getInt32(slots + SLOT.a, true) === E('GhosttyRenderStateDirty', 'FULL') ||
      foreground !== renderer.foreground || background !== renderer.background;
    get('COLS', slots + SLOT.a);
    const columns = view().getUint16(slots + SLOT.a, true);

    get('ROW_ITERATOR', renderer.iteratorRef);
    const iterator = view().getUint32(renderer.iteratorRef, true);
    const rowGet = (data, out) => check('render_state_row_get', ex.ghostty_render_state_row_get(iterator, E('GhosttyRenderStateRowData', data), out));
    const out = [];
    const known = new Set();
    while (ex.ghostty_render_state_row_iterator_next(iterator)) {
      rowGet('ID', slots + SLOT.b);
      const id = view().getBigUint64(slots + SLOT.b, true).toString(36) + '.' + view().getBigUint64(slots + SLOT.b + 8, true).toString(36);
      rowGet('DIRTY', slots + SLOT.a);
      const dirty = bytes()[slots + SLOT.a] !== 0;
      if (!everything && !dirty && renderer.known.has(id)) {
        out.push('=' + id);
      } else {
        rowGet('CELLS', renderer.cellsRef);
        out.push('+' + id + buildRow(view().getUint32(renderer.cellsRef, true), foreground, background));
      }
      known.add(id);
    }
    renderer.known = known;
    renderer.everything = false;
    renderer.foreground = foreground;
    renderer.background = background;

    const cursor = slots + SLOT.cursor;
    view().setUint32(cursor, sizeOf('GhosttyRenderStateCursor'), true);
    get('CURSOR', cursor);
    const memory = bytes();
    const c = (name) => cursor + F('GhosttyRenderStateCursor', name);
    const cursorText = memory[c('visible')] && memory[c('viewport_has_value')]
      ? [view().getUint16(c('viewport_x'), true), view().getUint16(c('viewport_y'), true), view().getInt32(c('visual_style'), true), memory[c('blinking')], cursorColor].join(',')
      : '';
    check('render_state_clean', ex.ghostty_render_state_clean(state));
    return [columns, background, foreground, cursorText].join(US) + (out.length ? RS + out.join(RS) : '');
  }

  /** The runs of the row the cells iterator points at, each field led by a separator. */
  function buildRow(cells, defaultForeground, defaultBackground) {
    const cellData = T.GhosttyRenderStateRowCellsData.values;
    const wideKind = T.GhosttyCellWide.values;
    const style = slots + SLOT.style;
    const styleField = (name) => bytes()[style + F('GhosttyStyle', name)] !== 0;
    const cellGet = (data, out) => check('render_state_row_cells_get', ex.ghostty_render_state_row_cells_get(cells, data, out));
    const cellTry = (data, out) => ex.ghostty_render_state_row_cells_get(cells, data, out) === OK;

    let text = '';
    let open = false; // the run being put together
    let run = null;
    const flush = () => {
      if (!open) return;
      open = false;
      text += US + run.column + US + run.cells + US + run.foreground + US + run.background + US + run.flags + US + run.text;
    };

    let column = 0;
    while (ex.ghostty_render_state_row_cells_next(cells)) {
      const thisColumn = column++;
      cellGet(cellData.RAW, slots + SLOT.a);
      check('cell_get', ex.ghostty_cell_get(view().getBigUint64(slots + SLOT.a, true), E('GhosttyCellData', 'WIDE'), slots + SLOT.b));
      const wide = view().getInt32(slots + SLOT.b, true);
      // The second half of a wide character: the wide cell before it already covers this one.
      if (wide === wideKind.SPACER_TAIL) continue;

      let foreground = defaultForeground;
      let background = -1;
      let flags = 0;
      let invisible = false;
      // A cell can carry a background without any other styling, so always ask.
      if (cellTry(cellData.BG_COLOR, slots + SLOT.a)) background = rgbAt(slots + SLOT.a);
      cellGet(cellData.HAS_STYLING, slots + SLOT.a);
      if (bytes()[slots + SLOT.a]) {
        if (cellTry(cellData.FG_COLOR, slots + SLOT.a)) foreground = rgbAt(slots + SLOT.a);
        view().setUint32(style, sizeOf('GhosttyStyle'), true);
        cellGet(cellData.STYLE, style);
        if (styleField('bold')) flags |= 1;
        if (styleField('italic')) flags |= 2;
        if (view().getInt32(style + F('GhosttyStyle', 'underline'), true) !== E('GhosttySgrUnderline', 'NONE')) flags |= 4;
        if (styleField('strikethrough')) flags |= 8;
        invisible = styleField('invisible');
        if (styleField('inverse')) {
          const swapped = background >= 0 ? background : defaultBackground;
          background = foreground;
          foreground = swapped;
        }
        if (styleField('faint')) foreground = blend(foreground, background >= 0 ? background : defaultBackground);
      }

      cellGet(cellData.GRAPHEMES_LEN, slots + SLOT.a);
      const count = invisible ? 0 : view().getUint32(slots + SLOT.a, true);
      if (count === 0 && background < 0) {
        flush(); // nothing to draw here, and a gap ends the run, since runs are contiguous
        continue;
      }

      let cellText = ' ';
      let plain = true;
      if (count > 0) {
        const buffer = count <= CODEPOINT_ROOM ? slots + SLOT.codepoints : room(count * 4);
        cellGet(cellData.GRAPHEMES_BUF, buffer);
        const first = view().getUint32(buffer, true);
        plain = count === 1 && wide === wideKind.NARROW && isPlain(first);
        cellText = '';
        for (let i = 0; i < count; i++) cellText += String.fromCodePoint(view().getUint32(buffer + i * 4, true));
      }

      const width = wide === wideKind.WIDE ? 2 : 1;
      const joins = open && plain && run.plain && thisColumn === run.column + run.cells &&
        foreground === run.foreground && background === run.background && flags === run.flags;
      if (!joins) {
        flush();
        open = true;
        run = { column: thisColumn, cells: 0, plain, foreground, background, flags, text: '' };
      }
      run.text += cellText;
      run.cells += width;
    }
    flush();
    return text;
  }

  // --- The mouse ---

  function mouseNew() {
    check('mouse_encoder_new', ex.ghostty_mouse_encoder_new(0, slots + SLOT.a));
    const encoder = view().getUint32(slots + SLOT.a, true);
    check('mouse_event_new', ex.ghostty_mouse_event_new(0, slots + SLOT.a));
    return encoder + ',' + view().getUint32(slots + SLOT.a, true);
  }

  function mouseFree(encoder, event) {
    ex.ghostty_mouse_event_free(event);
    ex.ghostty_mouse_encoder_free(encoder);
  }

  /**
   * What to send the terminal's program for a pointer event, as bytes; empty when it wants
   * nothing. `action` is 0 press, 1 release, 2 motion; `button` 0 none, 1 left, 2 right,
   * 3 middle, 4 wheel up, 5 wheel down; `mods` 1 shift, 2 control, 4 alt.
   */
  function mouseEncode(encoder, event, terminal, action, button, mods, x, y, cellWidth, cellHeight, anyButtonDown) {
    ex.ghostty_mouse_encoder_setopt_from_terminal(encoder, terminal);
    const size = slots + SLOT.colors; // free between frames
    const put32 = (name, value) => view().setUint32(size + F('GhosttyMouseEncoderSize', name), value, true);
    bytes().fill(0, size, size + sizeOf('GhosttyMouseEncoderSize'));
    put32('size', sizeOf('GhosttyMouseEncoderSize'));
    put32('screen_width', terminalColumns(terminal) * cellWidth);
    put32('screen_height', terminalRows(terminal) * cellHeight);
    put32('cell_width', cellWidth);
    put32('cell_height', cellHeight);
    ex.ghostty_mouse_encoder_setopt(encoder, E('GhosttyMouseEncoderOption', 'SIZE'), size);
    bytes()[slots + SLOT.a] = anyButtonDown ? 1 : 0;
    ex.ghostty_mouse_encoder_setopt(encoder, E('GhosttyMouseEncoderOption', 'ANY_BUTTON_PRESSED'), slots + SLOT.a);

    ex.ghostty_mouse_event_set_action(event, action);
    if (button === 0) ex.ghostty_mouse_event_clear_button(event);
    else ex.ghostty_mouse_event_set_button(event, button);
    ex.ghostty_mouse_event_set_mods(event, mods);
    view().setFloat32(slots + SLOT.b, x, true);
    view().setFloat32(slots + SLOT.b + 4, y, true);
    ex.ghostty_mouse_event_set_position(event, slots + SLOT.b);

    const buffer = slots + SLOT.codepoints;
    check('mouse_encoder_encode', ex.ghostty_mouse_encoder_encode(encoder, event, buffer, 64, slots + SLOT.a));
    return take(buffer, view().getUint32(slots + SLOT.a, true));
  }

  globalThis.GhosttyGlue = {
    init, terminalNew, terminalRestore, terminalFree, terminalWrite, terminalResize, terminalColumns, terminalRows,
    terminalWidthPx, terminalHeightPx, terminalSetColors, terminalScrollBy, terminalScrollToBottom, terminalBracketedPaste,
    pasteEncode, rendererNew, rendererFree, rendererInvalidate, render, mouseNew, mouseFree, mouseEncode,
  };
})();
