/* Liqstorm Dienstplan: photo roster recognition + editable personal shift calendar.
   Everything is stored locally in this browser (localStorage). */

const SDK_URL = 'https://cdn.jsdelivr.net/npm/@anthropic-ai/sdk@0.127.0/+esm';
const TESSERACT_URL = 'https://cdn.jsdelivr.net/npm/tesseract.js@7.0.0/dist/tesseract.min.js';
const STORE_KEY = 'liqstorm.dienstplan.v1';

const MONTHS = ['Januar','Februar','März','April','Mai','Juni','Juli','August','September','Oktober','November','Dezember'];
const DOW = ['Mo','Di','Mi','Do','Fr','Sa','So'];
const DOW_LONG = ['Montag','Dienstag','Mittwoch','Donnerstag','Freitag','Samstag','Sonntag'];

const DEFAULT_CODES = [
  {code:'F', label:'Frühdienst',  start:'06:00', end:'14:00', color:'#38bdf8', kind:'work'},
  {code:'S', label:'Spätdienst',  start:'14:00', end:'22:00', color:'#ffc94d', kind:'work'},
  {code:'N', label:'Nachtdienst', start:'22:00', end:'06:00', color:'#a78bfa', kind:'work'},
  {code:'T', label:'Tagdienst',   start:'08:00', end:'16:30', color:'#34d399', kind:'work'},
  {code:'X', label:'Frei',        start:'',      end:'',      color:'#64748b', kind:'off'},
  {code:'U', label:'Urlaub',      start:'',      end:'',      color:'#fb923c', kind:'off'},
  {code:'K', label:'Krank',       start:'',      end:'',      color:'#f43f5e', kind:'off'},
];

/* ---------- state ---------- */
const state = load();
const now = new Date();
let viewY = now.getFullYear(), viewM = now.getMonth();
let photos = [];          // {id, name, dataUrl, base64, mediaType}
let scanResult = null;    // {year, month, entries:{day:{code,start,end,uncertain}}, notes}
let editKey = null, editCode = '';

function load(){
  let s = null;
  try { s = JSON.parse(localStorage.getItem(STORE_KEY)); } catch {}
  s = s && typeof s === 'object' ? s : {};
  return {
    settings: Object.assign({name:'', apiKey:'', model:'claude-opus-5-5', mode:'claude'}, s.settings || {}),
    codes: Array.isArray(s.codes) && s.codes.length ? s.codes : DEFAULT_CODES.map(c => ({...c})),
    days: s.days && typeof s.days === 'object' ? s.days : {},
  };
}
function save(){
  try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); }
  catch { toast('Speichern fehlgeschlagen: Speicher des Browsers voll oder gesperrt.'); }
}

/* ---------- helpers ---------- */
const $ = s => document.querySelector(s);
const pad = n => String(n).padStart(2, '0');
const keyOf = (y, m, d) => `${y}-${pad(m + 1)}-${pad(d)}`;
const daysIn = (y, m) => new Date(y, m + 1, 0).getDate();
const dowIdx = (y, m, d) => (new Date(y, m, d).getDay() + 6) % 7; // Monday = 0
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const codeDef = code => state.codes.find(c => c.code.toLowerCase() === String(code || '').toLowerCase());
const colorOf = code => codeDef(code)?.color || '#94a3b8';
const isTime = t => /^\d{2}:\d{2}$/.test(t || '');

function toast(msg, ms = 2600){
  const t = $('#toast'); t.textContent = msg; t.classList.add('show');
  clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.remove('show'), ms);
}

/** effective start/end of a stored day entry (override or code default) */
function timesOf(entry){
  const def = codeDef(entry.code) || {};
  return { start: entry.start || def.start || '', end: entry.end || def.end || '' };
}
function hoursOf(entry){
  const def = codeDef(entry.code);
  if (def && def.kind === 'off') return 0;
  const {start, end} = timesOf(entry);
  if (!isTime(start) || !isTime(end)) return 0;
  const [a, b] = [start, end].map(t => { const [h, m] = t.split(':').map(Number); return h * 60 + m; });
  let d = b - a; if (d <= 0) d += 24 * 60;
  return d / 60;
}
const fmtH = h => (Math.round(h * 100) / 100).toLocaleString('de-DE', {maximumFractionDigits: 2});

/* ---------- navigation ---------- */
function show(view){
  document.querySelectorAll('.view').forEach(v => v.classList.toggle('on', v.id === 'view-' + view));
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('on', t.dataset.view === view));
  if (view === 'plan') renderPlan();
  if (view === 'codes') renderCodes();
  if (view === 'settings') renderSettings();
  if (view === 'scan') renderScanForm();
  scrollTo({top: 0, behavior: 'smooth'});
}
document.querySelectorAll('.tab').forEach(t => t.addEventListener('click', () => show(t.dataset.view)));

function renderModePill(){
  const p = $('#modePill'), s = state.settings;
  if (s.mode === 'claude' && s.apiKey){ p.textContent = 'KI aktiv'; p.className = 'pill ok'; }
  else if (s.mode === 'claude'){ p.textContent = 'Kein API-Schlüssel'; p.className = 'pill'; }
  else { p.textContent = 'Offline-Modus'; p.className = 'pill warn'; }
}

