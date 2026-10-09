// Checks the roster parser (parseRoster in web/schichtpuls.html) on synthetic OCR data.
// Run: node tools/test_parser.cjs
const fs = require('fs'), path = require('path');
const html = fs.readFileSync(path.join(__dirname, '..', 'web', 'schichtpuls.html'), 'utf8');
const start = html.indexOf('function parseRoster('), end = html.indexOf('const ocrWaiters');
if (start < 0 || end < 0) throw new Error('parseRoster not found in web/schichtpuls.html');
const parseRoster = new Function(html.slice(start, end) + '; return parseRoster;')();

const codes = [{code:'F',kind:'work'},{code:'S',kind:'work'},{code:'N',kind:'work'},{code:'Z',kind:'work'},{code:'FB',kind:'work'},{code:'X',kind:'free'},{code:'U',kind:'absent'},{code:'K',kind:'absent'}];
const pool = ['F','S','N','X','U','Z','FB','K'];
let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;

function roster({days = 30, tilt = 0, dropHeader = 0, merge = false, confuse = false}){
  const people = ['Schmidt, A.','Weber, M.','Wagner, T.','Becker, L.'], truth = {}, els = [];
  const put = (t, x, y, w = 24, h = 18) => {
    const a = tilt * Math.PI / 180, cx = x + w/2, cy = y + h/2;
    els.push({t, x: cx*Math.cos(a) - cy*Math.sin(a) - w/2, y: cx*Math.sin(a) + cy*Math.cos(a) - h/2, w, h});
  };
  const colX = d => 220 + (d - 1) * 42;
  for (let d = 1; d <= days; d++) if (rnd() > dropHeader) put(String(d), colX(d), 100);
  people.forEach((p, k) => {
    const y = 140 + k * 32, [last, first] = p.split(' ');
    put(last, 20, y, 80); put(first, 105, y, 24);
    const row = Array.from({length: days}, () => pool[Math.floor(rnd() * pool.length)]);
    truth[p] = row;
    for (let d = 1; d <= days; d++){
      let c = row[d - 1];
      if (merge && d < days && d % 7 === 0 && c.length === 1 && row[d].length === 1){ put(c + row[d], colX(d), y, 66); d++; continue; }
      if (confuse && c === 'S' && d % 5 === 0) c = '5';
      put(c, colX(d), y, c.length > 1 ? 30 : 16);
    }
  });
  return {ocr: {els}, truth};
}

let failed = 0;
for (const [label, opts] of [
  ['gerade', {}], ['3 Grad schief', {tilt: 3}], ['-4 Grad schief', {tilt: -4}],
  ['20% Tageszahlen fehlen', {dropHeader: 0.2}], ['zusammengeklebte Kuerzel', {merge: true}],
  ['5 statt S gelesen', {confuse: true}], ['31 Tage', {days: 31}],
]){
  const {ocr, truth} = roster(opts), days = opts.days || 30;
  const r = parseRoster(ocr, {name: 'Weber, M.', days, codes});
  const ok = r.entries.filter((e, i) => e.code === truth['Weber, M.'][i]).length;
  if (ok !== days) failed++;
  console.log(`${ok === days ? 'OK  ' : 'FAIL'} ${label.padEnd(26)} ${ok}/${days}`);
}
process.exit(failed ? 1 : 0);
