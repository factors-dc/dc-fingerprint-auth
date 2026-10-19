// Drives window.FingerprintAuth inside the running app's WebView via Chrome DevTools Protocol.
// Usage: node fpcheck.mjs <serial> <step> [fingerId]
//   steps: avail | encrypt | decrypt | delete | cancel-encrypt | full
// Needs a debuggable build (cordova debug build) and an emulator ("adb emu finger touch").
import { execFileSync } from 'node:child_process';

const [serial, step = 'full', finger = '1'] = process.argv.slice(2);
const ADB = process.env.ADB || 'adb';
const adb = (...a) => execFileSync(ADB, ['-s', serial, ...a], { encoding: 'utf8' });
const sleep = ms => new Promise(r => setTimeout(r, ms));

const sockets = adb('shell', 'cat', '/proc/net/unix').match(/webview_devtools_remote_\d+/g);
if (!sockets) throw new Error('No WebView devtools socket - is the app running (debug build)?');
const port = 9300 + Math.floor(Math.random() * 500);
adb('forward', `tcp:${port}`, `localabstract:${sockets[sockets.length - 1]}`);
const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = targets.find(t => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r));
let id = 0;
const pending = new Map();
ws.addEventListener('message', ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
});
const evaluate = expr => new Promise(r => {
  const i = ++id; pending.set(i, r);
  ws.send(JSON.stringify({ id: i, method: 'Runtime.evaluate', params: { expression: expr, awaitPromise: true, returnByValue: true } }));
}).then(m => m.result?.result?.value ?? m);

const CFG = `{clientId:'fpcheck', username:'tester', disableBackup:true, maxAttempts:5, locale:'da', userAuthRequired:true}`;
const call = (fn, extra = '') => `new Promise(res => { const t=setTimeout(()=>res({timeout:true}),20000);
  FingerprintAuth.${fn}(${extra ? `Object.assign(${CFG}, ${extra})` : CFG},
    r => {clearTimeout(t); res({ok:r})}, e => {clearTimeout(t); res({err:e})}); })`;
const avail = () => evaluate(`new Promise(res => FingerprintAuth.isAvailable(r=>res({ok:r}), e=>res({err:e})))`);

// Run a prompting call and touch the sensor (or press Back) once the prompt is up.
async function prompted(expr, action) {
  const p = evaluate(expr);
  await sleep(2500);
  if (action === 'touch') adb('emu', 'finger', 'touch', finger);
  if (action === 'back') adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  return p;
}

const out = {};
const steps = step === 'full' ? ['avail', 'encrypt', 'decrypt', 'cancel-encrypt', 'decrypt', 'delete', 'decrypt-after-delete'] : [step];
for (const s of steps) {
  let r;
  if (s === 'avail') r = await avail();
  if (s === 'encrypt') {
    r = await prompted(call('encrypt', `{password:'{"pin":"1234"}', dialogMessage:'Aktivér biometri'}`), 'touch');
    if (r.ok?.token) await evaluate(`localStorage.fpcheckToken=${JSON.stringify(r.ok.token)}`);
  }
  if (s === 'cancel-encrypt') r = await prompted(call('encrypt', `{password:'other', dialogMessage:'Annullér mig'}`), 'back');
  if (s === 'decrypt' || s === 'decrypt-after-delete') {
    const prompt = s === 'decrypt' ? 'touch' : null;
    r = await prompted(call('decrypt', `{token: localStorage.fpcheckToken, dialogMessage:'Log på'}`), prompt);
  }
  if (s === 'delete') r = await evaluate(call('delete'));
  console.log(s.padEnd(22), JSON.stringify(r));
  out[s] = r;
}
ws.close();
adb('forward', '--remove', `tcp:${port}`);