/* ---------- plan view ---------- */
function renderPlan(){
  $('#monthTitle').innerHTML = `${MONTHS[viewM]}<small>${viewY}</small>`;
  const n = daysIn(viewY, viewM), first = dowIdx(viewY, viewM, 1);
  const todayKey = keyOf(now.getFullYear(), now.getMonth(), now.getDate());
  let html = DOW.map((d, i) => `<div class="dow${i > 4 ? ' we' : ''}">${d}</div>`).join('');
  for (let i = 0; i < first; i++) html += '<div class="day pad"></div>';
  let hours = 0, work = 0, off = 0, absent = 0;
  const used = new Set();
  for (let d = 1; d <= n; d++){
    const k = keyOf(viewY, viewM, d), e = state.days[k], wi = dowIdx(viewY, viewM, d);
    let inner = `<span class="n">${d}</span>`, style = '';
    if (e && e.code){
      const def = codeDef(e.code), c = colorOf(e.code), {start, end} = timesOf(e);
      used.add(e.code);
      style = `style="--c:${c}"`;
      inner += `<span class="code" style="--c:${c}">${esc(e.code)}</span>`;
      if (isTime(start) && isTime(end) && (!def || def.kind !== 'off')) inner += `<span class="t">${start}–${end}</span>`;
      else if (def) inner += `<span class="t">${esc(def.label)}</span>`;
      const h = hoursOf(e); hours += h;
      if (h > 0) work++;
      else if (def && def.kind === 'off' && /^x$|frei/i.test(def.code + ' ' + def.label)) off++;
      else if (def && def.kind === 'off') absent++;
      if (e.note) inner += '<i class="flag note"></i>';
      if (e.src === 'manual') inner += '<i class="flag edit" title="von Hand geändert"></i>';
    }
    html += `<button class="day${wi > 4 ? ' we' : ''}${k === todayKey ? ' today' : ''}" data-k="${k}" ${style} aria-label="${d}. ${MONTHS[viewM]}${e && e.code ? ', ' + esc(e.code) : ''}">${inner}</button>`;
  }
  $('#cal').innerHTML = html;
  $('#cal').querySelectorAll('.day[data-k]').forEach(b => b.addEventListener('click', () => openEditor(b.dataset.k)));

  $('#stats').innerHTML = `
    <div class="stat h"><b>${fmtH(hours)}</b><small>Stunden</small></div>
    <div class="stat w"><b>${work}</b><small>Dienste</small></div>
    <div class="stat o"><b>${off}</b><small>Frei</small></div>
    <div class="stat u"><b>${absent}</b><small>Urlaub / Krank</small></div>`;

  $('#legend').innerHTML = state.codes.filter(c => used.has(c.code))
    .map(c => `<span style="--c:${c.color}"><i></i>${esc(c.code)} · ${esc(c.label)}</span>`).join('');

  renderUpcoming();
}

function renderUpcoming(){
  const today = keyOf(now.getFullYear(), now.getMonth(), now.getDate());
  const items = Object.keys(state.days).filter(k => k >= today && state.days[k]?.code).sort().slice(0, 6);
  if (!items.length){
    $('#upcoming').innerHTML = `<li class="empty" style="display:block;border:0"><b>Noch keine Dienste</b>Scanne deinen Plan oder tippe auf einen Tag im Kalender.</li>`;
    return;
  }
  $('#upcoming').innerHTML = items.map(k => {
    const e = state.days[k], [y, m, d] = k.split('-').map(Number), def = codeDef(e.code), {start, end} = timesOf(e);
    const wd = DOW[dowIdx(y, m - 1, d)], label = def ? def.label : 'Unbekannt';
    const time = isTime(start) && isTime(end) && def?.kind !== 'off' ? `${start} – ${end}` : '';
    return `<li><span class="d">${pad(d)}.${pad(m)}.<small>${k === today ? 'HEUTE' : wd.toUpperCase()}</small></span>
      <span class="c" style="--c:${colorOf(e.code)}">${esc(e.code)}</span>
      <span class="l">${esc(label)}${time ? ` · ${time}` : ''}${e.note ? `<small>${esc(e.note)}</small>` : ''}</span></li>`;
  }).join('');
}

$('#prevM').addEventListener('click', () => { if (--viewM < 0){ viewM = 11; viewY--; } renderPlan(); });
$('#nextM').addEventListener('click', () => { if (++viewM > 11){ viewM = 0; viewY++; } renderPlan(); });
$('#todayBtn').addEventListener('click', () => { viewY = now.getFullYear(); viewM = now.getMonth(); renderPlan(); });

/* swipe between months on touch devices */
(() => {
  let x0 = null, y0 = null;
  const cal = $('#cal');
  cal.addEventListener('touchstart', e => { x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; }, {passive: true});
  cal.addEventListener('touchend', e => {
    if (x0 === null) return;
    const dx = e.changedTouches[0].clientX - x0, dy = e.changedTouches[0].clientY - y0;
    x0 = null;
    if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) (dx < 0 ? $('#nextM') : $('#prevM')).click();
  });
})();

