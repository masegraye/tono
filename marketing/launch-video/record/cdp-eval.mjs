// node cdp-eval.mjs PORT URL-SUBSTRING EXPRESSION  -> prints the value; "pages" as the expression lists pages.
const [port, match, expression] = process.argv.slice(2);
const pages = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).filter((t) => t.type === 'page');
if (expression === 'pages') { console.log(pages.map((p) => p.url).join('\n')); process.exit(0); }
const page = pages.find((p) => match.startsWith('=') ? p.url === match.slice(1) : p.url.includes(match));
if (!page) { console.log('no such page; open: ' + pages.map((p) => p.url).join(', ')); process.exit(1); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r));
ws.addEventListener('message', (m) => { const msg = JSON.parse(m.data); if (msg.id === 1) { const v = msg.result?.result?.value; console.log(typeof v === 'string' ? v : JSON.stringify(v ?? msg.result?.exceptionDetails?.exception?.description ?? null)); ws.close(); process.exit(0); } });
ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true, userGesture: true } }));
setTimeout(() => process.exit(2), 15000);
