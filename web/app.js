'use strict';
/* MivimooseRips Scanner, desktop UI (Holo Vault theme). Talks to the local server. */

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const api = {
  get: (p) => fetch(p, { cache: 'no-store' }).then((r) => r.json()),
  post: (p, body) => fetch(p, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Scanner': '1' }, body: JSON.stringify(body || {}) })
    .then(async (r) => { const j = await r.json(); if (!r.ok) throw new Error(j.error || r.statusText); return j; }),
};
const act = (action, extra = {}) => api.post('/api/action', { action, ...extra }).catch((e) => toast(esc(e.message), 'bad'));

let S = null;            // latest /api/state
let SETS = [];           // set list
let tab = 'scan';
let live = null;
const NOLIVE = new URLSearchParams(location.search).has('nolive');   // screenshots: no streams
const TABS = ['scan', 'binder', 'packs', 'collection', 'settings'];
const TITLES = { scan: 'Scan', binder: 'Binder', packs: 'Packs', collection: 'Collection', settings: 'Settings' };
const LANGS = { en: 'English', ja: 'Japanese', ko: 'Korean', 'zh-tw': 'Chinese (Trad.)', 'zh-cn': 'Chinese (Simp.)', th: 'Thai', id: 'Indonesian' };

// ------------------------------------------------------------ formatting
let _fmt = null, _fmtCur = null;
function money(usd) {
  if (usd == null || isNaN(usd)) return '—';
  const cur = (S && S.settings.currency) || 'USD';
  const rate = (S && S.rates && S.rates[cur]) || 1;
  if (_fmtCur !== cur) {
    _fmt = new Intl.NumberFormat(undefined, { style: 'currency', currency: cur, maximumFractionDigits: cur === 'JPY' ? 0 : 2 });
    _fmtCur = cur;
  }
  return _fmt.format(usd * rate);
}
const signed = (usd) => (usd >= 0 ? '+' : '−') + money(Math.abs(usd));
const pct = (a, b) => (b ? Math.round((100 * a) / b) : 0);
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
function foil(c, extra = '') {
  if (!c || !c.img) return `<div class="foil empty ${extra}"><span class="muted small">${esc(c ? c.name : '')}</span></div>`;
  return `<div class="foil ${extra}"><img src="${esc(c.img)}" alt="${esc(c.name)}" loading="lazy" onerror="this.style.visibility='hidden'"></div>`;
}
function countUp(el, to) {
  const from = parseFloat(el.dataset.v || '0');
  el.dataset.v = to;
  if (Math.abs(to - from) < 0.005) { el.textContent = money(to); return; }
  const t0 = performance.now(), dur = 650;
  const step = (t) => {
    const k = Math.min(1, (t - t0) / dur), e = 1 - Math.pow(1 - k, 3);
    el.textContent = money(from + (to - from) * e);
    if (k < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

// ------------------------------------------------------------ interface sounds (synthesised in the browser)
let _ac = null;
function uiSound(kind) {
  if (!S || !S.settings.sound || !S.settings.snd_ui) return;
  try {
    _ac = _ac || new (window.AudioContext || window.webkitAudioContext)();
    const t = _ac.currentTime, vol = 0.18 * (S.settings.volume ?? 0.8);
    const tone = (f0, f1, dur, type = 'sine', gain = 1, delay = 0) => {
      const o = _ac.createOscillator(), g = _ac.createGain();
      o.type = type;
      o.frequency.setValueAtTime(f0, t + delay);
      o.frequency.exponentialRampToValueAtTime(f1, t + delay + dur);
      g.gain.setValueAtTime(0.0001, t + delay);
      g.gain.exponentialRampToValueAtTime(vol * gain, t + delay + 0.005);
      g.gain.exponentialRampToValueAtTime(0.0001, t + delay + dur);
      o.connect(g).connect(_ac.destination);
      o.start(t + delay);
      o.stop(t + delay + dur + 0.02);
    };
    if (kind === 'click') tone(1800, 1200, 0.045, 'sine', 0.6);
    else if (kind === 'tab') { tone(620, 900, 0.09, 'sine', 0.7); tone(900, 1300, 0.08, 'sine', 0.4, 0.05); }
    else if (kind === 'open') tone(400, 1100, 0.14, 'sine', 0.6);
    else if (kind === 'close') tone(1000, 420, 0.12, 'sine', 0.5);
    else if (kind === 'on') { tone(700, 700, 0.06, 'triangle', 0.6); tone(1050, 1050, 0.08, 'triangle', 0.6, 0.05); }
    else if (kind === 'off') { tone(1050, 1050, 0.06, 'triangle', 0.6); tone(700, 700, 0.08, 'triangle', 0.6, 0.05); }
    else if (kind === 'page') tone(300, 160, 0.12, 'triangle', 0.5);
  } catch (e) { /* audio not available */ }
}

// ------------------------------------------------------------ toasts & celebrations
function toast(html, kind = 'info', ms = 3800) {
  const el = document.createElement('div');
  el.className = `toast ${kind}`;
  el.innerHTML = `<span>${html}</span>`;
  $('#toasts').appendChild(el);
  setTimeout(() => el.remove(), ms);
}
function celebrate(title, big, sub) {
  const c = $('#celebrate');
  const colors = ['#6ef3d6', '#8aa2ff', '#e48bff', '#ffcf8a', '#ffffff'];
  let sparks = '';
  for (let i = 0; i < 46; i++) {
    sparks += `<i class="spark" style="left:${Math.random() * 100}vw;top:-24px;background:${colors[i % 5]};--dx:${(Math.random() - .5) * 240}px;--r:${Math.round(Math.random() * 900)}deg;animation-delay:${Math.random() * .45}s"></i>`;
  }
  c.innerHTML = `${sparks}<div class="box"><h2>${esc(title)}</h2><div class="big">${esc(big)}</div><div class="muted">${esc(sub || '')}</div></div>`;
  c.hidden = false;
  clearTimeout(celebrate.t);
  celebrate.t = setTimeout(() => { c.hidden = true; }, 3300);
}

// ------------------------------------------------------------ state
let refreshTimer = null;
function refreshSoon(delay = 80) { clearTimeout(refreshTimer); refreshTimer = setTimeout(refresh, delay); }
async function refresh() {
  try { S = await api.get('/api/state'); } catch (e) { return; }
  renderChrome();
  if (tab === 'scan') renderScan();
  if (tab === 'settings' && !renderSettings.built) renderSettings();
}

function renderChrome() {
  const cam = S.camera, vr = S.vr;
  const camOk = cam && cam.status === 'live';
  $('#rail-status').innerHTML = `
    <div class="status-line ${camOk ? 'ok' : cam ? 'err' : 'warn'}" title="${esc(cam ? cam.error || cam.name : 'No camera')}"><svg><use href="#i-cam"/></svg><span>${esc(cam ? cam.name : 'No camera')}</span><i class="dot"></i></div>
    <div class="status-line ${vr.connected ? 'ok' : 'warn'}" title="${esc(vr.status || '')}"><svg><use href="#i-vr"/></svg><span>${vr.connected ? 'SteamVR connected' : esc(vr.status || 'VR off')}</span><i class="dot"></i></div>
    <div class="muted small" style="padding-left:2px">v${esc(S.version)}${S.matcher ? ` · knows ${S.matcher.cards} cards` : ''}</div>`;
  const s = S.set;
  const pill = $('#set-pill');
  $('.sp-logo', pill).style.backgroundImage = s && s.logo ? `url("${s.logo}")` : '';
  $('.sp-text', pill).innerHTML = s ? `${esc(s.name)}<small>${esc(s.release.slice(0, 4))}</small>` : 'Choose the set you’re opening';
  const chips = [];
  if (S.perf && S.perf.fps && tab === 'scan') chips.push(`<span class="chip"><svg><use href="#i-scan"/></svg>${S.perf.fps} fps · ${S.perf.ms} ms</span>`);
  if (S.prep && S.prep.running && S.prep.total) chips.push(`<span class="chip"><svg><use href="#i-download"/></svg>Getting ${esc(S.prep.name)} ready ${pct(S.prep.done, S.prep.total)}%</span>`);
  $('#chips').innerHTML = chips.join('');
}

// ------------------------------------------------------------ scan view
function renderScan() {
  renderCamera();
  renderHero();
  renderPack();
  renderSession();
  renderRecent();
  const paused = S.settings.paused;
  const pb = $('#btn-pause');
  pb.innerHTML = `<svg><use href="#i-${paused ? 'play' : 'pause'}"/></svg><span>${paused ? 'Resume' : 'Pause'}</span>`;
  pb.classList.toggle('on', paused);
  $('#btn-reverse').classList.toggle('on', S.next_variant === 'reverse');
  $('#btn-next').classList.toggle('pulse', S.state === 'pack_done');
  $('#perf').textContent = S.perf && S.perf.fps ? `${S.perf.fps} scans/s` : '';
}

function renderCamera() {
  const img = $('#camera');
  const on = S.camera && S.camera.status === 'live' && S.settings.show_preview;
  if (on && !img.dataset.on) {
    img.src = (NOLIVE ? '/frame.jpg?w=1280' : '/video.mjpg?w=1280&fps=15') + '&t=' + Date.now();
    img.dataset.on = '1';
    img.onerror = () => { delete img.dataset.on; setTimeout(refreshSoon, 1500); };
  } else if (!on && img.dataset.on) {
    img.removeAttribute('src');
    delete img.dataset.on;
  }
  img.hidden = !on;
  const empty = $('#stage-empty');
  empty.hidden = on;
  if (!on) {
    let title = 'Camera off', body = '';
    if (S.state === 'loading') { title = 'Loading the card database'; body = esc(S.message); }
    else if (!S.settings.show_preview && S.camera && S.camera.status === 'live') { title = 'Camera preview hidden'; body = 'Scanning still runs. Turn the preview on in Settings.'; }
    else if (S.camera) { title = 'Camera not ready'; body = esc(S.camera.error || 'Starting…') + '<br>Pick another camera in Settings → Camera.'; }
    else { title = 'No camera'; body = 'Plug in a webcam, or start SteamVR to use the headset camera.'; }
    empty.innerHTML = `<div><b>${title}</b>${body}</div>`;
  }
  renderLive();
}

function renderLive() {
  const pill = $('#live-pill');
  const state = (live && live.state) || (S && S.state) || 'loading';
  let cls = state, msg = esc((live && live.message) || (S && S.message) || ''), extra = '';
  if (live && state === 'scanning') {
    if (live.pendingName && live.progress > 0) {
      cls = 'reading';
      msg = `Reading ${esc(live.pendingName)}`;
      extra = `<span class="prog"><i style="width:${Math.round(live.progress * 100)}%"></i></span>`;
    } else if (live.hintText) {
      cls = 'hint';
      msg = esc(live.hintText);
    } else if (live.lockedNames && live.lockedNames.length) {
      const n = live.lockedNames.length;
      msg = n === 1 ? `Got ${esc(live.lockedNames[0])}. Show the next card` : `${n} cards in view, all scanned`;
    }
  }
  pill.className = 'live-pill ' + cls;
  pill.innerHTML = `<i class="dot"></i><span>${msg}</span>${extra}`;
  $('#stage').classList.toggle('reading', cls === 'reading');
}
async function pollLive() {
  if (tab === 'scan' && document.visibilityState === 'visible') {
    try { live = await api.get('/api/live'); renderLive(); } catch (e) { /* server restarting */ }
  }
  setTimeout(pollLive, 250);
}

let lastHeroId = null;
function renderHero() {
  const el = $('#hero'), sc = S.last;
  if (!sc || !sc.card) {
    el.innerHTML = `<div class="hero-empty"><b>Ready when you are</b>Hold each card up to the camera, face out.<br>New cards, duplicates and repeats are sorted out for you.</div>`;
    lastHeroId = null;
    return;
  }
  const c = sc.card;
  const hit = sc.price >= S.settings.hit_value;
  const fresh = lastHeroId !== null && lastHeroId !== sc.id;
  if (lastHeroId === sc.id && el.dataset.variant === sc.variant) {
    countUp($('.hero-price', el), sc.price);
    return;
  }
  el.dataset.variant = sc.variant;
  el.innerHTML = `<h3>Last pull</h3>
    <div class="hero-card">
      ${foil(c, hit ? 'shine' : '')}
      <div>
        <div class="row gap wrap">
          <span class="badge ${sc.dupe ? 'dupe' : 'new'}">${sc.dupe ? 'DUPLICATE' : 'NEW'}</span>
          ${hit ? '<span class="badge hit">HIT</span>' : ''}
          ${sc.source === 'manual' ? '<span class="badge soft">BY HAND</span>' : ''}
          ${c.priceKnown ? '' : '<span class="badge soft" title="No market price yet; estimated from similar cards">EST.</span>'}
        </div>
        <div class="hero-name">${esc(c.name)}</div>
        <div class="muted small">${esc(c.setName)} · #${esc(c.number)} · ${esc(c.rarity || '')}</div>
        <div class="hero-price" data-v="${fresh ? 0 : sc.price}">${money(sc.price)}</div>
        <div class="variants">${['normal', 'reverse', 'holo'].map((k) => `<button class="${sc.variant === k ? 'active' : ''}" data-variant="${k}" data-scan="${sc.id}">${{ normal: 'Normal', reverse: 'Reverse', holo: 'Holo' }[k]}</button>`).join('')}</div>
      </div>
    </div>
    <div class="row gap wrap">
      <button class="btn sm" data-wrong="${sc.id}">Wrong card?</button>
      <button class="btn sm danger" data-delete="${sc.id}"><svg><use href="#i-trash"/></svg>Remove</button>
    </div>
    ${sc.alts && sc.alts.length ? `<div><div class="muted small">Or was it one of these?</div><div class="alts">${sc.alts.map((a) => `<button data-correct="${esc(a.key)}" data-scan="${sc.id}" title="${esc(a.name)} #${esc(a.number)}">${foil(a)}</button>`).join('')}</div></div>` : ''}`;
  $('.hero-card .foil', el).onclick = () => showCard(c);
  if (fresh) {
    el.classList.remove('flash');
    void el.offsetWidth;
    el.classList.add('flash');
    countUp($('.hero-price', el), sc.price);
  }
  lastHeroId = sc.id;
}

function renderPack() {
  const el = $('#pack'), p = S.pack;
  if (!p) { el.innerHTML = '<h3>Pack tracker</h3><p class="muted">Choose a set to start tracking packs.</p>'; return; }
  const slots = [];
  for (let i = 0; i < Math.max(p.size, p.count); i++) {
    const sc = p.scans[i];
    if (sc) {
      const cls = sc.dupe ? 'dupe' : sc.price >= S.settings.hit_value ? 'hit' : '';
      slots.push(`<div class="slot filled ${cls}" title="${esc(sc.card ? sc.card.name : sc.name)} · ${money(sc.price)}" data-card="${esc(sc.card_key)}">${foil(sc.card)}</div>`);
    } else slots.push('<div class="slot"></div>');
  }
  const profit = p.count ? p.profit : null;
  el.innerHTML = `<div class="pack-top"><h3>Pack ${p.number}</h3><span class="pack-count">${p.count}<span class="muted">/${p.size}</span></span></div>
    <div class="slots">${slots.join('')}</div>
    <div class="money">
      <div><div class="k">Pulled</div><div class="v">${money(p.value)}</div></div>
      <div><div class="k">Cost</div><div class="v">${p.cost != null ? money(p.cost) : '—'}</div></div>
      <div><div class="k">Profit</div><div class="v ${profit == null ? '' : profit >= 0 ? 'good' : 'bad'}">${profit == null ? '—' : signed(profit)}</div></div>
    </div>
    <div class="muted small" style="margin-top:10px">${p.new} new · ${plural(p.dupes, 'duplicate')} · ${plural(p.hits, 'hit')}${p.best ? ` · best: ${esc(p.best.name)}` : ''}</div>`;
}

function renderSession() {
  const s = S.session || {}, pk = s.packs || {};
  const profit = (s.value || 0) - (pk.cost || 0);
  $('#session').innerHTML = `<div class="row-head" style="margin-bottom:10px"><h3>This session</h3><button class="btn sm ghost" data-act="reset_session">Reset</button></div>
    <div class="tiles" style="grid-template-columns:1fr 1fr">
      <div class="tile"><div class="k">Packs</div><div class="v">${pk.packs || 0}</div></div>
      <div class="tile"><div class="k">Cards</div><div class="v">${s.scans || 0}</div></div>
      <div class="tile"><div class="k">Pulled</div><div class="v">${money(s.value || 0)}</div></div>
      <div class="tile"><div class="k">Profit</div><div class="v ${profit >= 0 ? 'good' : 'bad'}">${signed(profit)}</div></div>
    </div>`;
}

function renderRecent() {
  const rows = S.recent || [];
  $('#recent').innerHTML = rows.length ? rows.map((sc) => `<div class="mini ${sc.dupe ? 'dupe' : ''}" data-card="${esc(sc.card_key)}" title="${esc(sc.name)} · ${esc(sc.set_id)}">${foil(sc.card)}<div class="p">${money(sc.price)}</div></div>`).join('')
    : '<span class="muted">Your pulls will line up here.</span>';
}

// ------------------------------------------------------------ set chooser
async function loadSets(force) {
  if (!SETS.length || force) SETS = (await api.get('/api/sets')).sets || [];
  return SETS;
}
function langSeg(el, value, withAll, onPick) {
  const langs = [...new Set(SETS.map((s) => s.lang))];
  const opts = (withAll ? [['', 'All']] : []).concat(langs.map((l) => [l, LANGS[l] || l]));
  el.innerHTML = opts.map(([v, l]) => `<button data-lang="${esc(v)}" class="${v === value ? 'active' : ''}">${esc(l)}</button>`).join('');
  el.onclick = (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    $$('button', el).forEach((x) => x.classList.toggle('active', x === b));
    onPick(b.dataset.lang);
  };
}
let setLang = 'en';
async function openSetDialog() {
  await loadSets(true);
  langSeg($('#set-lang'), setLang, false, (v) => { setLang = v; renderSetChoices(); });
  renderSetChoices();
  $('#set-dialog').showModal();
  $('#set-search').focus();
}
function renderSetChoices() {
  const q = $('#set-search').value.trim().toLowerCase();
  let list = SETS.filter((s) => s.lang === setLang && (!q || `${s.name} ${s.nameLocal || ''} ${s.series} ${s.release}`.toLowerCase().includes(q)));
  list = list.slice().sort((a, b) => b.release.localeCompare(a.release));
  let html = '', series = null;
  for (const s of list) {
    if (s.series !== series) { series = s.series; html += `<div class="series-title">${esc(series)}</div>`; }
    html += `<button class="set-choice" data-pick-set="${esc(s.id)}">${s.logo ? `<img src="${esc(s.logo)}" alt="" loading="lazy">` : `<span class="ph">${esc(s.lang.toUpperCase())}</span>`}
      <span><div class="t">${esc(s.name)}</div><div class="s">${esc(s.release.slice(0, 4))} · ${s.total} cards${s.owned ? ` · you have ${s.owned}` : ''}${s.ready ? ' · ready' : ''}</div></span></button>`;
  }
  $('#set-choices').innerHTML = html || '<p class="muted">No sets match.</p>';
}

// ------------------------------------------------------------ add / correct
let addMode = null;
function openAdd(mode) {
  addMode = mode;
  $('#add-title').textContent = mode.mode === 'correct' ? 'Which card was it?' : 'Add a card by hand';
  $('#add-search').value = '';
  $('#add-results').innerHTML = '<p class="muted">Type a name or card number.</p>';
  $('#add-dialog').showModal();
  $('#add-search').focus();
}
let addTimer = null;
async function runAddSearch() {
  const q = $('#add-search').value.trim();
  if (!q) return;
  const set = $('#add-anyset').checked ? '' : (S.settings.active_set || '');
  const r = await api.get(`/api/search?q=${encodeURIComponent(q)}&set=${encodeURIComponent(set)}&limit=40`);
  $('#add-results').innerHTML = r.cards.length ? r.cards.map((c) => `<div class="add-row" data-add-key="${esc(c.key)}">${foil(c)}
    <div class="nm"><b>${esc(c.name)}</b><div class="muted small">${esc(c.setName)} · #${esc(c.number)} · ${esc(c.rarity || '')}</div></div><b>${money(c.price)}</b></div>`).join('')
    : `<p class="muted">Nothing found${set ? ' in this set. Tick “All sets” to search everywhere' : ''}.</p>`;
}

// ------------------------------------------------------------ card detail
function showCard(c) {
  const d = $('#card-dialog');
  d.innerHTML = `<div class="dialog-head"><h2>${esc(c.name)}</h2><button class="icon-btn" data-close aria-label="Close"><svg><use href="#i-x"/></svg></button></div>
    <div class="card-detail"><div class="tilt">${foil({ img: c.imgLarge || c.img, name: c.name })}</div>
    <div><p class="muted">${esc(c.setName)} · #${esc(c.number)}<br>${esc(c.rarity || '')}${c.nameLocal ? '<br>' + esc(c.nameLocal) : ''}</p>
      <div class="tiles">
        <div class="tile"><div class="k">Market</div><div class="v">${money(c.price)}</div></div>
        <div class="tile"><div class="k">Reverse holo</div><div class="v">${money(c.reversePrice)}</div></div>
      </div>
      ${c.priceKnown ? '' : '<p class="muted small">No market price yet; estimated from similar cards in the set.</p>'}
      ${c.owned != null ? `<p>You have <b>${c.owned}</b>${c.variants && c.variants.length && c.variants[0] ? ' (' + esc(c.variants.join(', ')) + ')' : ''}.</p>` : ''}
      <button class="btn primary" data-add-key="${esc(c.key)}" data-direct><svg><use href="#i-plus"/></svg>Add to collection</button>
    </div></div>`;
  const tilt = $('.tilt', d), f = $('.foil', tilt);
  tilt.onmousemove = (e) => {
    const r = tilt.getBoundingClientRect();
    const x = (e.clientX - r.left) / r.width - 0.5, y = (e.clientY - r.top) / r.height - 0.5;
    f.style.transform = `rotateY(${x * 16}deg) rotateX(${-y * 16}deg)`;
  };
  tilt.onmouseleave = () => { f.style.transform = ''; };
  d.showModal();
}

// ------------------------------------------------------------ binder (set progress)
let bSet = null, bFilter = 'all', bSort = 'number', bView = 'binder', bPage = 0, bData = null, bLang = '';
async function showBinder() {
  await loadSets(true);
  langSeg($('#bs-lang'), bLang, true, (v) => { bLang = v; renderSetBrowser(); });
  if (!bSet) bSet = S.settings.active_set || (SETS.find((s) => s.owned) || SETS[SETS.length - 1] || {}).id;
  renderSetBrowser();
  if (bSet) loadBinderSet(bSet);
}
function renderSetBrowser() {
  const q = $('#bs-search').value.trim().toLowerCase(), started = $('#bs-started').checked;
  const list = SETS.filter((s) => (!bLang || s.lang === bLang) && (!started || s.owned) && (!q || `${s.name} ${s.series} ${s.release}`.toLowerCase().includes(q)))
    .sort((a, b) => (b.owned > 0) - (a.owned > 0) || b.release.localeCompare(a.release));
  $('#bs-list').innerHTML = list.slice(0, 400).map((s) => `<li data-bset="${esc(s.id)}" class="${s.id === bSet ? 'active' : ''}">
    ${s.symbol ? `<img class="sym" src="${esc(s.symbol)}" alt="" loading="lazy">` : '<span class="sym"></span>'}
    <div class="nm"><div>${esc(s.name)}</div><div class="bar thin"><i style="width:${pct(s.owned, s.mainCount || s.total)}%"></i></div></div>
    <span class="ct">${s.owned}/${s.mainCount || s.total}</span></li>`).join('');
}
async function loadBinderSet(id) {
  if (id !== bSet) bPage = 0;
  bSet = id;
  $$('#bs-list li').forEach((li) => li.classList.toggle('active', li.dataset.bset === id));
  bData = await api.get('/api/set/' + encodeURIComponent(id));
  renderBinder();
}
function ring(value, total, label) {
  const r = 38, c = 2 * Math.PI * r, f = total ? value / total : 0;
  return `<div><div class="ring"><svg viewBox="0 0 92 92"><circle class="bg" cx="46" cy="46" r="${r}"/><circle class="fg" cx="46" cy="46" r="${r}" stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - f)}" stroke-linecap="round"/></svg><b>${Math.floor(f * 100)}%</b></div><small>${label}</small></div>`;
}
function binderCards() {
  let cards = bData.cards.slice();
  const hit = S.settings.hit_value;
  if (bFilter === 'owned') cards = cards.filter((c) => c.owned);
  if (bFilter === 'missing') cards = cards.filter((c) => !c.owned);
  if (bFilter === 'dupes') cards = cards.filter((c) => c.owned > 1);
  if (bFilter === 'hits') cards = cards.filter((c) => c.price >= hit);
  if (bFilter === 'main') cards = cards.filter((c) => c.mainSet);
  if (bSort === 'price') cards.sort((a, b) => b.price - a.price);
  else if (bSort === 'rarity') cards.sort((a, b) => (a.rarity || '').localeCompare(b.rarity || '') || a.order - b.order);
  else cards.sort((a, b) => a.order - b.order);
  return cards;
}
function pocket(c) {
  if (!c) return '<div class="pocket"></div>';
  if (c.owned) return `<div class="pocket" data-tile="${esc(c.key)}" title="${esc(c.name)} · ${money(c.price)}">${foil(c)}${c.owned > 1 ? `<span class="count">×${c.owned}</span>` : ''}</div>`;
  return `<div class="pocket missing" data-tile="${esc(c.key)}" title="${esc(c.name)} · ${money(c.price)}">${c.img ? `<img class="ghost" src="${esc(c.img)}" alt="" loading="lazy">` : ''}
    <div class="ph"><span class="n">${esc(c.number)}</span><span class="t">${esc(c.name)}</span><span class="pr">${money(c.price)}</span></div></div>`;
}
function renderBinder() {
  const d = bData;
  if (!d || d.error) { $('#binder-main').innerHTML = '<div class="glass muted">Pick a set.</div>'; return; }
  const s = d.set, pk = d.packs || {};
  const cards = binderCards();
  const perSpread = 18, spreads = Math.max(1, Math.ceil(cards.length / perSpread));
  bPage = Math.min(bPage, spreads - 1);
  let body;
  if (bView === 'binder') {
    const slice = cards.slice(bPage * perSpread, (bPage + 1) * perSpread);
    const page = (arr) => `<div class="page">${Array.from({ length: 9 }, (_, i) => pocket(arr[i])).join('')}</div>`;
    body = `<div class="spread">${page(slice.slice(0, 9))}${page(slice.slice(9, 18))}</div>
      <div class="pager"><button class="icon-btn" data-bpage="-1" aria-label="Previous pages"${bPage ? '' : ' disabled'}><svg><use href="#i-chev-l"/></svg></button>
      <span>Pages ${bPage * 2 + 1}–${bPage * 2 + 2} of ${spreads * 2}</span>
      <button class="icon-btn" data-bpage="1" aria-label="Next pages"${bPage < spreads - 1 ? '' : ' disabled'}><svg><use href="#i-chev-r"/></svg></button></div>`;
  } else {
    body = `<div class="card-grid" style="margin-top:0">${cards.map((c) => `<div class="cell ${c.owned ? '' : 'missing'}" data-tile="${esc(c.key)}">${foil(c)}${c.owned > 1 ? `<span class="count">×${c.owned}</span>` : ''}
      <div class="meta"><span>#${esc(c.number)} ${esc(c.name)}</span><b>${money(c.price)}</b></div></div>`).join('') || '<p class="muted">No cards here.</p>'}</div>`;
  }
  $('#binder-main').innerHTML = `<div class="glass">
      <div class="binder-hero">
        ${s.logo ? `<img class="logo-img" src="${esc(s.logo)}" alt="">` : '<span></span>'}
        <div>
          <h2 style="font-size:24px">${esc(s.name)}</h2>
          <div class="muted">${esc(s.series)} · ${esc(s.release)} · ${s.total} cards${s.estimated ? ' · prices mostly estimated' : ''}</div>
          <div class="rarities">${d.rarities.map((r) => `<span class="rarity">${esc(r.rarity)} <b>${r.owned}/${r.total}</b></span>`).join('')}</div>
        </div>
        <div class="rings">${ring(d.main_owned, d.main_total, `Set ${d.main_owned}/${d.main_total}`)}${ring(d.master_owned, d.master_total, `Master ${d.master_owned}/${d.master_total}`)}</div>
      </div>
      <div class="tiles" style="margin-top:16px">
        <div class="tile"><div class="k">Your cards are worth</div><div class="v">${money(d.owned_value)}</div></div>
        <div class="tile"><div class="k">Missing cards cost</div><div class="v">${money(d.missing_value)}</div></div>
        <div class="tile"><div class="k">Packs opened</div><div class="v">${pk.packs || 0}</div></div>
        <div class="tile"><div class="k">Spent / pulled</div><div class="v">${money(pk.cost || 0)} / ${money(pk.value || 0)}</div></div>
      </div>
      <div class="row gap wrap" style="margin-top:14px">
        <button class="btn primary" data-pick-set="${esc(s.id)}"><svg><use href="#i-scan"/></svg>Open this set</button>
        ${d.scannable ? '<span class="muted small">Ready to scan</span>' : `<button class="btn" data-prepare="${esc(s.id)}"><svg><use href="#i-download"/></svg>Get ready to scan</button>`}
      </div>
    </div>
    <div class="glass">
      <div class="toolbar">
        <div class="seg" id="b-view"><button data-bview="binder" class="${bView === 'binder' ? 'active' : ''}"><svg style="width:15px;height:15px;vertical-align:-3px"><use href="#i-binder"/></svg> Binder</button><button data-bview="grid" class="${bView === 'grid' ? 'active' : ''}"><svg style="width:15px;height:15px;vertical-align:-3px"><use href="#i-grid"/></svg> Grid</button></div>
        <div class="seg">${[['all', 'All'], ['owned', 'Have'], ['missing', 'Missing'], ['dupes', 'Duplicates'], ['hits', 'Hits'], ['main', 'Main set']].map(([k, l]) => `<button data-filter="${k}" class="${bFilter === k ? 'active' : ''}">${l}</button>`).join('')}</div>
        <select id="b-sort"><option value="number">By number</option><option value="price">By price</option><option value="rarity">By rarity</option></select>
      </div>
      ${body}
    </div>`;
  $('#b-sort').value = bSort;
}

// ------------------------------------------------------------ packs
let packsOffset = 0, packsAll = [];
async function showPacks(append) {
  await loadSets();
  const sel = $('#packs-set');
  if (!sel.dataset.filled) {
    sel.innerHTML = '<option value="">All sets</option>' + SETS.filter((s) => s.owned).map((s) => `<option value="${esc(s.id)}">${esc(s.name)}</option>`).join('');
    sel.dataset.filled = '1';
  }
  if (!append) { packsOffset = 0; packsAll = []; }
  const r = await api.get(`/api/packs?limit=40&offset=${packsOffset}&set=${encodeURIComponent(sel.value)}`);
  const t = r.totals || {};
  const profit = (t.value || 0) - (t.cost || 0);
  $('#packs-tiles').innerHTML = `
    <div class="tile"><div class="k">Packs opened</div><div class="v">${t.packs || 0}</div></div>
    <div class="tile"><div class="k">Spent</div><div class="v">${money(t.cost || 0)}</div></div>
    <div class="tile"><div class="k">Pulled</div><div class="v">${money(t.value || 0)}</div></div>
    <div class="tile"><div class="k">Profit</div><div class="v ${profit >= 0 ? 'good' : 'bad'}">${signed(profit)}</div></div>
    <div class="tile"><div class="k">Average pack</div><div class="v">${money(t.packs ? t.value / t.packs : 0)}</div></div>
    <div class="tile"><div class="k">Best pack</div><div class="v">${money(t.best_pack || 0)}</div></div>`;
  packsAll = packsAll.concat(r.packs);
  const html = r.packs.map((p) => `<div class="pack-row" data-pack="${p.id}">
      <div class="top-line"><span class="num">#${p.id}</span><span class="set">${esc(p.setName)}<div class="muted small">${new Date(p.started * 1000).toLocaleString()} · ${p.cards}/${p.size} cards${p.ended ? (p.complete ? '' : ' · ended early') : ' · open now'}</div></span>
      <span class="muted small">${plural(p.dupes, 'dupe')}</span>
      <span class="val">${money(p.value)}</span>
      <span class="pl ${p.profit == null ? 'muted' : p.profit >= 0 ? 'good' : 'bad'}">${p.profit == null ? '' : signed(p.profit)}</span>
      <button class="icon-btn" data-del-pack="${p.id}" title="Delete this pack and its scans"><svg><use href="#i-trash"/></svg></button></div>
      <div class="cards" hidden></div></div>`).join('');
  if (append) $('#pack-list').insertAdjacentHTML('beforeend', html);
  else $('#pack-list').innerHTML = html || '<div class="glass muted">No packs yet. Open one on the Scan page!</div>';
  $('#packs-more').hidden = r.packs.length < 40;
  drawPackChart(packsAll.filter((p) => p.cards > 0).slice(0, 40).reverse());
}

function niceMax(v) {
  if (v <= 0) return 1;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  for (const m of [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}
function drawPackChart(packs) {
  const box = $('#pack-chart');
  $('#chart-legend').innerHTML = '<span><i></i>Pulled value</span><span><i class="tick"></i>Pack cost</span>';
  $('#chart-sub').textContent = packs.length ? `Your last ${plural(packs.length, 'pack')}, oldest first. Hover a bar for details; the list below has every number.` : '';
  if (!packs.length) { box.innerHTML = '<div class="chart-empty">Open a pack and it shows up here.</div>'; return; }
  const W = box.clientWidth || 800, H = box.clientHeight || 260;
  const m = { l: 58, r: 8, t: 22, b: 26 };
  const iw = W - m.l - m.r, ih = H - m.t - m.b;
  const top = niceMax(Math.max(...packs.map((p) => Math.max(p.value, p.cost || 0))) * 1.08);
  const y = (v) => m.t + ih - (v / top) * ih;
  const band = iw / packs.length, bw = Math.min(24, Math.max(4, band - 2));
  let g = '<g class="grid">', ax = '<g class="axis">';
  for (let i = 0; i <= 4; i++) {
    const v = (top / 4) * i, yy = y(v);
    g += `<line x1="${m.l}" x2="${W - m.r}" y1="${yy}" y2="${yy}"/>`;
    ax += `<text x="${m.l - 10}" y="${yy + 4}" text-anchor="end">${esc(money(v))}</text>`;
  }
  g += '</g>';
  const every = Math.max(1, Math.ceil(packs.length / 12));
  let bars = '', ticks = '', zones = '';
  let best = 0;
  packs.forEach((p, i) => { if (p.value > packs[best].value) best = i; });
  packs.forEach((p, i) => {
    const cx = m.l + band * i + band / 2, x = cx - bw / 2, yy = y(p.value), h = Math.max(1, m.t + ih - yy);
    const r = Math.min(4, h, bw / 2);
    bars += `<path class="bar-v" data-i="${i}" d="M${x},${m.t + ih} V${yy + r} Q${x},${yy} ${x + r},${yy} H${x + bw - r} Q${x + bw},${yy} ${x + bw},${yy + r} V${m.t + ih} Z"/>`;
    if (p.cost != null) ticks += `<line class="cost" x1="${x - 3}" x2="${x + bw + 3}" y1="${y(p.cost)}" y2="${y(p.cost)}"/>`;
    zones += `<rect class="hit-zone" data-i="${i}" x="${m.l + band * i}" y="${m.t}" width="${band}" height="${ih}"/>`;
    if (i % every === 0) ax += `<text x="${cx}" y="${H - 6}" text-anchor="middle">#${p.id}</text>`;
  });
  ax += '</g>';
  const bp = packs[best];
  const lbl = `<text class="lbl" x="${m.l + band * best + band / 2}" y="${y(bp.value) - 7}" text-anchor="middle">${esc(money(bp.value))}</text>`;
  box.innerHTML = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Pulled value per pack, with each pack's cost">${g}${ax}${bars}${ticks}${lbl}${zones}</svg>`;
  const tip = $('#tooltip');
  $$('.hit-zone', box).forEach((z) => {
    z.onmouseenter = () => {
      const p = packs[+z.dataset.i];
      box.classList.add('hovering');
      $$('.bar-v', box).forEach((b) => b.classList.toggle('on', b.dataset.i === z.dataset.i));
      tip.innerHTML = `<b>Pack #${p.id} · ${esc(p.setName)}</b><div><span>Pulled</span><span>${money(p.value)}</span></div>
        <div><span>Cost</span><span>${p.cost != null ? money(p.cost) : '—'}</span></div>
        ${p.profit != null ? `<div><span>Profit</span><span>${signed(p.profit)}</span></div>` : ''}<div><span>Cards</span><span>${p.cards}</span></div>`;
      tip.hidden = false;
    };
    z.onmousemove = (e) => { tip.style.left = Math.min(innerWidth - 220, e.clientX + 14) + 'px'; tip.style.top = (e.clientY + 14) + 'px'; };
    z.onmouseleave = () => { tip.hidden = true; box.classList.remove('hovering'); $$('.bar-v', box).forEach((b) => b.classList.remove('on')); };
    z.onclick = () => { const row = $(`.pack-row[data-pack="${packs[+z.dataset.i].id}"]`); if (row) { row.scrollIntoView({ behavior: 'smooth', block: 'center' }); togglePack(row, true); } };
  });
}
async function togglePack(row, open) {
  const box = $('.cards', row);
  if (!box.hidden && !open) { box.hidden = true; return; }
  const p = await api.get('/api/pack/' + row.dataset.pack);
  box.innerHTML = p.scans.map((sc) => `<div class="mini ${sc.dupe ? 'dupe' : ''}" data-card="${esc(sc.card_key)}">${foil(sc.card)}<div class="p">${money(sc.price)}</div></div>`).join('') || '<span class="muted">No cards.</span>';
  box.hidden = false;
}

// ------------------------------------------------------------ collection
async function showCollection() {
  const r = await api.get(`/api/collection?sort=${$('#col-sort').value}&q=${encodeURIComponent($('#col-search').value)}`);
  const t = r.totals || {};
  $('#col-tiles').innerHTML = `
    <div class="tile"><div class="k">Different cards</div><div class="v">${t.unique_cards || 0}</div></div>
    <div class="tile"><div class="k">Cards pulled</div><div class="v">${t.scans || 0}</div></div>
    <div class="tile"><div class="k">Duplicates</div><div class="v">${t.dupes || 0}</div></div>
    <div class="tile"><div class="k">Collection value</div><div class="v">${money(r.value)}</div></div>`;
  window._col = r.cards;
  $('#col-grid').innerHTML = r.cards.map((c, i) => `<div class="cell" data-col="${i}">${foil(c, c.price >= (S ? S.settings.hit_value : 5) ? 'shine' : '')}${c.owned > 1 ? `<span class="count">×${c.owned}</span>` : ''}
    <div class="meta"><span>${esc(c.name)}</span><b>${money(c.price)}</b></div><div class="muted small">${esc(c.setName)}</div></div>`).join('')
    || '<p class="muted">Nothing scanned yet.</p>';
}

// ------------------------------------------------------------ settings
const SETTINGS = [
  { id: 'camera', title: 'Camera', items: [
    { key: 'camera', label: 'Camera', type: 'camera', help: 'A webcam on a stand pointing at where you open packs works best. The headset camera only works on headsets that share it with apps through SteamVR (like the Valve Index); the Steam Frame doesn’t yet, so Automatic picks your webcam.' },
    { key: 'camera_res', label: 'Resolution' },
    { key: 'camera_fps', label: 'Frame rate', type: 'number' },
    { key: 'camera_rotate', label: 'Rotate image', labels: { 0: 'No', 90: '90°', 180: '180°', 270: '270°' } },
    { key: 'camera_mirror', label: 'Mirror image', type: 'bool' },
    { key: 'stereo_eye', label: 'Headset camera: which eye', labels: { left: 'Left', right: 'Right', full: 'Whole frame' }, help: 'SteamVR sends both eyes in one picture. The scanner reads one of them.' },
    { key: 'camera_exposure', label: 'Exposure', type: 'range', step: 1, help: '0 = automatic. Turn it down if shiny cards wash out.' },
    { key: 'show_preview', label: 'Show the camera on the Scan page', type: 'bool' },
    { action: 'restart_camera', label: 'Restart camera' },
  ] },
  { id: 'scanning', title: 'Scanning', items: [
    { key: 'sensitivity', label: 'Scan sensitivity', labels: { relaxed: 'Relaxed: fastest', balanced: 'Balanced', strict: 'Strict: fewest mistakes' } },
    { key: 'use_keypoints', label: 'Recognise cards by their artwork', type: 'bool', help: 'Finds cards even when their edges are hard to see: busy tables, sleeves, toploaders, fingers over the border. Recommended.' },
    { key: 'max_cards', label: 'Cards scanned at once', type: 'number', help: 'Lay several new cards under the camera and they are all scanned together.' },
    { key: 'detect_mode', label: 'Finding the card’s edges', labels: { auto: 'Automatic', both: 'Automatic, else guide box', guide: 'Guide box only' }, help: 'Use the guide box if your camera struggles to see card edges: hold the card to fill the box.' },
    { key: 'guide_size', label: 'Guide box size', type: 'range', step: 0.01, pct: true },
    { key: 'scope', label: 'Cards to look for', labels: { pack: 'Only the set I am opening', selected: 'That set + sets I pick', prepared: 'Every downloaded set' }, help: 'Fewer sets = faster and more accurate.' },
    { key: 'scope_sets', label: 'Extra sets to look for', type: 'sets' },
    { key: 'rescan_gap', label: 'Card must leave view for', type: 'range', step: 0.1, unit: 's', help: 'Stops a card you are still holding from being scanned twice.' },
    { key: 'same_in_pack', label: 'Same card again in one pack', labels: { ignore: 'Ignore (you showed it twice)', allow: 'Count it again' } },
    { key: 'duplicates', label: 'Cards you already own', labels: { count: 'Count the spare copies', ignore: 'Flag them, don’t count spares' } },
    { key: 'max_fps', label: 'Max scans per second', type: 'number', help: 'Lower it to use less CPU while a game runs.' },
  ] },
  { id: 'packs', title: 'Packs', items: [
    { key: 'pack_size', label: 'Cards per pack', type: 'number', help: '0 = the set’s normal pack size (code cards aren’t scanned, so don’t count them).' },
    { key: 'pack_cost', label: 'Price you paid per pack (US$)', type: 'money', help: 'Empty = the sealed pack price from MivimooseRips.' },
    { key: 'auto_next_pack', label: 'Start the next pack automatically', type: 'bool' },
    { key: 'default_variant', label: 'Scanned cards count as', labels: { normal: 'Normal', reverse: 'Reverse holo', holo: 'Holo' } },
  ] },
  { id: 'sound', title: 'Sound & voice', items: [
    { key: 'sound', label: 'Sound effects', type: 'bool' },
    { key: 'volume', label: 'Volume', type: 'range', step: 0.05, pct: true },
    { key: 'snd_new', label: 'New card', type: 'bool', test: 'new' },
    { key: 'snd_dupe', label: 'Duplicate', type: 'bool', test: 'dupe' },
    { key: 'snd_hit', label: 'Hits', type: 'bool', test: 'hit', test2: 'big' },
    { key: 'snd_pack', label: 'Pack complete', type: 'bool', test: 'pack' },
    { key: 'snd_milestone', label: 'Set milestones (25/50/75/100%)', type: 'bool', test: 'milestone' },
    { key: 'snd_ignored', label: 'Card already in this pack', type: 'bool', test: 'ignored' },
    { key: 'snd_reading', label: 'Tick when a card starts being read', type: 'bool', test: 'reading' },
    { key: 'snd_ui', label: 'Interface sounds', type: 'bool', test: 'deal', help: 'Clicks, page turns, pack shuffles, pause and resume, VR panel buttons.' },
    { key: 'hit_value', label: 'A “hit” is worth at least (US$)', type: 'money' },
    { key: 'big_hit_value', label: 'A “big hit” is worth at least (US$)', type: 'money' },
    { key: 'jackpot_value', label: 'A “jackpot” is worth at least (US$)', type: 'money', help: 'Really expensive pulls play the jackpot sound.' },
    { key: 'jackpot_sound', label: 'Jackpot sound', test: 'jackpot', labels: { gunshot: 'Gunshot', explosion: 'Explosion', cash: 'Cash register', airhorn: 'Air horn', laser: 'Laser', fanfare: 'Fanfare' } },
    { key: 'voice', label: 'Read prices aloud', labels: { off: 'Off', price: 'Price only', name_price: 'Card name + price', hits: 'Only for hits' } },
    { key: 'voice_rate', label: 'Voice speed', type: 'range', step: 1 },
    { action: 'test_voice', label: 'Test voice' },
  ] },
  { id: 'prices', title: 'Prices', items: [
    { key: 'currency', label: 'Currency', help: 'Prices are TCGplayer market prices in US dollars, converted with daily rates.' },
    { key: 'price_basis', label: 'Collection value uses', labels: { market: 'Today’s market price', scan: 'Price when scanned' } },
  ] },
  { id: 'vr', title: 'VR headset', items: [
    { key: 'vr_enabled', label: 'Show the scanner panel in VR', type: 'bool' },
    { key: 'hud_mode', label: 'Panel position', labels: { world: 'Floating in front of you', head: 'Follows your head', left: 'Left hand', right: 'Right hand' } },
    { key: 'hud_width', label: 'Panel size', type: 'range', step: 0.01, unit: ' m' },
    { key: 'hud_distance', label: 'Distance', type: 'range', step: 0.05, unit: ' m' },
    { key: 'hud_height', label: 'Height (from eye level)', type: 'range', step: 0.01, unit: ' m' },
    { key: 'hud_opacity', label: 'Opacity', type: 'range', step: 0.02, pct: true },
    { key: 'hud_camera', label: 'Show the camera on the panel', type: 'bool' },
    { key: 'hud_fps', label: 'Panel refresh rate', type: 'number' },
    { key: 'vr_autostart', label: 'Start with SteamVR', type: 'bool', help: 'Only works for the installed .exe.' },
    { key: 'vr_flip_y', label: 'Fix upside-down clicks', type: 'bool', help: 'Turn this off if pressing a button on the VR panel triggers the one above or below it.' },
    { action: 'vr_check', label: 'Run VR check', help: 'Shows what SteamVR offers this app: headset, controllers, and whether the headset camera can be used.' },
  ] },
  { id: 'data', title: 'Data', items: [
    { key: 'site_url', label: 'MivimooseRips site', type: 'text', help: 'Card list and prices come from here.' },
    { key: 'refresh_hours', label: 'Refresh prices every (hours)', type: 'number' },
    { key: 'lan', label: 'Allow other devices on my network', type: 'bool', help: 'Open this dashboard from the headset browser or a phone. Takes effect after a restart.' },
    { key: 'port', label: 'Port', type: 'number', help: 'Takes effect after a restart.' },
    { action: 'reload_data', label: 'Refresh card data now' },
    { action: 'clear_cache', label: 'Re-download scan data for all sets' },
  ] },
];

async function renderSettings() {
  if (!S) return;
  renderSettings.built = true;
  const v = S.settings, ch = S.choices, rg = S.ranges;
  let cams = [];
  try { cams = (await api.get('/api/cameras')).cameras; } catch (e) { /* ignore */ }
  await loadSets();
  const ctl = (it) => {
    const k = it.key, val = v[k], type = it.type || 'select';
    if (type === 'bool') return `<label class="switch"><input type="checkbox" data-key="${k}" ${val ? 'checked' : ''}><span></span></label>`;
    if (type === 'camera') return `<select data-key="${k}">${cams.map((c) => `<option value="${esc(c.id)}" ${c.id === val ? 'selected' : ''}>${esc(c.name)}</option>`).join('')}${cams.some((c) => c.id === val) ? '' : `<option selected value="${esc(val)}">${esc(val)}</option>`}</select>`;
    if (type === 'range') {
      const [lo, hi] = rg[k];
      return `<div class="range"><input type="range" data-key="${k}" min="${lo}" max="${hi}" step="${it.step}" value="${val}"><output>${it.pct ? Math.round(val * 100) + '%' : val + (it.unit || '')}</output></div>`;
    }
    if (type === 'number') return `<input type="number" data-key="${k}" value="${val}" min="${rg[k] ? rg[k][0] : ''}" max="${rg[k] ? rg[k][1] : ''}">`;
    if (type === 'money') return `<input type="number" step="0.01" min="0" data-key="${k}" value="${val ?? ''}" placeholder="automatic">`;
    if (type === 'text') return `<input type="text" data-key="${k}" value="${esc(val)}">`;
    if (type === 'sets') return `<select data-key="scope_sets_add"><option value="">Add a set…</option>${SETS.filter((s) => !val.includes(s.id)).map((s) => `<option value="${esc(s.id)}">${esc(s.name)}${s.lang !== 'en' ? ' (' + esc(s.lang) + ')' : ''}</option>`).join('')}</select>
        ${val.map((id) => { const s = SETS.find((x) => x.id === id); return `<button class="btn sm" data-remove-scope="${esc(id)}">${esc(s ? s.name : id)} <svg style="width:13px;height:13px"><use href="#i-x"/></svg></button>`; }).join('')}`;
    return `<select data-key="${k}">${(ch[k] || []).map((o) => `<option value="${esc(o)}" ${String(o) === String(val) ? 'selected' : ''}>${esc((it.labels && it.labels[o]) || o)}</option>`).join('')}</select>`;
  };
  const sections = SETTINGS.map((sec) => `<div class="glass" id="set-${sec.id}"><h2 style="margin-bottom:8px">${sec.title}</h2>${sec.items.map((it) => {
    if (it.action) return `<div class="setting"><div><div class="lbl">${it.label}</div>${it.help ? `<div class="help">${it.help}</div>` : ''}</div><div class="ctl"><button class="btn sm" data-act="${it.action}">${it.label}</button></div></div>`;
    const tests = it.test ? `<button class="icon-btn" data-test="${it.test}" title="Play"><svg style="width:16px;height:16px"><use href="#i-play"/></svg></button>${it.test2 ? `<button class="icon-btn" data-test="${it.test2}" title="Play the big-hit sound"><svg style="width:16px;height:16px"><use href="#i-spark"/></svg></button>` : ''}` : '';
    return `<div class="setting"><div><div class="lbl">${it.label}</div>${it.help ? `<div class="help">${it.help}</div>` : ''}</div><div class="ctl">${tests}${ctl(it)}</div></div>`;
  }).join('')}</div>`).join('') + `<div class="glass" id="set-danger"><h2 style="margin-bottom:8px">Danger zone</h2>
    <div class="setting"><div><div class="lbl">Delete my whole collection</div><div class="help">Removes every scan and pack. Can’t be undone.</div></div><div class="ctl"><button class="btn sm danger" id="reset-all">Delete everything</button></div></div>
    <div class="setting"><div><div class="lbl">Quit the scanner</div><div class="help">Version ${esc(S.version)}</div></div><div class="ctl"><button class="btn sm" id="quit-app">Quit</button></div></div></div>`;
  $('#settings').innerHTML = sections;
  $('#settings-nav').innerHTML = SETTINGS.map((s) => `<a href="#set-${s.id}" data-snav="${s.id}">${s.title}</a>`).join('') + '<a href="#set-danger" data-snav="danger">Danger zone</a>';
}
async function saveSetting(key, value) {
  try {
    const r = await api.post('/api/settings', { changes: { [key]: value } });
    if (S) S.settings = r.settings;
    if (Object.keys(r.changed).length) toast('Saved', 'good', 1200);
    refreshSoon();
  } catch (e) { toast(esc(e.message), 'bad'); }
}
async function runVrCheck() {
  toast('Checking SteamVR…', 'info', 2500);
  const r = await api.get('/api/vr/check');
  const h = r.headset || {};
  const camLine = r.camera_present ? (r.camera_frame ? `Yes, ${r.camera_frame.width}×${r.camera_frame.height} frames` : 'Reported, but no frame arrived') : 'Not available to apps';
  const d = $('#card-dialog');
  d.innerHTML = `<div class="dialog-head"><h2>VR check</h2><button class="icon-btn" data-close aria-label="Close"><svg><use href="#i-x"/></svg></button></div>
    <dl class="facts">
      <dt>SteamVR</dt><dd>${r.connected ? 'Connected' : esc(r.error || (r.steamvr_running ? 'Running, not connected' : 'Not running'))}</dd>
      ${r.connected ? `<dt>Headset</dt><dd>${esc(h.maker || '')} ${esc(h.model || '')} <span class="muted">(${esc(h.driver || '')})</span></dd>
      <dt>Controllers</dt><dd>${(r.controllers || []).length}</dd>
      <dt>Headset camera</dt><dd>${camLine}</dd>` : ''}
      <dt>App status</dt><dd>${esc(r.app_status || '')}</dd>
    </dl>
    ${r.camera_frame ? `<img src="/vr-camera-test.jpg?t=${Date.now()}" style="max-width:100%;border-radius:14px">` : ''}
    <details><summary class="muted small">Full report</summary><pre class="report">${esc(JSON.stringify(r, null, 1))}</pre></details>`;
  d.showModal();
}

// ------------------------------------------------------------ events
function connectEvents() {
  const es = new EventSource('/api/events');
  es.onmessage = (m) => {
    const ev = JSON.parse(m.data);
    switch (ev.type) {
      case 'scan': {
        const sc = ev.scan, c = sc.card || {};
        if (sc.price >= (S ? S.settings.jackpot_value : 100)) celebrate('JACKPOT!', money(sc.price), c.name);
        else if (sc.price >= (S ? S.settings.big_hit_value : 50)) celebrate('Big hit!', money(sc.price), c.name);
        else if (sc.dupe) toast(`Duplicate: <b>${esc(c.name)}</b> · ${money(sc.price)}`, 'bad');
        else if (sc.price >= (S ? S.settings.hit_value : 5)) toast(`Hit! <b>${esc(c.name)}</b> · ${money(sc.price)}`, 'hit');
        refreshSoon(0);
        break;
      }
      case 'pack_done': {
        const p = ev.pack;
        celebrate(`Pack ${p.number} done`, money(p.value), p.profit != null ? `${signed(p.profit)} against the pack price · ${p.new} new, ${plural(p.dupes, 'dupe')}` : `${p.new} new, ${plural(p.dupes, 'dupe')}`);
        refreshSoon(0);
        break;
      }
      case 'milestone':
        celebrate(ev.kind === 'master' ? 'Master set complete!' : `${ev.pct}% of the set!`, ev.name, 'Keep going');
        break;
      case 'ignored':
        toast(`${esc(ev.card.name)} is already in this pack, skipped`, 'info', 2200);
        break;
      case 'toast':
        toast(esc(ev.message), ev.level || 'info');
        break;
      case 'prep': case 'status':
        refreshSoon(300);
        break;
      default:
        refreshSoon();
    }
  };
}

// ------------------------------------------------------------ navigation & wiring
function showTab(t) {
  tab = t;
  if (location.hash.slice(1) !== t) history.replaceState(null, '', '#' + t);
  $$('#nav button').forEach((b) => b.classList.toggle('active', b.dataset.tab === t));
  $$('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + t));
  $('#page-title').textContent = TITLES[t];
  const img = $('#camera');
  if (t !== 'scan' && img.dataset.on) { img.removeAttribute('src'); delete img.dataset.on; }
  if (t === 'scan') refresh();
  if (t === 'binder') showBinder();
  if (t === 'packs') showPacks();
  if (t === 'collection') showCollection();
  if (t === 'settings') { renderSettings.built = false; renderSettings(); }
}

document.addEventListener('click', async (e) => {
  const t = e.target.closest('button, a[data-snav], [data-tile], [data-card], [data-add-key], [data-bset], .pack-row .top-line, [data-col]');
  if (!t) return;
  const d = t.dataset;
  if (d.tab) uiSound('tab');
  else if (d.bpage) uiSound('page');
  else if (d.close !== undefined) uiSound('close');
  else if (d.openSets !== undefined || d.tile || d.card || d.col !== undefined) uiSound('open');
  else if (!d.test) uiSound('click');
  if (d.tab) return showTab(d.tab);
  if (d.close !== undefined) return t.closest('dialog').close();
  if (d.openSets !== undefined) return openSetDialog();
  if (d.pickSet) {
    await act('select_set', { set_id: d.pickSet });
    $('#set-dialog').close();
    if (tab !== 'scan') showTab('scan'); else refreshSoon(0);
    return;
  }
  if (d.variant) { await act('set_variant', { scan_id: +d.scan, variant: d.variant }); return refreshSoon(0); }
  if (d.correct) { await act('correct', { scan_id: +d.scan, card_key: d.correct }); toast('Fixed', 'good', 1500); return refreshSoon(0); }
  if (d.wrong) return openAdd({ mode: 'correct', scanId: +d.wrong });
  if (d.delete) { await act('delete_scan', { scan_id: +d.delete }); return refreshSoon(0); }
  if (d.addKey) {
    if (addMode && addMode.mode === 'correct' && d.direct === undefined) await act('correct', { scan_id: addMode.scanId, card_key: d.addKey });
    else await act('manual_add', { card_key: d.addKey });
    const dlg = t.closest('dialog');
    if (dlg) dlg.close();
    addMode = null;
    toast('Added', 'good', 1500);
    if (tab === 'binder' && bSet) loadBinderSet(bSet);
    return refreshSoon(0);
  }
  if (d.card) {
    const [sid, , name] = d.card.split('|');
    const r = await api.get(`/api/search?q=${encodeURIComponent(name || '')}&set=${encodeURIComponent(sid)}&limit=60`);
    const c = r.cards.find((x) => x.key === d.card);
    if (c) showCard(c);
    return;
  }
  if (d.tile) { const c = bData && bData.cards.find((x) => x.key === d.tile); if (c) showCard(c); return; }
  if (d.col !== undefined) return showCard(window._col[+d.col]);
  if (d.bset) return loadBinderSet(d.bset);
  if (d.filter) { bFilter = d.filter; bPage = 0; return renderBinder(); }
  if (d.bview) { bView = d.bview; return renderBinder(); }
  if (d.bpage) { bPage = Math.max(0, bPage + +d.bpage); return renderBinder(); }
  if (d.prepare) { await act('prepare_set', { set_id: d.prepare }); toast('Downloading the set’s card images…'); return; }
  if (d.delPack) { e.stopPropagation(); if (confirm('Delete this pack and all its scans?')) { await act('delete_pack', { pack_id: +d.delPack }); showPacks(); } return; }
  if (t.matches('.pack-row .top-line')) return togglePack(t.closest('.pack-row'));
  if (d.test) return act('test_sound', { sound: d.test });
  if (d.snav) { e.preventDefault(); $$('#settings-nav a').forEach((a) => a.classList.toggle('active', a === t)); $('#set-' + d.snav).scrollIntoView({ behavior: 'smooth', block: 'start' }); return; }
  if (d.removeScope) { await saveSetting('scope_sets', S.settings.scope_sets.filter((x) => x !== d.removeScope)); renderSettings.built = false; return renderSettings(); }
  if (d.act === 'vr_check') return runVrCheck();
  if (d.act) {
    await act(d.act);
    if (d.act === 'reload_data') toast('Refreshing card data…');
    if (d.act === 'clear_cache') toast('Scan data cleared. Sets re-download when used.');
    return refreshSoon(0);
  }
  if (t.id === 'reset-all') {
    const w = prompt('This deletes every scan and pack. Type DELETE to confirm.');
    if (w === 'DELETE') { await act('reset_collection', { confirm: 'DELETE' }); toast('Collection deleted'); refreshSoon(0); }
    return;
  }
  if (t.id === 'quit-app') { if (confirm('Quit the scanner?')) api.post('/api/quit'); }
});

$('#btn-pause').onclick = () => act('pause').then(() => refreshSoon(0));
$('#btn-undo').onclick = () => act('undo').then(() => refreshSoon(0));
$('#btn-reverse').onclick = () => act('next_variant', { variant: S && S.next_variant === 'reverse' ? null : 'reverse' }).then(() => refreshSoon(0));
$('#btn-next').onclick = () => act('next_pack').then(() => refreshSoon(0));
$('#btn-add').onclick = () => openAdd({ mode: 'add' });
$('#set-search').oninput = renderSetChoices;
$('#add-search').oninput = () => { clearTimeout(addTimer); addTimer = setTimeout(runAddSearch, 180); };
$('#add-anyset').onchange = runAddSearch;
$('#bs-search').oninput = renderSetBrowser;
$('#bs-started').onchange = renderSetBrowser;
$('#packs-set').onchange = () => showPacks();
$('#packs-more').onclick = () => { packsOffset += 40; showPacks(true); };
$('#col-sort').onchange = showCollection;
let colTimer = null;
$('#col-search').oninput = () => { clearTimeout(colTimer); colTimer = setTimeout(showCollection, 200); };
window.addEventListener('resize', () => { if (tab === 'packs' && packsAll.length) drawPackChart(packsAll.filter((p) => p.cards > 0).slice(0, 40).reverse()); });
document.addEventListener('change', (e) => {
  const el = e.target;
  if (el.id === 'b-sort') { bSort = el.value; return renderBinder(); }
  const k = el.dataset.key;
  if (!k) return;
  if (k === 'scope_sets_add') { if (el.value) saveSetting('scope_sets', [...S.settings.scope_sets, el.value]).then(() => { renderSettings.built = false; renderSettings(); }); return; }
  let v = el.type === 'checkbox' ? el.checked : el.value;
  if (el.type === 'number' || el.type === 'range') v = el.value === '' ? null : Number(el.value);
  if (el.type === 'checkbox') uiSound(v ? 'on' : 'off');
  saveSetting(k, v);
});
document.addEventListener('input', (e) => {
  const el = e.target;
  if (el.type === 'range' && el.dataset.key) {
    const it = SETTINGS.flatMap((s) => s.items).find((x) => x.key === el.dataset.key) || {};
    el.nextElementSibling.textContent = it.pct ? Math.round(el.value * 100) + '%' : el.value + (it.unit || '');
  }
});
$$('dialog').forEach((d) => d.addEventListener('click', (e) => { if (e.target === d) d.close(); }));

document.addEventListener('keydown', (e) => {
  if (e.target.matches('input, select, textarea') || e.ctrlKey || e.metaKey || e.altKey || $('dialog[open]')) {
    if (e.key === 'z' && e.ctrlKey && !e.target.matches('input, textarea')) { e.preventDefault(); act('undo').then(() => refreshSoon(0)); }
    return;
  }
  const k = e.key.toLowerCase();
  if (tab === 'binder' && bView === 'binder' && (k === 'arrowright' || k === 'arrowleft')) { uiSound('page'); bPage = Math.max(0, bPage + (k === 'arrowright' ? 1 : -1)); return renderBinder(); }
  if (k === ' ') { e.preventDefault(); $('#btn-pause').click(); }
  else if (k === 'z') $('#btn-undo').click();
  else if (k === 'r') $('#btn-reverse').click();
  else if (k === 'n') $('#btn-next').click();
  else if (k === 'a') { e.preventDefault(); openAdd({ mode: 'add' }); }
  else if (k === 's') openSetDialog();
  else if (['1', '2', '3', '4', '5'].includes(k)) showTab(TABS[+k - 1]);
});

// ------------------------------------------------------------ start
refresh().then(() => {
  const h = location.hash.slice(1);
  if (h === 'progress') showTab('binder');
  else if (TABS.includes(h) && h !== 'scan') showTab(h);
  else if (S && S.state === 'no_set') setTimeout(openSetDialog, 400);
});
if (!NOLIVE) {
  connectEvents();
  pollLive();
  setInterval(() => { if (tab === 'scan' && document.visibilityState === 'visible') refreshSoon(0); }, 4000);
}