/* ---------- day editor ---------- */
function openEditor(k){
  editKey = k;
  const [y, m, d] = k.split('-').map(Number), e = state.days[k] || {};
  editCode = e.code || '';
  $('#sheetDow').textContent = DOW_LONG[dowIdx(y, m - 1, d)];
  $('#sheetTitle').textContent = `${d}. ${MONTHS[m - 1]} ${y}`;
  $('#edNote').value = e.note || '';
  renderCodePick();
  const t = timesOf(e); $('#edStart').value = t.start; $('#edEnd').value = t.end;
  $('#sheet').classList.add('open');
}
function renderCodePick(){
  $('#codepick').innerHTML = state.codes.map(c =>
    `<button type="button" data-c="${esc(c.code)}" class="${c.code === editCode ? 'on' : ''}" style="--c:${c.color}">${esc(c.code)}<small>${esc(c.label)}</small></button>`).join('');
  $('#codepick').querySelectorAll('button').forEach(b => b.addEventListener('click', () => {
    editCode = b.dataset.c;
    const def = codeDef(editCode);
    $('#edStart').value = def?.start || ''; $('#edEnd').value = def?.end || '';
    renderCodePick();
  }));
}
function closeEditor(){ $('#sheet').classList.remove('open'); editKey = null; }
$('#sheetClose').addEventListener('click', closeEditor);
$('#sheet').addEventListener('click', e => { if (e.target.id === 'sheet') closeEditor(); });
addEventListener('keydown', e => { if (e.key === 'Escape' && $('#sheet').classList.contains('open')) closeEditor(); });
$('#edSave').addEventListener('click', () => {
  const note = $('#edNote').value.trim();
  if (!editCode && !note){ delete state.days[editKey]; }
  else {
    const def = codeDef(editCode), s = $('#edStart').value, en = $('#edEnd').value;
    const entry = {code: editCode, src: 'manual'};
    if (s && s !== (def?.start || '')) entry.start = s;
    if (en && en !== (def?.end || '')) entry.end = en;
    if (note) entry.note = note;
    state.days[editKey] = entry;
  }
  save(); closeEditor(); renderPlan(); toast('Gespeichert');
});
$('#edClear').addEventListener('click', () => { delete state.days[editKey]; save(); closeEditor(); renderPlan(); toast('Eintrag gelöscht'); });

/* ---------- codes view ---------- */
function renderCodes(){
  $('#codes').innerHTML = state.codes.map((c, i) => `
    <div class="cd" data-i="${i}" style="--c:${c.color}">
      <input type="color" class="c-col" value="${esc(c.color)}" aria-label="Farbe">
      <input type="text" class="k c-key" value="${esc(c.code)}" maxlength="6" aria-label="Kürzel">
      <input type="text" class="c-label" value="${esc(c.label)}" aria-label="Bezeichnung">
      <input type="time" class="c-s" value="${esc(c.start)}" aria-label="Beginn">
      <input type="time" class="c-e" value="${esc(c.end)}" aria-label="Ende">
      <select class="c-kind" aria-label="Art"><option value="work"${c.kind !== 'off' ? ' selected' : ''}>Dienst</option><option value="off"${c.kind === 'off' ? ' selected' : ''}>Abwesend</option></select>
      <button class="rm" type="button" aria-label="Entfernen">✕</button>
    </div>`).join('');
  $('#codes').querySelectorAll('.cd').forEach(row => {
    row.querySelector('.c-col').addEventListener('input', e => row.style.setProperty('--c', e.target.value));
    row.querySelector('.rm').addEventListener('click', () => { readCodes(); state.codes.splice(+row.dataset.i, 1); renderCodes(); });
  });
}
function readCodes(){
  state.codes = [...$('#codes').querySelectorAll('.cd')].map(r => {
    const start = r.querySelector('.c-s').value, end = r.querySelector('.c-e').value;
    return {
      code: r.querySelector('.c-key').value.trim(),
      label: r.querySelector('.c-label').value.trim() || r.querySelector('.c-key').value.trim(),
      start, end, color: r.querySelector('.c-col').value,
      kind: r.querySelector('.c-kind').value,
    };
  }).filter(c => c.code);
}
$('#addCode').addEventListener('click', () => {
  readCodes();
  state.codes.push({code:'', label:'', start:'', end:'', color:'#94a3b8', kind:'work'});
  renderCodes();
  const keys = $('#codes').querySelectorAll('.c-key'); keys[keys.length - 1].focus();
});
$('#saveCodes').addEventListener('click', () => {
  readCodes();
  const seen = new Set(), dup = state.codes.find(c => { const k = c.code.toLowerCase(); if (seen.has(k)) return true; seen.add(k); return false; });
  if (dup){ toast(`Kürzel „${dup.code}“ ist doppelt.`); return; }
  save(); renderCodes(); toast('Dienstarten gespeichert');
});

