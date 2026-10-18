const [match, expression] = process.argv.slice(2);
if (!match || !expression) {
  console.error('usage: node cdp-eval.mjs <url substring> <expression>');
  process.exit(2);
}

const targets = await (await fetch('http://127.0.0.1:9230/json')).json();
const target = targets.find(t => t.type === 'page' && t.url.includes(match));
if (!target) {
  console.error('no page matches; pages are:\n' + targets.map(t => '  ' + t.url).join('\n'));
  process.exit(1);
}

const socket = new WebSocket(target.webSocketDebuggerUrl);
socket.addEventListener('open', () => {
  socket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
});
socket.addEventListener('message', event => {
  const reply = JSON.parse(event.data);
  if (reply.id !== 1) return;
  const result = reply.result?.result;
  console.log(reply.result?.exceptionDetails ? 'exception: ' + JSON.stringify(reply.result.exceptionDetails) : JSON.stringify(result?.value ?? result));
  socket.close();
});
