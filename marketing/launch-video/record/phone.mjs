// Drives the web Tono as a phone would, for the film: the page is told it is a phone, and is
// touched and typed into the way a phone's browser does it. Run beside the recorder.
//   node phone.mjs PORT look            a picture of the page as a phone, and nothing more
//   node phone.mjs PORT take 'STEPS'    the take; STEPS is JSON: [{tap:[x,y]}, {type:'text'}, {wait:ms}]
const [port, action] = process.argv.slice(2);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pages = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).filter((t) => t.type === 'page');
const page = pages.find((p) => /^https?:\/\/[^/]+\/$/.test(p.url) && !p.url.startsWith('chrome'));
if (!page) { console.error('the app page is not open: ' + pages.map((p) => p.url).join(', ')); process.exit(1); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r));
let id = 0; const waiting = new Map();
ws.addEventListener('message', (m) => { const msg = JSON.parse(m.data); if (msg.id && waiting.has(msg.id)) { waiting.get(msg.id)(msg.result ?? msg.error); waiting.delete(msg.id); } });
const send = (method, params = {}) => new Promise((r) => { waiting.set(++id, r); ws.send(JSON.stringify({ id, method, params })); });
const tap = async (x, y, hold = 60) => {
  await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  await sleep(hold);
  await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
};
// A keyboard on a screen edits the focused field; it presses no keys.
const type = async (text, each = 70) => { for (const ch of text) { await send('Input.insertText', { text: ch }); await sleep(each); } };

const WIDTH = 400, HEIGHT = 820;
const { existsSync, writeFileSync, rmSync } = await import('node:fs');
const dir = process.env.DEMO ?? '/tmp/tono-demo';
const shoot = async (name) => { const shot = await send('Page.captureScreenshot', { format: 'png' }); writeFileSync(`${dir}/${name}.png`, Buffer.from(shot.data, 'base64')); };
const until = async (file) => { while (!existsSync(`${dir}/${file}`)) await sleep(100); };

// Being a phone lasts only while this stays connected, so the whole take is one run: it
// makes the page a phone, says so (phone-ready), waits to be told the recorder is running
// (phone-go), does the steps, says it has (phone-done), and waits to be let go (phone-release).
for (const f of ['phone-ready', 'phone-go', 'phone-done', 'phone-release']) rmSync(`${dir}/${f}`, { force: true });
await send('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: HEIGHT, deviceScaleFactor: 0, mobile: true });
await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
await send('Page.reload');
await sleep(14000);
await shoot('phone-prep');
writeFileSync(`${dir}/phone-ready`, '');
if (action === 'look') { ws.close(); process.exit(0); }
await until('phone-go');
// Positions are in the page's own points.
for (const step of JSON.parse(process.argv[4] ?? '[]')) {
  if (step.tap) await tap(step.tap[0], step.tap[1]);
  if (step.type) await type(step.type, step.each);
  await sleep(step.wait ?? 800);
}
writeFileSync(`${dir}/phone-done`, '');
await until('phone-release');
await send('Emulation.clearDeviceMetricsOverride');
await send('Emulation.setTouchEmulationEnabled', { enabled: false });
ws.close();
process.exit(0);