/* ---------- settings view ---------- */
function renderSettings(){
  const s = state.settings;
  $('#setName').value = s.name; $('#setKey').value = s.apiKey; $('#setModel').value = s.model;
  setModeUI(s.mode);
}
function setModeUI(mode){
  $('#modeSeg').querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.mode === mode));
  $('#claudeBox').hidden = mode !== 'claude';
  $('#modeHint').textContent = mode === 'claude'
    ? 'Claude liest das Foto wie ein Mensch: findet deine Zeile, ordnet Kürzel den Tagen zu und markiert unsichere Stellen. Braucht Internet und einen API-Schlüssel (kostet pro Scan wenige Cent).'
    : 'Texterkennung direkt im Browser, kostenlos und ohne Schlüssel. Bei Tabellen-Fotos deutlich ungenauer: Ergebnis immer prüfen.';
}
$('#modeSeg').querySelectorAll('button').forEach(b => b.addEventListener('click', () => setModeUI(b.dataset.mode)));
$('#showKey').addEventListener('click', () => { const i = $('#setKey'); i.type = i.type === 'password' ? 'text' : 'password'; });
$('#saveSettings').addEventListener('click', () => {
  const s = state.settings;
  s.name = $('#setName').value.trim();
  s.apiKey = $('#setKey').value.trim();
  s.model = $('#setModel').value;
  s.mode = $('#modeSeg .on')?.dataset.mode || 'claude';
  save(); renderModePill(); toast('Einstellungen gespeichert');
});

/* ---------- export / backup ---------- */
function download(name, text, type){
  const blob = new Blob([text], {type});
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = name;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 4000);
}
function icsEscape(s){ return String(s).replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\r?\n/g, '\\n'); }
function buildIcs(keys){
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d+/, '');
  const lines = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Liqstorm//Dienstplan//DE', 'CALSCALE:GREGORIAN', 'X-WR-CALNAME:Dienstplan'];
  let count = 0;
  for (const k of keys){
    const e = state.days[k]; if (!e || !e.code) continue;
    const def = codeDef(e.code);
    if (def && def.kind === 'off' && /^x$|frei/i.test(def.code + ' ' + def.label)) continue; // skip plain days off
    const ymd = k.replace(/-/g, ''), {start, end} = timesOf(e);
    const title = def ? `${def.label} (${e.code})` : `Dienst ${e.code}`;
    lines.push('BEGIN:VEVENT', `UID:${k}@dienstplan.liqstorm`, `DTSTAMP:${stamp}`, `SUMMARY:${icsEscape(title)}`);
    if (isTime(start) && isTime(end) && def?.kind !== 'off'){
      const [y, m, d] = k.split('-').map(Number);
      const overnight = end <= start;
      const endDate = overnight ? new Date(y, m - 1, d + 1) : new Date(y, m - 1, d);
      const endYmd = `${endDate.getFullYear()}${pad(endDate.getMonth() + 1)}${pad(endDate.getDate())}`;
      lines.push(`DTSTART:${ymd}T${start.replace(':', '')}00`, `DTEND:${endYmd}T${end.replace(':', '')}00`);
    } else {
      const [y, m, d] = k.split('-').map(Number), nx = new Date(y, m - 1, d + 1);
      lines.push(`DTSTART;VALUE=DATE:${ymd}`, `DTEND;VALUE=DATE:${nx.getFullYear()}${pad(nx.getMonth() + 1)}${pad(nx.getDate())}`, 'TRANSP:TRANSPARENT');
    }
    if (e.note) lines.push(`DESCRIPTION:${icsEscape(e.note)}`);
    lines.push('END:VEVENT');
    count++;
  }
  lines.push('END:VCALENDAR');
  return {text: lines.join('\r\n') + '\r\n', count};
}
$('#icsMonth').addEventListener('click', () => {
  const prefix = `${viewY}-${pad(viewM + 1)}-`;
  const {text, count} = buildIcs(Object.keys(state.days).filter(k => k.startsWith(prefix)).sort());
  if (!count){ toast('In diesem Monat gibt es noch keine Dienste.'); return; }
  download(`dienstplan-${viewY}-${pad(viewM + 1)}.ics`, text, 'text/calendar');
  toast(`${count} Termine exportiert`);
});
$('#icsAll').addEventListener('click', () => {
  const {text, count} = buildIcs(Object.keys(state.days).sort());
  if (!count){ toast('Noch keine Dienste vorhanden.'); return; }
  download('dienstplan.ics', text, 'text/calendar');
  toast(`${count} Termine exportiert`);
});
$('#backupBtn').addEventListener('click', () => {
  const data = {...state, settings: {...state.settings, apiKey: ''}}; // never put the key into a file
  download(`dienstplan-backup-${keyOf(now.getFullYear(), now.getMonth(), now.getDate())}.json`, JSON.stringify(data, null, 2), 'application/json');
});
$('#restoreBtn').addEventListener('click', () => $('#restoreInput').click());
$('#restoreInput').addEventListener('change', async e => {
  const f = e.target.files[0]; e.target.value = '';
  if (!f) return;
  try {
    const d = JSON.parse(await f.text());
    if (!d || typeof d.days !== 'object' || !Array.isArray(d.codes)) throw new Error();
    if (!confirm('Backup laden? Dein aktueller Plan wird ersetzt.')) return;
    state.days = d.days; state.codes = d.codes;
    Object.assign(state.settings, {...d.settings, apiKey: state.settings.apiKey});
    save(); renderSettings(); renderModePill(); toast('Backup geladen');
  } catch { toast('Diese Datei ist kein gültiges Dienstplan-Backup.'); }
});
$('#wipeBtn').addEventListener('click', () => {
  if (!confirm('Wirklich alle Dienste, Dienstarten und Einstellungen auf diesem Gerät löschen?')) return;
  localStorage.removeItem(STORE_KEY);
  location.reload();
});

