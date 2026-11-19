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
    selecting = ex.ghostty_wasm_alloc(128);
  }

  // --- Terminals ---

  /** How much image data one terminal keeps, as in other Rex clients. */
  const IMAGE_STORAGE_BYTES = 64n << 20n;

  function terminalNew(columns, rows) {
    check('terminal_new', ex.ghostty_terminal_new(0, slots + SLOT.a, columns, rows));
    return allowImages(view().getUint32(slots + SLOT.a, true));
  }

  /**
   * Lets programs show images with the Kitty graphics protocol, which is off until a terminal
   * is given room to keep them. Only pixels sent in the output itself are taken; this build of
   * the library can't read files at all, and the settings say so as the desktop's do.
   */
  function allowImages(terminal) {
    const set = (option, value) => check('terminal_set', ex.ghostty_terminal_set(terminal, E('GhosttyTerminalOption', option), value));
    view().setBigUint64(slots + SLOT.a, IMAGE_STORAGE_BYTES, true);
    set('KITTY_IMAGE_STORAGE_LIMIT', slots + SLOT.a);
    bytes()[slots + SLOT.b] = 0;
    set('KITTY_IMAGE_MEDIUM_FILE', slots + SLOT.b);
    set('KITTY_IMAGE_MEDIUM_SHARED_MEM', slots + SLOT.b);
    set('KITTY_IMAGE_MEDIUM_TEMP_FILE', 0);
    return terminal;
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
        // A snapshot can carry the images on screen; they are dropped unless there is room for them.
        view().setBigUint64(slots + SLOT.c, IMAGE_STORAGE_BYTES, true);
        check('snapshot_decoder_set', ex.ghostty_snapshot_decoder_set(decoder, E('GhosttySnapshotDecoderOption', 'KITTY_IMAGE_STORAGE_LIMIT'), slots + SLOT.c));
        check('snapshot_decoder_decode', ex.ghostty_snapshot_decoder_decode(decoder, slots + SLOT.b));
        const terminal = allowImages(view().getUint32(slots + SLOT.b, true));
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
  /** The row at the top of the view, counted from the oldest row kept: the difference across a scroll is how far the view really moved. */
  function terminalViewportTop(terminal) {
    const scrollbar = slots + SLOT.c;
    if (ex.ghostty_terminal_get(terminal, E('GhosttyTerminalData', 'SCROLLBAR'), scrollbar) !== OK) return 0;
    return Number(view().getBigUint64(scrollbar + F('GhosttyTerminalScrollbar', 'offset'), true));
  }
  /** Moves the view by `count` shell prompts (OSC 133 marks): negative is up, to older ones. Nothing moves without marks. */
  const terminalScrollByPrompts = (terminal, count) => terminalScroll(terminal, 'DELTA_PROMPT', count);

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
    const iteratorRef = ex.ghostty_wasm_alloc(12);
    check('render_state_row_iterator_new', ex.ghostty_render_state_row_iterator_new(0, iteratorRef));
    check('render_state_row_cells_new', ex.ghostty_render_state_row_cells_new(0, iteratorRef + 4));
    check('render_state_image_iterator_new', ex.ghostty_render_state_image_iterator_new(0, iteratorRef + 8));
    renderers.set(state, {
      iteratorRef,
      cellsRef: iteratorRef + 4,
      imagesRef: iteratorRef + 8,
      placements: null, // which arrangement of images was last described, as the library numbers them
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
    ex.ghostty_render_state_image_iterator_free(view().getUint32(renderer.imagesRef, true));
    ex.ghostty_wasm_free(renderer.iteratorRef, 12);
    ex.ghostty_render_state_free(state);
    renderers.delete(state);
  }

  /** Forgets what was sent, so the next frame carries every row. Call it after changing colors. */
  function rendererInvalidate(state) {
    renderers.get(state).everything = true;
  }

  /** Asks for `rows` rows past the top and the bottom of the view to be read as well, where there are any: see `render`. */
  function rendererOverscan(state, rows) {
    const request = slots + SLOT.a;
    view().setUint16(request + F('GhosttyRenderStateOverscan', 'above'), rows, true);
    view().setUint16(request + F('GhosttyRenderStateOverscan', 'below'), rows, true);
    check('render_state_set', ex.ghostty_render_state_set(state, E('GhosttyRenderStateOption', 'OVERSCAN'), request));
    renderers.get(state).overscan = rows;
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
   * The first record is "columns US background US foreground US cursor US images US
   * selection US above US below", where cursor is empty when it is hidden and otherwise
   * "column,row,shape,blinking,color" (color -1 for the foreground), images is as
   * `describeImages` says, and selection is the selected cells on screen, "row,first
   * column,last column" for each row that has some, with ";" between: a row above the view counts down from -1, and one below it on from the view's last. Above and below
   * are how many rows from past the view's top and bottom were read (`rendererOverscan`). Then one record per row read, top to bottom, those above the view first and those below it last: "=" and the row's id when
   * the row is unchanged since the last frame, or "+" and its id followed by its runs, each
   * seven fields: column, cells, foreground, background (-1 for none), flags (1 bold, 2 italic,
   * 8 strikethrough, 16 overline, and 256 times the kind of underline: 0 none, 1 single,
   * 2 double, 3 curly, 4 dotted, 5 dashed), the underline's color (-1 for the foreground)
   * and text. A row keeps its id as it scrolls.
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

    let above = 0;
    let below = 0;
    if (renderer.overscan && ex.ghostty_render_state_get(state, E('GhosttyRenderStateData', 'OVERSCAN'), slots + SLOT.a) === OK) {
      above = view().getUint16(slots + SLOT.a + F('GhosttyRenderStateOverscan', 'above'), true);
      below = view().getUint16(slots + SLOT.a + F('GhosttyRenderStateOverscan', 'below'), true);
    }

    get('ROW_ITERATOR', renderer.iteratorRef);
    const iterator = view().getUint32(renderer.iteratorRef, true);
    const rowGet = (data, out) => check('render_state_row_get', ex.ghostty_render_state_row_get(iterator, E('GhosttyRenderStateRowData', data), out));
    const out = [];
    const known = new Set();
    const selected = [];
    const rowSelection = slots + SLOT.c;
    while (ex.ghostty_render_state_row_iterator_next(iterator)) {
      // Asked of every row, changed or not: selecting changes no row's content.
      view().setUint32(rowSelection, sizeOf('GhosttyRenderStateRowSelection'), true);
      if (ex.ghostty_render_state_row_get(iterator, E('GhosttyRenderStateRowData', 'SELECTION'), rowSelection) === OK) {
        selected.push((out.length - above) + ',' + view().getUint16(rowSelection + F('GhosttyRenderStateRowSelection', 'start_x'), true) +
          ',' + view().getUint16(rowSelection + F('GhosttyRenderStateRowSelection', 'end_x'), true));
      }
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
    const images = describeImages(state, renderer);
    check('render_state_clean', ex.ghostty_render_state_clean(state));
    return [columns, background, foreground, cursorText, images, selected.join(';'), above, below].join(US) + (out.length ? RS + out.join(RS) : '');
  }

  /**
   * Which images are on screen and where: "=" when that is as it was last frame, as it nearly
   * always is, and otherwise the placements with ";" between, in the order to draw them. Each
   * is fifteen numbers with commas between: the image's number and version, its layer (0 below
   * the background, 1 below text, 2 above it), the column and row of its top-left cell, the
   * columns and rows it covers, the part of the image to draw (x, y, width, height), the size
   * to draw at and the offset into the first cell, in the terminal's own pixels. The pixels
   * themselves are asked for by number, once per version (`imageRead`).
   */
  function describeImages(state, renderer) {
    if (ex.ghostty_render_state_get(state, E('GhosttyRenderStateData', 'IMAGE_PLACEMENTS_GENERATION'), slots + SLOT.a) !== OK) return '=';
    const generation = view().getBigUint64(slots + SLOT.a, true);
    if (generation === renderer.placements) return '=';
    if (ex.ghostty_render_state_get(state, E('GhosttyRenderStateData', 'IMAGE_ITERATOR'), renderer.imagesRef) !== OK) return '=';
    renderer.placements = generation;

    const images = view().getUint32(renderer.imagesRef, true);
    const data = T.GhosttyRenderStateImageData.values;
    const layers = T.GhosttyRenderStateImageLayer.values;
    const get = (what) => check('render_state_image_get', ex.ghostty_render_state_image_get(images, what, slots + SLOT.a));
    const unsigned = (what) => (get(what), view().getUint32(slots + SLOT.a, true));
    const signed = (what) => (get(what), view().getInt32(slots + SLOT.a, true));
    const found = [];
    while (ex.ghostty_render_state_image_iterator_next(images)) {
      if (signed(data.PROTOCOL) !== E('GhosttyImageProtocol', 'KITTY')) continue;
      const layer = signed(data.LAYER);
      get(data.IMAGE_GENERATION);
      const version = view().getBigUint64(slots + SLOT.a, true).toString();
      found.push([
        unsigned(data.IMAGE_ID), version,
        layer === layers.BELOW_BACKGROUND ? 0 : layer === layers.BELOW_TEXT ? 1 : 2,
        signed(data.VIEWPORT_COL), signed(data.VIEWPORT_ROW), unsigned(data.GRID_COLS), unsigned(data.GRID_ROWS),
        unsigned(data.SOURCE_X), unsigned(data.SOURCE_Y), unsigned(data.SOURCE_WIDTH), unsigned(data.SOURCE_HEIGHT),
        unsigned(data.PIXEL_WIDTH), unsigned(data.PIXEL_HEIGHT), unsigned(data.CELL_OFFSET_X), unsigned(data.CELL_OFFSET_Y),
      ].join(','));
    }
    return found.join(';');
  }

  /**
   * The pixels of an image a program sent, as "width,height," and then four bytes a pixel:
   * red, green, blue, alpha. Empty if there is no such image, its pixels haven't arrived yet,
   * or they are in a form this doesn't know.
   */
  function imageRead(terminal, id) {
    if (ex.ghostty_terminal_get(terminal, E('GhosttyTerminalData', 'KITTY_GRAPHICS'), slots + SLOT.a) !== OK) return '';
    const image = ex.ghostty_kitty_graphics_image(view().getUint32(slots + SLOT.a, true), id);
    if (!image) return '';
    const data = T.GhosttyKittyGraphicsImageData.values;
    const number = (what) => (ex.ghostty_kitty_graphics_image_get(image, what, slots + SLOT.a) === OK ? view().getUint32(slots + SLOT.a, true) : 0);
    const width = number(data.WIDTH);
    const height = number(data.HEIGHT);
    const pointer = number(data.DATA_PTR);
    const length = number(data.DATA_LEN);
    if (ex.ghostty_kitty_graphics_image_get(image, data.FORMAT, slots + SLOT.a) !== OK) return '';
    const format = view().getInt32(slots + SLOT.a, true);
    const count = width * height;
    if (!pointer || count <= 0 || length <= 0) return '';

    const formats = T.GhosttyKittyImageFormat.values;
    const prefix = width + ',' + height + ',';
    if (format === formats.RGBA) return length >= count * 4 ? prefix + take(pointer, count * 4) : '';
    // The other forms are widened to four bytes a pixel: [bytes a pixel, where alpha is or -1, whether it is gray].
    const shape = format === formats.RGB ? [3, -1, false] : format === formats.GRAY ? [1, -1, true] : format === formats.GRAY_ALPHA ? [2, 1, true] : null;
    if (!shape || length < count * shape[0]) return '';
    const [step, alpha, gray] = shape;
    const source = bytes().subarray(pointer, pointer + count * step);
    const rgba = new Uint8Array(count * 4);
    for (let i = 0; i < count; i++) {
      const from = i * step;
      rgba[i * 4] = source[from];
      rgba[i * 4 + 1] = source[gray ? from : from + 1];
      rgba[i * 4 + 2] = source[gray ? from : from + 2];
      rgba[i * 4 + 3] = alpha < 0 ? 255 : source[from + alpha];
    }
    let out = prefix;
    for (let from = 0; from < rgba.length; from += 8192) out += String.fromCharCode.apply(null, rgba.subarray(from, from + 8192));
    return out;
  }

  /** The runs of the row the cells iterator points at, each field led by a separator. */
  function buildRow(cells, defaultForeground, defaultBackground) {
    const cellData = T.GhosttyRenderStateRowCellsData.values;
    const wideKind = T.GhosttyCellWide.values;
    const style = slots + SLOT.style;
    const styleField = (name) => bytes()[style + F('GhosttyStyle', name)] !== 0;
    const underlineKind = T.GhosttySgrUnderline.values;
    const colorTag = T.GhosttyStyleColorTag.values;
    /** The underline's own color, a palette color looked up in the palette this frame was read with, or -1 when it has none. */
    const underlineColorOf = () => {
      const color = style + F('GhosttyStyle', 'underline_color');
      const tag = view().getInt32(color + F('GhosttyStyleColor', 'tag'), true);
      const value = color + F('GhosttyStyleColor', 'value');
      if (tag === colorTag.RGB) return rgbAt(value);
      if (tag === colorTag.PALETTE) return rgbAt(slots + SLOT.colors + F('GhosttyRenderStateColors', 'palette') + bytes()[value] * 3);
      return -1;
    };
    const cellGet = (data, out) => check('render_state_row_cells_get', ex.ghostty_render_state_row_cells_get(cells, data, out));
    const cellTry = (data, out) => ex.ghostty_render_state_row_cells_get(cells, data, out) === OK;

    let text = '';
    let open = false; // the run being put together
    let run = null;
    const flush = () => {
      if (!open) return;
      open = false;
      text += US + run.column + US + run.cells + US + run.foreground + US + run.background + US + run.flags + US + run.underlineColor + US + run.text;
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
      let underlineColor = -1;
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
        const underline = view().getInt32(style + F('GhosttyStyle', 'underline'), true);
        if (underline !== underlineKind.NONE) {
          flags |= 256 * (underline === underlineKind.DOUBLE ? 2 : underline === underlineKind.CURLY ? 3 :
            underline === underlineKind.DOTTED ? 4 : underline === underlineKind.DASHED ? 5 : 1);
          underlineColor = underlineColorOf();
        }
        if (styleField('strikethrough')) flags |= 8;
        if (styleField('overline')) flags |= 16;
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
        foreground === run.foreground && background === run.background && flags === run.flags && underlineColor === run.underlineColor;
      if (!joins) {
        flush();
        open = true;
        run = { column: thisColumn, cells: 0, plain, foreground, background, flags, underlineColor, text: '' };
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

  // --- Selecting text ---
  //
  // The library turns presses and drags into a selection, and the terminal then keeps it:
  // see ghostty/src@jvm/GhosttySelection.kt, which this mirrors.

  let selecting = 0; // memory for what an event is told, laid out as SEL says
  const SEL = { point: 0, cell: 24, position: 40, geometry: 56, selection: 72, number: 104, options: 112 };
  /** How soon a press has to follow the last to make a double or triple click. */
  const REPEAT_CLICK_NS = 500000000n;
  const selections = new Map(); // by gesture: its events, and the terminal it was last used with

  function selectionNew() {
    check('selection_gesture_new', ex.ghostty_selection_gesture_new(0, slots + SLOT.a));
    const gesture = view().getUint32(slots + SLOT.a, true);
    const event = (type) => {
      check('selection_gesture_event_new', ex.ghostty_selection_gesture_event_new(0, slots + SLOT.a, E('GhosttySelectionGestureEventType', type)));
      return view().getUint32(slots + SLOT.a, true);
    };
    selections.set(gesture, { press: event('PRESS'), drag: event('DRAG'), release: event('RELEASE'), tick: event('AUTOSCROLL_TICK'), terminal: 0 });
    return gesture;
  }

  /** Frees a gesture. The terminal it was last used with must still be there. */
  function selectionFree(gesture) {
    const held = selections.get(gesture);
    if (!held) return;
    ex.ghostty_selection_gesture_free(gesture, held.terminal);
    for (const event of [held.press, held.drag, held.release, held.tick]) ex.ghostty_selection_gesture_event_free(event);
    selections.delete(gesture);
  }

  /**
   * Fills in where a position is: the cell under it, the position in pixels, and the
   * terminal's size. One outside the terminal counts as the nearest cell at its edge.
   */
  function selectionAim(held, terminal, x, y, cellWidth, cellHeight) {
    if (cellWidth <= 0 || cellHeight <= 0) return false;
    const columns = terminalColumns(terminal);
    const rows = terminalRows(terminal);
    if (columns <= 0 || rows <= 0) return false;
    held.terminal = terminal;
    const clamp = (value, most) => Math.min(Math.max(Math.trunc(value), 0), most);
    const point = selecting + SEL.point;
    bytes().fill(0, point, point + sizeOf('GhosttyPoint'));
    const coordinate = point + F('GhosttyPoint', 'value') + F('GhosttyPointValue', 'coordinate');
    view().setInt32(point + F('GhosttyPoint', 'tag'), E('GhosttyPointTag', 'VIEWPORT'), true);
    view().setUint16(coordinate + F('GhosttyPointCoordinate', 'x'), clamp(x / cellWidth, columns - 1), true);
    view().setUint32(coordinate + F('GhosttyPointCoordinate', 'y'), clamp(y / cellHeight, rows - 1), true);
    const cell = selecting + SEL.cell;
    view().setUint32(cell, sizeOf('GhosttyGridRef'), true);
    if (ex.ghostty_terminal_grid_ref(terminal, point, cell) !== OK) return false;
    view().setFloat64(selecting + SEL.position + F('GhosttySurfacePosition', 'x'), x, true);
    view().setFloat64(selecting + SEL.position + F('GhosttySurfacePosition', 'y'), y, true);
    const geometry = selecting + SEL.geometry;
    view().setUint32(geometry + F('GhosttySelectionGestureGeometry', 'columns'), columns, true);
    view().setUint32(geometry + F('GhosttySelectionGestureGeometry', 'cell_width'), cellWidth, true);
    view().setUint32(geometry + F('GhosttySelectionGestureGeometry', 'padding_left'), 0, true);
    view().setUint32(geometry + F('GhosttySelectionGestureGeometry', 'screen_height'), rows * cellHeight, true);
    return true;
  }
  const selectionSet = (event, option, value) =>
    check('selection_gesture_event_set', ex.ghostty_selection_gesture_event_set(event, E('GhosttySelectionGestureEventOption', option), value));
  /** The terminal takes the selection over from here, and moves it with its text. 0 for none. */
  const selectionInstall = (terminal, selection) =>
    check('terminal_set', ex.ghostty_terminal_set(terminal, E('GhosttyTerminalOption', 'SELECTION'), selection));
  /** Gives the gesture an event, and makes what it says is selected the terminal's selection. */
  function selectionApply(gesture, terminal, event, clearWhenNone) {
    const selection = selecting + SEL.selection;
    view().setUint32(selection, sizeOf('GhosttySelection'), true);
    const result = ex.ghostty_selection_gesture_event(gesture, terminal, event, selection);
    if (result === OK) selectionInstall(terminal, selection);
    else if (result !== E('GhosttyResult', 'NO_VALUE')) check('selection_gesture_event', result);
    else if (clearWhenNone) selectionInstall(terminal, 0);
  }
  function selectionWantsScrolling(gesture, terminal) {
    const number = selecting + SEL.number;
    return ex.ghostty_selection_gesture_get(gesture, terminal, E('GhosttySelectionGestureData', 'AUTOSCROLL'), number) === OK &&
      view().getInt32(number, true) !== E('GhosttySelectionGestureAutoscroll', 'NONE');
  }

  /** The left button went down. `timeMillis` tells a second press from a first. */
  function selectionPress(gesture, terminal, x, y, cellWidth, cellHeight, timeMillis) {
    const held = selections.get(gesture);
    if (!selectionAim(held, terminal, x, y, cellWidth, cellHeight)) return;
    const number = selecting + SEL.number;
    selectionSet(held.press, 'REF', selecting + SEL.cell);
    selectionSet(held.press, 'POSITION', selecting + SEL.position);
    view().setFloat64(number, cellWidth, true);
    selectionSet(held.press, 'REPEAT_DISTANCE', number);
    view().setBigUint64(number, BigInt(Math.round(timeMillis)) * 1000000n, true);
    selectionSet(held.press, 'TIME_NS', number);
    view().setBigUint64(number, REPEAT_CLICK_NS, true);
    selectionSet(held.press, 'REPEAT_INTERVAL_NS', number);
    selectionApply(gesture, terminal, held.press, true);
  }

  /** The pointer moved with the button held. Returns whether to start scrolling (`selectionAutoscroll`). */
  function selectionDrag(gesture, terminal, x, y, cellWidth, cellHeight) {
    const held = selections.get(gesture);
    if (!selectionAim(held, terminal, x, y, cellWidth, cellHeight)) return false;
    selectionSet(held.drag, 'REF', selecting + SEL.cell);
    selectionSet(held.drag, 'POSITION', selecting + SEL.position);
    selectionSet(held.drag, 'GEOMETRY', selecting + SEL.geometry);
    selectionApply(gesture, terminal, held.drag, true);
    return selectionWantsScrolling(gesture, terminal);
  }

  function selectionRelease(gesture, terminal, x, y, cellWidth, cellHeight) {
    const held = selections.get(gesture);
    if (!selectionAim(held, terminal, x, y, cellWidth, cellHeight)) return;
    selectionSet(held.release, 'REF', selecting + SEL.cell);
    selectionApply(gesture, terminal, held.release, false);
  }

  /** Scrolls a row towards the edge the pointer is held at, and extends the selection. Returns whether to go on. */
  function selectionAutoscroll(gesture, terminal, x, y, cellWidth, cellHeight) {
    const held = selections.get(gesture);
    if (!selectionAim(held, terminal, x, y, cellWidth, cellHeight)) return false;
    selectionSet(held.tick, 'VIEWPORT', selecting + SEL.point + F('GhosttyPoint', 'value') + F('GhosttyPointValue', 'coordinate'));
    selectionSet(held.tick, 'POSITION', selecting + SEL.position);
    selectionSet(held.tick, 'GEOMETRY', selecting + SEL.geometry);
    selectionApply(gesture, terminal, held.tick, false);
    return selectionWantsScrolling(gesture, terminal);
  }

  function selectionAll(terminal) {
    const selection = selecting + SEL.selection;
    view().setUint32(selection, sizeOf('GhosttySelection'), true);
    if (ex.ghostty_terminal_select_all(terminal, selection) === OK) selectionInstall(terminal, selection);
  }

  const selectionClear = (terminal) => selectionInstall(terminal, 0);

  /**
   * The selected text as it is copied, as UTF-8 bytes: a line the terminal wrapped is one
   * line, and the blanks after the end of a line are left out. Empty when nothing is selected.
   */
  function selectionText(terminal) {
    const options = selecting + SEL.options;
    bytes().fill(0, options, options + sizeOf('GhosttyTerminalSelectionFormatOptions'));
    view().setUint32(options, sizeOf('GhosttyTerminalSelectionFormatOptions'), true);
    view().setInt32(options + F('GhosttyTerminalSelectionFormatOptions', 'emit'), E('GhosttyFormatterFormat', 'PLAIN'), true);
    bytes()[options + F('GhosttyTerminalSelectionFormatOptions', 'unwrap')] = 1;
    bytes()[options + F('GhosttyTerminalSelectionFormatOptions', 'trim')] = 1;
    const result = ex.ghostty_terminal_selection_format_alloc(terminal, 0, options, slots + SLOT.a, slots + SLOT.b);
    if (result === E('GhosttyResult', 'NO_VALUE')) return '';
    check('terminal_selection_format_alloc', result);
    const pointer = view().getUint32(slots + SLOT.a, true);
    const length = view().getUint32(slots + SLOT.b, true);
    if (!pointer || !length) return '';
    try {
      return take(pointer, length);
    } finally {
      ex.ghostty_free(0, pointer, length);
    }
  }

  // --- Links ---
  //
  // See hyperlinkAt and rowWraps in ghostty/src@jvm/GhosttyTerminal.kt, which these mirror.

  /** A link's address longer than this is not read. */
  const MAX_HYPERLINK_BYTES = 8192;

  /** Points SEL.cell at a cell of the view. False if there is no such cell. */
  function cellAt(terminal, column, row) {
    if (column < 0 || row < 0 || column >= terminalColumns(terminal) || row >= terminalRows(terminal)) return false;
    const point = selecting + SEL.point;
    bytes().fill(0, point, point + sizeOf('GhosttyPoint'));
    const coordinate = point + F('GhosttyPoint', 'value') + F('GhosttyPointValue', 'coordinate');
    view().setInt32(point + F('GhosttyPoint', 'tag'), E('GhosttyPointTag', 'VIEWPORT'), true);
    view().setUint16(coordinate + F('GhosttyPointCoordinate', 'x'), column, true);
    view().setUint32(coordinate + F('GhosttyPointCoordinate', 'y'), row, true);
    const cell = selecting + SEL.cell;
    view().setUint32(cell, sizeOf('GhosttyGridRef'), true);
    return ex.ghostty_terminal_grid_ref(terminal, point, cell) === OK;
  }

  /**
   * Where a cell of the view leads, if its program made it a link (OSC 8): the address as
   * UTF-8 bytes, or empty when the cell is no link.
   */
  function terminalHyperlinkAt(terminal, column, row) {
    if (!cellAt(terminal, column, row)) return '';
    const cell = selecting + SEL.cell;
    const lengthOut = slots + SLOT.a;
    // Asked twice: first for how long the address is, then for the address.
    if (ex.ghostty_grid_ref_hyperlink_uri(cell, 0, 0, lengthOut) !== E('GhosttyResult', 'OUT_OF_SPACE')) return '';
    const length = view().getUint32(lengthOut, true);
    if (length <= 0 || length > MAX_HYPERLINK_BYTES) return '';
    const buffer = room(length);
    if (ex.ghostty_grid_ref_hyperlink_uri(cell, buffer, length, lengthOut) !== OK) return '';
    return take(buffer, view().getUint32(lengthOut, true));
  }

  /** Whether a row of the view ran out of room and goes on in the row below it. */
  function terminalRowWraps(terminal, row) {
    if (!cellAt(terminal, 0, row)) return false;
    const rowOut = slots + SLOT.a; // a GhosttyRow, which is 64 bits
    if (ex.ghostty_grid_ref_row(selecting + SEL.cell, rowOut) !== OK) return false;
    const wraps = slots + SLOT.b;
    return ex.ghostty_row_get(view().getBigUint64(rowOut, true), E('GhosttyRowData', 'WRAP'), wraps) === OK && bytes()[wraps] !== 0;
  }

  // --- Search ---
  //
  // See ghostty/src@jvm/GhosttySearch.kt, which this mirrors.

  const searches = new Map(); // by search: its terminal, and room for the matches near the view
  /** How much looking one `searchAdvance` does before it lets something else happen. */
  const SEARCH_MAX_TICKS = 64;
  const searchGet = (search, data, out) => ex.ghostty_search_get(search, E('GhosttySearchData', data), out);

  function searchNew(terminal) {
    check('search_new', ex.ghostty_search_new(0, slots + SLOT.a, terminal));
    const search = view().getUint32(slots + SLOT.a, true);
    searches.set(search, { terminal, matches: 0, capacity: 0 });
    return search;
  }

  /** Frees a search. Its terminal must still be there. */
  function searchFree(search) {
    const held = searches.get(search);
    if (!held) return;
    ex.ghostty_search_free(search);
    if (held.matches) ex.ghostty_wasm_free(held.matches, held.capacity * sizeOf('GhosttySelection'));
    searches.delete(search);
  }

  /** What to look for, as UTF-8 bytes. Changing it starts over; empty looks for nothing. */
  function searchSetNeedle(search, binary) {
    if (!binary.length) {
      check('search_set', ex.ghostty_search_set(search, E('GhosttySearchOption', 'NEEDLE'), 0));
      return;
    }
    const data = put(binary);
    const needle = slots + SLOT.a;
    view().setUint32(needle + F('GhosttyString', 'ptr'), data, true);
    view().setUint32(needle + F('GhosttyString', 'len'), binary.length, true);
    check('search_set', ex.ghostty_search_set(search, E('GhosttySearchOption', 'NEEDLE'), needle));
  }

  function searchStatus(search) {
    check('search_get', searchGet(search, 'STATUS', slots + SLOT.a));
    return view().getInt32(slots + SLOT.a, true);
  }

  /** Takes in what the terminal holds now and looks through some more of it. Returns whether it has been through it all. */
  function searchAdvance(search) {
    check('search_feed', ex.ghostty_search_feed(search));
    const running = E('GhosttySearchStatus', 'RUNNING');
    for (let ticks = 0; ticks < SEARCH_MAX_TICKS && searchStatus(search) === running; ticks++) {
      check('search_tick', ex.ghostty_search_tick(search, 0));
    }
    return searchStatus(search) === E('GhosttySearchStatus', 'COMPLETE');
  }

  /** Selects the next match, towards older output, or the previous. False when there are none. */
  function searchSelect(search, forward) {
    const result = ex.ghostty_search_set(search, E('GhosttySearchOption', forward ? 'SELECT_NEXT' : 'SELECT_PREV'), 0);
    if (result === E('GhosttyResult', 'NO_VALUE')) return false;
    check('search_set', result);
    return true;
  }

  function searchTotal(search) {
    check('search_get', searchGet(search, 'TOTAL_MATCHES', slots + SLOT.a));
    return view().getUint32(slots + SLOT.a, true);
  }

  /** Which match is selected, counting from 0 for the newest, or -1. */
  function searchSelected(search) {
    return searchGet(search, 'SELECTED_INDEX', slots + SLOT.a) === OK ? view().getUint32(slots + SLOT.a, true) : -1;
  }

  /** The column and row of the view a place in the terminal is at, or null when it is out of view. */
  function searchInView(terminal, place) {
    const coordinate = slots + SLOT.b;
    if (ex.ghostty_terminal_point_from_grid_ref(terminal, place, E('GhosttyPointTag', 'VIEWPORT'), coordinate) !== OK) return null;
    return [view().getUint16(coordinate + F('GhosttyPointCoordinate', 'x'), true), view().getUint32(coordinate + F('GhosttyPointCoordinate', 'y'), true)];
  }

  /**
   * A match's cells in the view, "row,first column,last column" for each row, with ";"
   * between. As cellsBetween in ghostty/src/SearchCells.kt: an end out of view is taken to
   * be above the top (the first) or below the bottom (the last).
   */
  function searchCells(terminal, match, columns, rows) {
    let first = searchInView(terminal, match + F('GhosttySelection', 'start'));
    let last = searchInView(terminal, match + F('GhosttySelection', 'end'));
    if (first && last && (first[1] > last[1] || (first[1] === last[1] && first[0] > last[0]))) [first, last] = [last, first];
    if (first && first[1] >= rows) first = null;
    if (last && last[1] >= rows) last = null;
    if (!first && !last) return '';
    const [fromColumn, fromRow] = first || [0, 0];
    const [toColumn, toRow] = last || [columns - 1, rows - 1];
    if (toRow < fromRow || (toRow === fromRow && toColumn < fromColumn)) return '';
    const cells = [];
    for (let row = fromRow; row <= toRow; row++) {
      cells.push(row + ',' + (row === fromRow ? Math.min(fromColumn, columns - 1) : 0) + ',' + (row === toRow ? Math.min(toColumn, columns - 1) : columns - 1));
    }
    return cells.join(';');
  }

  /**
   * Where the matches are in the view, as of the last `searchAdvance`: the cells of every
   * match but the selected one, then US, then the selected one's.
   */
  function searchHighlights(search) {
    const held = searches.get(search);
    const terminal = held.terminal;
    const columns = terminalColumns(terminal);
    const rows = terminalRows(terminal);
    const size = sizeOf('GhosttySelection');
    const one = selecting + SEL.selection;
    view().setUint32(one, size, true);
    const selected = searchGet(search, 'SELECTED_MATCH', one) === OK ? searchCells(terminal, one, columns, rows) : '';

    const buffer = slots + SLOT.c;
    let count = 0;
    for (;;) {
      for (let i = 0; i < held.capacity; i++) view().setUint32(held.matches + i * size, size, true);
      view().setUint32(buffer + F('GhosttySelectionBuffer', 'ptr'), held.matches, true);
      view().setUint32(buffer + F('GhosttySelectionBuffer', 'cap'), held.capacity, true);
      view().setUint32(buffer + F('GhosttySelectionBuffer', 'len'), 0, true);
      const result = searchGet(search, 'VIEWPORT_MATCHES', buffer);
      const length = view().getUint32(buffer + F('GhosttySelectionBuffer', 'len'), true);
      if (result === OK) { count = length; break; }
      if (result !== E('GhosttyResult', 'OUT_OF_SPACE') || length <= held.capacity) break;
      if (held.matches) ex.ghostty_wasm_free(held.matches, held.capacity * size);
      held.capacity = Math.max(length * 2, 64);
      held.matches = ex.ghostty_wasm_alloc(held.capacity * size);
    }
    const others = [];
    for (let i = 0; i < count; i++) {
      const cells = searchCells(terminal, held.matches + i * size, columns, rows);
      // The selected match is among them, and is marked in its own way.
      if (cells && cells !== selected) others.push(cells);
    }
    return others.join(';') + US + selected;
  }

  globalThis.GhosttyGlue = {
    searchNew, searchFree, searchSetNeedle, searchAdvance, searchSelect, searchTotal, searchSelected, searchHighlights,
    terminalHyperlinkAt, terminalRowWraps,
    selectionNew, selectionFree, selectionPress, selectionDrag, selectionRelease, selectionAutoscroll, selectionAll, selectionClear, selectionText,
    init, terminalNew, terminalRestore, terminalFree, terminalWrite, terminalResize, terminalColumns, terminalRows,
    terminalWidthPx, terminalHeightPx, terminalSetColors, terminalScrollBy, terminalViewportTop, terminalScrollByPrompts, terminalScrollToBottom, terminalBracketedPaste,
    pasteEncode, rendererNew, rendererFree, rendererInvalidate, rendererOverscan, render, imageRead, mouseNew, mouseFree, mouseEncode,
  };
})();