/* ---------- scan view ---------- */
function renderScanForm(){
  const sel = $('#scanMonth');
  if (!sel.options.length){
    sel.innerHTML = MONTHS.map((m, i) => `<option value="${i}">${m}</option>`).join('');
    // rosters are usually published for the coming month
    const next = new Date(now.getFullYear(), now.getMonth() + 1, 1);
    sel.value = String(next.getMonth()); $('#scanYear').value = next.getFullYear();
  }
  if (!$('#scanName').value) $('#scanName').value = state.settings.name;
  renderThumbs();
}
$('#camBtn').addEventListener('click', () => $('#camInput').click());
$('#galBtn').addEventListener('click', () => $('#galInput').click());
$('#camInput').addEventListener('change', e => { addFiles(e.target.files); e.target.value = ''; });
$('#galInput').addEventListener('change', e => { addFiles(e.target.files); e.target.value = ''; });
(() => {
  const d = $('#drop');
  d.addEventListener('dragover', e => { e.preventDefault(); d.classList.add('over'); });
  d.addEventListener('dragleave', () => d.classList.remove('over'));
  d.addEventListener('drop', e => { e.preventDefault(); d.classList.remove('over'); addFiles(e.dataTransfer.files); });
})();

/** Downscale and re-encode a photo as JPEG so it stays well under the API image limits. */
async function prepareImage(file, maxSide = 2400){
  let bmp;
  try { bmp = await createImageBitmap(file, {imageOrientation: 'from-image'}); }
  catch {
    bmp = await new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = URL.createObjectURL(file); });
  }
  const w = bmp.width, h = bmp.height, s = Math.min(1, maxSide / Math.max(w, h));
  const c = document.createElement('canvas'); c.width = Math.round(w * s); c.height = Math.round(h * s);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
  const dataUrl = c.toDataURL('image/jpeg', 0.9);
  return {dataUrl, base64: dataUrl.split(',')[1], mediaType: 'image/jpeg'};
}
async function addFiles(list){
  const files = [...list].filter(f => f.type.startsWith('image/'));
  if (!files.length) return;
  setStatus('busy', 'Bilder werden vorbereitet');
  for (const f of files){
    try { photos.push({id: Math.random().toString(36).slice(2), name: f.name, ...(await prepareImage(f))}); }
    catch { toast(`„${f.name}“ konnte nicht gelesen werden.`); }
  }
  if (photos.length > 6){ photos = photos.slice(0, 6); toast('Maximal 6 Fotos pro Scan.'); }
  setStatus('', '');
  renderThumbs();
}
function renderThumbs(scanning = false){
  $('#thumbs').innerHTML = photos.map(p =>
    `<div class="thumb${scanning ? ' scanning' : ''}"><img src="${p.dataUrl}" alt="${esc(p.name)}">${scanning ? '' : `<button type="button" data-id="${p.id}" aria-label="Foto entfernen">✕</button>`}</div>`).join('');
  $('#thumbs').querySelectorAll('button').forEach(b => b.addEventListener('click', () => { photos = photos.filter(p => p.id !== b.dataset.id); renderThumbs(); }));
  $('#recognizeBtn').disabled = !photos.length || scanning;
}
function setStatus(kind, text){
  const s = $('#scanStatus');
  s.className = 'status' + (kind === 'err' ? ' err' : kind === 'ok' ? ' ok' : '');
  s.innerHTML = kind === 'busy' ? `<span class="spin"></span>${esc(text)}` : esc(text);
}

$('#recognizeBtn').addEventListener('click', async () => {
  const month = +$('#scanMonth').value, year = +$('#scanYear').value, name = $('#scanName').value.trim();
  if (!name){ toast('Bitte deinen Namen eintragen, so wie er auf dem Plan steht.'); $('#scanName').focus(); return; }
  if (!(year >= 2000 && year <= 2100)){ toast('Bitte ein gültiges Jahr eintragen.'); return; }
  if (name !== state.settings.name){ state.settings.name = name; save(); }
  const useClaude = state.settings.mode === 'claude';
  if (useClaude && !state.settings.apiKey){
    toast('Für die KI-Erkennung fehlt der API-Schlüssel (System).'); show('settings'); return;
  }
  renderThumbs(true);
  setStatus('busy', useClaude ? 'Claude liest den Plan' : 'Texterkennung läuft');
  try {
    const r = useClaude ? await recognizeWithClaude({year, month, name}) : await recognizeOffline({year, month, name});
    scanResult = {year, month, ...r};
    renderReview();
    const found = Object.values(scanResult.entries).filter(e => e.code).length;
    setStatus(found ? 'ok' : 'err', found ? `${found} Tage erkannt` : 'Keine Dienste für deinen Namen gefunden. Name und Foto prüfen oder manuell eintragen.');
  } catch (err){
    console.error(err);
    setStatus('err', err.userMessage || 'Erkennung fehlgeschlagen: ' + (err.message || err));
  } finally {
    renderThumbs(false);
  }
});

/* --- Claude vision recognition --- */
let sdkPromise = null;
const loadSdk = () => (sdkPromise ??= import(SDK_URL).then(m => m.default || m.Anthropic));

const RESULT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['person_found', 'matched_name', 'entries', 'unknown_codes', 'notes'],
  properties: {
    person_found: {type: 'boolean'},
    matched_name: {type: 'string', description: 'Name exactly as printed on the roster row that was used, or empty.'},
    entries: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['day', 'code', 'start', 'end', 'uncertain'],
        properties: {
          day: {type: 'integer', description: 'Day of month, 1-31.'},
          code: {type: 'string', description: 'Shift code exactly as printed in the cell, or empty string if the cell is blank.'},
          start: {type: 'string', description: 'HH:MM if a start time is printed for this day, else empty.'},
          end: {type: 'string', description: 'HH:MM if an end time is printed for this day, else empty.'},
          uncertain: {type: 'boolean', description: 'True if the cell was hard to read or the column/day mapping is doubtful.'},
        },
      },
    },
    unknown_codes: {
      type: 'array',
      description: 'Codes found in the row that are not in the known list, with their meaning if a legend on the roster explains them.',
      items: {
        type: 'object', additionalProperties: false, required: ['code', 'label', 'start', 'end'],
        properties: {code: {type: 'string'}, label: {type: 'string'}, start: {type: 'string'}, end: {type: 'string'}},
      },
    },
    notes: {type: 'string', description: 'Short note in German for the user about problems (blurry area, multiple matching names, cut-off days). Empty if none.'},
  },
};

const SYSTEM_PROMPT = `Du liest fotografierte Dienstpläne (Schichtpläne) aus Krankenhäusern, Pflege, Handel, Sicherheit, Gastronomie und ähnlichen Betrieben.

Aufgabe: Finde die Zeile (oder Spalte) der genannten Person und gib für jeden Tag des angegebenen Monats das eingetragene Dienstkürzel zurück.

Vorgehen:
- Bestimme zuerst, wie die Tabelle aufgebaut ist: wo stehen die Tagesnummern bzw. Wochentage, wo die Namen. Pläne können quer oder hoch, handschriftlich ergänzt, schief fotografiert oder über mehrere Fotos verteilt sein.
- Ordne jede Zelle über die Spaltenköpfe dem richtigen Tag zu. Prüfe die Zuordnung an den Wochentagen gegen den Kalender des genannten Monats.
- Übernimm Kürzel genau so, wie sie in der Zelle stehen (z. B. "F", "S2", "N", "U", "/"). Handschriftliche Korrekturen oder Durchstreichungen haben Vorrang vor dem gedruckten Wert.
- Leere Zellen bekommen code "". Erfinde keine Werte. Wenn du dir bei einer Zelle nicht sicher bist, gib deine beste Lesart an und setze uncertain=true.
- Wenn der Plan eine Legende mit Uhrzeiten oder Bedeutungen der Kürzel hat, nutze sie für unknown_codes. Uhrzeiten, die direkt in einer Zelle stehen, kommen in start/end dieses Tages.
- Wenn mehrere Personen zum Namen passen könnten, nimm die beste Übereinstimmung und erwähne das in notes.
- Gib für jeden Tag des Monats genau einen Eintrag zurück, auch für leere Tage.`;

async function recognizeWithClaude({year, month, name}){
  const Anthropic = await loadSdk().catch(() => {
    const e = new Error('sdk'); e.userMessage = 'Die KI-Bibliothek konnte nicht geladen werden. Besteht eine Internetverbindung?'; throw e;
  });
  const client = new Anthropic({apiKey: state.settings.apiKey, dangerouslyAllowBrowser: true});
  const model = state.settings.model || 'claude-opus-5-5';
  const n = daysIn(year, month);
  const firstDow = DOW_LONG[dowIdx(year, month, 1)];
  const known = state.codes.map(c => `${c.code} = ${c.label}${c.start ? ` (${c.start}–${c.end})` : ''}`).join('\n');

  const content = [
    ...photos.map(p => ({type: 'image', source: {type: 'base64', media_type: p.mediaType, data: p.base64}})),
    {type: 'text', text:
`Person: ${name}
Monat: ${MONTHS[month]} ${year} (${n} Tage, der 1. ist ein ${firstDow})
Anzahl Fotos: ${photos.length}

Bekannte Kürzel dieser Person:
${known}

Lies die Dienste dieser Person für jeden Tag 1 bis ${n} aus.`},
  ];

  const params = {
    model,
    max_tokens: 16000,
    system: SYSTEM_PROMPT,
    output_config: {effort: 'high', format: {type: 'json_schema', schema: RESULT_SCHEMA}},
    messages: [{role: 'user', content}],
  };
  // Server-side refusal fallback is available on Opus 5.5 and Sonnet 5.5 (not Haiku).
  if (model !== 'claude-haiku-5-5'){ params.betas = ['server-side-fallback-2026-07-01']; params.fallbacks = 'default'; }

  let res;
  try {
    res = await client.beta.messages.create(params);
  } catch (err){
    const e = new Error(err.message);
    if (err instanceof Anthropic.AuthenticationError) e.userMessage = 'Der API-Schlüssel wurde abgelehnt. Bitte unter System prüfen.';
    else if (err instanceof Anthropic.PermissionDeniedError) e.userMessage = 'Der API-Schlüssel hat keinen Zugriff auf dieses Modell.';
    else if (err instanceof Anthropic.RateLimitError) e.userMessage = 'Zu viele Anfragen oder Guthaben aufgebraucht. Kurz warten bzw. Guthaben in der Anthropic Console prüfen.';
    else if (err instanceof Anthropic.BadRequestError) e.userMessage = 'Die Anfrage wurde abgelehnt: ' + (err.error?.error?.message || err.message);
    else if (err instanceof Anthropic.APIConnectionError) e.userMessage = 'Keine Verbindung zur Anthropic-API. Internet prüfen.';
    else if (err instanceof Anthropic.APIError) e.userMessage = `API-Fehler (${err.status ?? '?'}). Bitte später erneut versuchen.`;
    throw e;
  }
  if (res.stop_reason === 'refusal'){ const e = new Error('refusal'); e.userMessage = 'Die Anfrage wurde vom Modell abgelehnt. Bitte ein anderes Foto versuchen.'; throw e; }
  if (res.stop_reason === 'max_tokens'){ const e = new Error('max_tokens'); e.userMessage = 'Antwort wurde abgeschnitten. Bitte mit weniger Fotos erneut versuchen.'; throw e; }
  const text = res.content.filter(b => b.type === 'text').map(b => b.text).join('');
  let data;
  try { data = JSON.parse(text); }
  catch { const e = new Error('parse'); e.userMessage = 'Die Antwort konnte nicht gelesen werden. Bitte erneut versuchen.'; throw e; }

  // Learn codes from the roster legend that the user hasn't defined yet.
  for (const u of data.unknown_codes || []){
    const code = String(u.code || '').trim();
    if (!code || codeDef(code)) continue;
    const hasTimes = isTime(u.start) && isTime(u.end);
    state.codes.push({code, label: u.label?.trim() || code, start: hasTimes ? u.start : '', end: hasTimes ? u.end : '', color: '#94a3b8', kind: hasTimes ? 'work' : 'off'});
  }
  save();

  const entries = {};
  for (const e of data.entries || []){
    const d = Number(e.day);
    if (!(d >= 1 && d <= n)) continue;
    entries[d] = {code: String(e.code || '').trim(), start: isTime(e.start) ? e.start : '', end: isTime(e.end) ? e.end : '', uncertain: !!e.uncertain};
  }
  const notes = [data.person_found ? '' : 'Name wurde auf dem Foto nicht sicher gefunden.', data.matched_name ? `Gelesene Zeile: „${data.matched_name}“.` : '', data.notes || '']
    .filter(Boolean).join(' ');
  return {entries, notes};
}

/* --- offline OCR fallback (tesseract.js) --- */
let tessPromise = null;
function loadTesseract(){
  return tessPromise ??= new Promise((res, rej) => {
    if (window.Tesseract) return res(window.Tesseract);
    const s = document.createElement('script'); s.src = TESSERACT_URL;
    s.onload = () => res(window.Tesseract);
    s.onerror = () => { tessPromise = null; rej(Object.assign(new Error('tess'), {userMessage: 'Texterkennung konnte nicht geladen werden (einmalig Internet nötig).'})); };
    document.head.appendChild(s);
  });
}
async function recognizeOffline({year, month, name}){
  const T = await loadTesseract();
  const worker = await T.createWorker('deu', 1, {logger: m => {
    if (m.status === 'recognizing text') setStatus('busy', `Texterkennung ${Math.round(m.progress * 100)} %`);
  }});
  const n = daysIn(year, month);
  const known = new Map(state.codes.map(c => [c.code.toLowerCase(), c.code]));
  const needle = name.toLowerCase().split(/[\s,.]+/).filter(w => w.length > 1).sort((a, b) => b.length - a.length)[0] || name.toLowerCase();
  let best = null;
  try {
    for (const p of photos){
      const {data} = await worker.recognize(p.dataUrl);
      for (const line of data.text.split('\n')){
        const low = line.toLowerCase(), at = low.indexOf(needle);
        if (at < 0) continue;
        const rest = line.slice(at + needle.length).replace(/^[^\s]*/, '');
        const tokens = rest.split(/[\s|]+/).filter(Boolean);
        const hits = tokens.filter(t => known.has(t.toLowerCase()) || /^[-/]$/.test(t)).length;
        if (!best || hits > best.hits) best = {tokens, hits, line};
      }
    }
  } finally { await worker.terminate(); }
  const entries = {};
  for (let d = 1; d <= n; d++) entries[d] = {code: '', start: '', end: '', uncertain: true};
  if (!best) return {entries, notes: 'Dein Name wurde im erkannten Text nicht gefunden. Tipp: KI-Erkennung nutzen oder die Tage unten von Hand setzen.'};
  // Assume the row lists one cell per day in order; map known codes, treat "-" or "/" as a day off.
  const off = state.codes.find(c => c.kind === 'off')?.code || '';
  best.tokens.slice(0, n).forEach((t, i) => {
    const c = known.get(t.toLowerCase());
    entries[i + 1] = {code: c || (/^[-/]$/.test(t) ? off : ''), start: '', end: '', uncertain: true};
  });
  return {entries, notes: `Offline erkannt (ungenau). Gelesene Zeile: „${best.line.trim().slice(0, 140)}“. Bitte jeden Tag prüfen.`};
}

/* --- review & apply --- */
function renderReview(){
  const {year, month, entries, notes} = scanResult, n = daysIn(year, month);
  $('#reviewPanel').hidden = false;
  $('#reviewTitle').textContent = `${MONTHS[month]} ${year}`;
  $('#reviewCount').textContent = `${Object.values(entries).filter(e => e.code).length} / ${n} Tage`;
  $('#reviewNotes').hidden = !notes; $('#reviewNotes').textContent = notes || '';
  const opts = cur => {
    const list = state.codes.map(c => c.code);
    if (cur && !list.some(c => c.toLowerCase() === cur.toLowerCase())) list.push(cur);
    return `<option value="">–</option>` + list.map(c => `<option value="${esc(c)}"${c.toLowerCase() === (cur || '').toLowerCase() ? ' selected' : ''}>${esc(c)}${codeDef(c) ? ' · ' + esc(codeDef(c).label) : ' · neu'}</option>`).join('');
  };
  let html = '';
  for (let d = 1; d <= n; d++){
    const e = entries[d] || {code: '', start: '', end: '', uncertain: false}, wi = dowIdx(year, month, d);
    const def = codeDef(e.code);
    html += `<div class="rv${e.uncertain && e.code ? ' unsure' : ''}${wi > 4 ? ' we' : ''}" data-d="${d}">
      <span class="dd">${pad(d)}.${pad(month + 1)}.<small>${DOW[wi].toUpperCase()}</small></span>
      <select aria-label="Dienst am ${d}.">${opts(e.code)}</select>
      <input type="time" class="s" value="${e.start || def?.start || ''}" aria-label="Beginn">
      <input type="time" class="e" value="${e.end || def?.end || ''}" aria-label="Ende">
    </div>`;
  }
  $('#review').innerHTML = html;
  $('#review').querySelectorAll('.rv').forEach(row => row.querySelector('select').addEventListener('change', ev => {
    const def = codeDef(ev.target.value);
    row.querySelector('.s').value = def?.start || ''; row.querySelector('.e').value = def?.end || '';
    row.classList.remove('unsure');
  }));
  $('#reviewPanel').scrollIntoView({behavior: 'smooth', block: 'start'});
}
$('#discardBtn').addEventListener('click', () => { scanResult = null; $('#reviewPanel').hidden = true; setStatus('', ''); });
$('#applyBtn').addEventListener('click', () => {
  if (!scanResult) return;
  const {year, month} = scanResult, keep = $('#keepManual').checked;
  let applied = 0, skipped = 0;
  $('#review').querySelectorAll('.rv').forEach(row => {
    const d = +row.dataset.d, code = row.querySelector('select').value, k = keyOf(year, month, d), cur = state.days[k];
    if (!code) return;
    if (keep && cur?.src === 'manual'){ skipped++; return; }
    if (!codeDef(code)) state.codes.push({code, label: code, start: '', end: '', color: '#94a3b8', kind: 'work'});
    const def = codeDef(code), s = row.querySelector('.s').value, e = row.querySelector('.e').value;
    const entry = {code, src: 'scan'};
    if (s && s !== (def.start || '')) entry.start = s;
    if (e && e !== (def.end || '')) entry.end = e;
    if (cur?.note) entry.note = cur.note;
    state.days[k] = entry; applied++;
  });
  save();
  scanResult = null; photos = []; $('#reviewPanel').hidden = true; setStatus('', ''); renderThumbs();
  viewY = year; viewM = month; show('plan');
  toast(`${applied} Tage übernommen${skipped ? `, ${skipped} manuelle behalten` : ''}`, 3400);
});

/* ---------- background embers (bull / bear) ---------- */
(() => {
  const c = $('#sparks'); if (!c || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  const x = c.getContext('2d'); let w, h, dpr, parts = [];
  function size(){ dpr = Math.min(devicePixelRatio || 1, 2); w = c.width = innerWidth * dpr; h = c.height = innerHeight * dpr; c.style.width = innerWidth + 'px'; c.style.height = innerHeight + 'px'; }
  function spawn(init){ const side = Math.random() < .5; return {
    x: (side ? Math.random() * .5 : .5 + Math.random() * .5) * w, y: init ? Math.random() * h : h + 10,
    r: (Math.random() * 1.4 + .3) * dpr, v: (Math.random() * .3 + .1) * dpr, a: Math.random() * .5 + .15,
    col: side ? '56,189,248' : '244,63,94', drift: (Math.random() - .5) * .22 * dpr}; }
  function init(){ parts = []; const n = Math.round(Math.min(50, innerWidth / 20)); for (let i = 0; i < n; i++) parts.push(spawn(true)); }
  function tick(){
    x.clearRect(0, 0, w, h);
    for (const p of parts){
      p.y -= p.v; p.x += p.drift; if (p.y < -10) Object.assign(p, spawn(false));
      x.beginPath(); x.arc(p.x, p.y, p.r, 0, Math.PI * 2); x.fillStyle = `rgba(${p.col},${p.a})`; x.shadowBlur = 8 * dpr; x.shadowColor = `rgba(${p.col},.8)`; x.fill();
    }
    requestAnimationFrame(tick);
  }
  size(); init(); tick(); addEventListener('resize', () => { size(); init(); });
})();

/* ---------- boot ---------- */
renderModePill();
renderPlan();
if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => {});
