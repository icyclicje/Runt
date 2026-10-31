'use strict';
/* MivimooseRips Scanner: desktop UI. Talks to the local server (same origin). */

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const api = {
  get: (p) => fetch(p, { cache: 'no-store' }).then((r) => r.json()),
  post: (p, body) => fetch(p, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Scanner': '1' }, body: JSON.stringify(body || {}) })
    .then(async (r) => { const j = await r.json(); if (!r.ok) throw new Error(j.error || r.statusText); return j; }),
};
const act = (action, extra = {}) => api.post('/api/action', { action, ...extra }).catch((e) => toast(e.message, 'bad'));

let S = null;            // latest /api/state
let SETS = [];           // set list
let tab = 'scan';
// ?nolive: no camera stream or event connection (still frame instead). For screenshots and debugging.
const NOLIVE = new URLSearchParams(location.search).has('nolive');
const LANGS = { en: 'English', ja: 'Japanese', ko: 'Korean', 'zh-tw': 'Chinese (Trad.)', 'zh-cn': 'Chinese (Simp.)', th: 'Thai', id: 'Indonesian' };

// ------------------------------------------------------------ money
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
const ago = (ts) => { const s = Date.now() / 1000 - ts; return s < 60 ? 'just now' : s < 3600 ? `${Math.floor(s / 60)} min ago` : s < 86400 ? `${Math.floor(s / 3600)} h ago` : new Date(ts * 1000).toLocaleDateString(); };
const cardImg = (c, cls = '') => c && c.img ? `<img class="${cls}" src="${esc(c.img)}" alt="${esc(c.name)}" loading="lazy" onerror="this.style.visibility='hidden'">` : `<div class="${cls} ph"></div>`;

// ------------------------------------------------------------ toasts & celebrations
function toast(msg, kind = 'info', ms = 3800) {
  const el = document.createElement('div');
  el.className = `toast ${kind}`;
  el.innerHTML = msg;
  $('#toasts').appendChild(el);
  setTimeout(() => el.remove(), ms);
}
function celebrate(title, big, sub) {
  const c = $('#celebrate');
  const colors = ['#ffcb05', '#3ee0a1', '#4c7dff', '#8b5cf6', '#ff6b7d'];
  let sparks = '';
  for (let i = 0; i < 40; i++) {
    sparks += `<i class="spark" style="left:${Math.random() * 100}vw;top:-20px;background:${colors[i % 5]};--dx:${(Math.random() - .5) * 200}px;animation-delay:${Math.random() * .4}s"></i>`;
  }
  c.innerHTML = `${sparks}<div class="box"><h2>${esc(title)}</h2><div class="big">${esc(big)}</div><div class="muted">${esc(sub || '')}</div></div>`;
  c.hidden = false;
  clearTimeout(celebrate.t);
  celebrate.t = setTimeout(() => { c.hidden = true; }, 3200);
}

// ------------------------------------------------------------ state
let refreshTimer = null;
function refreshSoon(delay = 80) { clearTimeout(refreshTimer); refreshTimer = setTimeout(refresh, delay); }
async function refresh() {
  try { S = await api.get('/api/state'); } catch (e) { return; }
  renderChips();
  if (tab === 'scan') renderScan();
  if (tab === 'settings' && !renderSettings.built) renderSettings();
}

function renderChips() {
  const cam = S.camera, vr = S.vr;
  const chips = [];
  const camOk = cam && cam.status === 'live';
  chips.push(`<span class="chip ${camOk ? 'ok' : cam ? 'err' : 'warn'}" title="${esc(cam ? cam.error || cam.name : 'No camera')}"><i class="dot"></i>${esc(cam ? cam.name : 'No camera')}${camOk && cam.fps ? ` · ${cam.fps} fps` : ''}</span>`);
  chips.push(`<span class="chip ${vr.connected ? 'ok' : 'warn'}" title="${esc(vr.status || '')}"><i class="dot"></i>${vr.connected ? 'VR panel on' : 'VR: ' + esc(vr.status || 'off')}</span>`);
  if (S.matcher) chips.push(`<span class="chip ${S.matcher.cards ? 'ok' : 'warn'}"><i class="dot"></i>${S.matcher.cards} cards known</span>`);
  $('#status-chips').innerHTML = chips.join('');
}

// ------------------------------------------------------------ scan view
function renderScan() {
  renderCamera();
  renderSetCard();
  renderLast();
  renderPack();
  renderSession();
  renderRecent();
  const paused = S.settings.paused;
  $('#btn-pause').textContent = paused ? 'Resume scanning' : 'Pause';
  $('#btn-pause').classList.toggle('btn-primary', paused);
  const rev = S.next_variant === 'reverse';
  $('#btn-reverse').classList.toggle('on', rev);
  $('#btn-reverse').textContent = rev ? 'Next card: reverse holo ✓' : 'Next card: reverse holo';
  $('#btn-next').classList.toggle('pulse', S.state === 'pack_done');
  $('#perf').textContent = S.perf && S.perf.fps ? `scanning ${S.perf.fps} fps · ${S.perf.ms} ms per frame` : '';
}

function renderCamera() {
  const img = $('#camera');
  const live = S.camera && S.camera.status === 'live' && S.settings.show_preview;
  const want = NOLIVE ? '/frame.jpg?w=1280' : '/video.mjpg?w=1280&fps=15';
  if (live && !img.dataset.on) {
    img.src = want + '&t=' + Date.now();
    img.dataset.on = '1';
    img.onerror = () => { delete img.dataset.on; setTimeout(refreshSoon, 1500); };
  } else if (!live && img.dataset.on) {
    img.removeAttribute('src');
    delete img.dataset.on;
  }
  img.hidden = !live;
  const empty = $('#camera-empty');
  if (live) { empty.hidden = true; } else {
    empty.hidden = false;
    let title = 'Camera off', body = '';
    if (S.state === 'loading') { title = 'Loading the card database…'; body = esc(S.message); }
    else if (!S.settings.show_preview && S.camera && S.camera.status === 'live') { title = 'Camera preview hidden'; body = 'Scanning still runs. Turn the preview back on in Settings.'; }
    else if (S.camera) { title = 'Camera not ready'; body = esc(S.camera.error || 'Starting…') + '<br><br>Pick another camera in Settings → Camera.'; }
    else { title = 'No camera'; body = 'Plug in a webcam, or start SteamVR to use the headset camera. Then choose it in Settings → Camera.'; }
    empty.innerHTML = `<div><b>${title}</b>${body}</div>`;
  }
  renderLive();
}

let live = null;
function renderLive() {
  const st = $('#camera-status');
  const state = (live && live.state) || (S && S.state) || 'loading';
  st.className = 'camera-status ' + state;
  let msg = esc((live && live.message) || (S && S.message) || '');
  let extra = '';
  st.classList.remove('hint');
  if (live && state === 'scanning') {
    if (live.pendingName && live.progress > 0) {
      msg = `Reading ${esc(live.pendingName)}…`;
      extra = `<span class="prog"><i style="width:${Math.round(live.progress * 100)}%"></i></span>`;
    } else if (live.hintText) {
      msg = esc(live.hintText);
      st.classList.add('hint');
    } else if (live.lockedNames && live.lockedNames.length) {
      const n = live.lockedNames.length;
      msg = n === 1 ? `Got it: ${esc(live.lockedNames[0])} · show the next card` : `Got ${n} cards in view · show the next one`;
    }
  }
  st.innerHTML = `<i class="dot"></i><span>${msg}</span>${extra}`;
}
async function pollLive() {
  if (tab === 'scan' && document.visibilityState === 'visible') {
    try { live = await api.get('/api/live'); renderLive(); } catch (e) { /* server restarting */ }
  }
  setTimeout(pollLive, 250);
}

function renderSetCard() {
  const s = S.set, el = $('#set-card');
  if (!s) {
    el.innerHTML = `<div class="info"><h3>Opening</h3><div class="name">No set chosen</div><div class="muted small">Pick the set you are opening so the scanner knows its cards.</div></div>
      <button class="btn btn-primary" data-open-sets>Choose set</button>`;
    return;
  }
  const pr = S.progress || {};
  const prep = S.prep && S.prep.running && S.prep.total ? S.prep : null;
  el.innerHTML = `${s.logo ? `<img class="logo" src="${esc(s.logo)}" alt="">` : `<div class="logo none">${esc(s.lang.toUpperCase())}</div>`}
    <div class="info">
      <h3>Opening</h3>
      <div class="name" title="${esc(s.name)}">${esc(s.name)}</div>
      <div class="muted small">${esc(s.series)} · ${esc(s.release.slice(0, 4))} · ${s.packSize} cards/pack${s.sealedPrice ? ' · pack ≈ ' + money(s.sealedPrice) : ''}</div>
      ${pr.main_total ? `<div class="bar thin" style="margin-top:8px"><i style="width:${pr.main_pct}%"></i></div>
        <div class="muted small" style="margin-top:4px">${pr.main_owned}/${pr.main_total} set · ${pr.master_owned}/${pr.master_total} master</div>` : ''}
      ${prep ? `<div class="prep"><div class="small">Downloading ${esc(prep.name)} for scanning… ${prep.done}/${prep.total}</div><div class="bar thin violet"><i style="width:${pct(prep.done, prep.total)}%"></i></div></div>` : ''}
    </div>
    <button class="btn btn-sm" data-open-sets>Change</button>`;
}

function variantButtons(sc) {
  const v = sc.variant;
  return ['normal', 'reverse', 'holo'].map((k) => `<button class="btn btn-sm ${v === k ? 'btn-primary' : ''}" data-variant="${k}" data-scan="${sc.id}">${{ normal: 'Normal', reverse: 'Reverse holo', holo: 'Holo' }[k]}</button>`).join('');
}

let lastShownId = null;
function renderLast() {
  const el = $('#last-card'), sc = S.last;
  if (!sc || !sc.card) {
    el.innerHTML = `<div class="empty-last"><b>Ready to scan</b>Hold each card up to the camera, face out, until it beeps.<br>Duplicates and cards already in this pack are caught automatically.</div>`;
    return;
  }
  const c = sc.card;
  const hit = sc.price >= S.settings.hit_value;
  el.innerHTML = `${cardImg(c, 'img')}
    <div>
      <div class="row gap" style="flex-wrap:wrap">
        <span class="badge ${sc.dupe ? 'dupe' : 'new'}">${sc.dupe ? 'DUPLICATE' : 'NEW'}</span>
        ${hit ? '<span class="badge hit">HIT</span>' : ''}
        ${sc.source === 'manual' ? '<span class="badge est">ADDED BY HAND</span>' : ''}
        ${c.priceKnown ? '' : '<span class="badge est" title="No market price yet; estimated from similar cards">EST.</span>'}
      </div>
      <div class="name">${esc(c.name)}</div>
      <div class="muted small">${esc(c.setName)} · #${esc(c.number)} · ${esc(c.rarity || '')}</div>
      <div class="price">${money(sc.price)}</div>
      <div class="variants">${variantButtons(sc)}</div>
      <div class="row gap"><button class="btn btn-sm" data-wrong="${sc.id}">Wrong card?</button><button class="btn btn-sm btn-danger" data-delete="${sc.id}">Remove</button></div>
      ${sc.alts && sc.alts.length ? `<div class="alts"><div class="muted small">Or was it one of these?</div><div class="alts-row">${sc.alts.map((a) => `<button data-correct="${esc(a.key)}" data-scan="${sc.id}" title="${esc(a.name)} #${esc(a.number)}">${cardImg(a)}</button>`).join('')}</div></div>` : ''}
    </div>`;
  if (lastShownId !== null && lastShownId !== sc.id) {
    el.classList.remove('flash', 'dupe');
    void el.offsetWidth;
    el.classList.add('flash');
    el.classList.toggle('dupe', !!sc.dupe);
  }
  lastShownId = sc.id;
  $('.img', el).onclick = () => showCard(c);
}

function renderPack() {
  const el = $('#pack-card'), p = S.pack;
  if (!p) { el.innerHTML = '<h2>Pack tracker</h2><p class="muted">Choose a set to start tracking packs.</p>'; return; }
  const slots = [];
  for (let i = 0; i < Math.max(p.size, p.count); i++) {
    const sc = p.scans[i];
    if (sc) {
      const cls = sc.dupe ? 'dupe' : sc.price >= S.settings.hit_value ? 'hit' : '';
      slots.push(`<div class="slot ${cls}" title="${esc(sc.card ? sc.card.name : sc.name)} · ${money(sc.price)}">${cardImg(sc.card)}</div>`);
    } else slots.push('<div class="slot"></div>');
  }
  const profit = p.count ? p.profit : null;     // an empty pack has no result yet
  el.innerHTML = `<div class="pack-head"><h2>Pack ${p.number}</h2><span class="pack-count">${p.count}/${p.size}</span></div>
    <div class="pack-slots">${slots.join('')}</div>
    <div class="pack-money">
      <div><div class="k">Pulled</div><div class="v">${money(p.value)}</div></div>
      <div><div class="k">Pack cost</div><div class="v">${p.cost != null ? money(p.cost) : '—'}</div></div>
      <div><div class="k">Profit</div><div class="v ${profit == null ? '' : profit >= 0 ? 'good' : 'bad'}">${profit == null ? '—' : signed(profit)}</div></div>
    </div>
    <div class="muted small" style="margin-top:8px">${p.new} new · ${p.dupes} duplicate${p.dupes === 1 ? '' : 's'} · ${p.hits} hit${p.hits === 1 ? '' : 's'}${p.best ? ' · best: ' + esc(p.best.name) : ''}</div>`;
}

function renderSession() {
  const s = S.session || {}, pk = s.packs || {};
  $('#session-card').innerHTML = `<div class="panel-head"><h2>This session</h2><button class="btn btn-sm btn-ghost" data-act="reset_session">Reset</button></div>
    <div class="stats">
      <div class="stat"><div class="k">Packs</div><div class="v">${pk.packs || 0}</div></div>
      <div class="stat"><div class="k">Cards</div><div class="v">${s.scans || 0}</div></div>
      <div class="stat"><div class="k">Pulled</div><div class="v">${money(s.value || 0)}</div></div>
      <div class="stat"><div class="k">Profit</div><div class="v ${(s.value || 0) - (pk.cost || 0) >= 0 ? 'good' : 'bad'}">${signed((s.value || 0) - (pk.cost || 0))}</div></div>
    </div>`;
}

function renderRecent() {
  const el = $('#recent');
  const rows = S.recent || [];
  el.innerHTML = rows.length ? rows.map((sc) => `<div class="mini ${sc.dupe ? 'dupe' : ''}" data-card="${esc(sc.card_key)}" title="${esc(sc.name)} · ${esc(sc.set_id)}">${cardImg(sc.card)}<div class="p">${money(sc.price)}</div></div>`).join('')
    : '<span class="muted">Scanned cards show up here.</span>';
}

// ------------------------------------------------------------ set chooser
async function loadSets(force) {
  if (!SETS.length || force) SETS = (await api.get('/api/sets')).sets || [];
  return SETS;
}
function langOptions(sel, withAll) {
  const langs = [...new Set(SETS.map((s) => s.lang))];
  sel.innerHTML = (withAll ? '<option value="">All languages</option>' : '') + langs.map((l) => `<option value="${esc(l)}">${esc(LANGS[l] || l)}</option>`).join('');
}
async function openSetDialog() {
  await loadSets(true);
  const dlg = $('#set-dialog');
  if (!$('#set-lang').options.length) langOptions($('#set-lang'), false);
  renderSetChoices();
  dlg.showModal();
  $('#set-search').focus();
}
function renderSetChoices() {
  const q = $('#set-search').value.trim().toLowerCase();
  const lang = $('#set-lang').value || 'en';
  let list = SETS.filter((s) => s.lang === lang && (!q || `${s.name} ${s.nameLocal || ''} ${s.series} ${s.release}`.toLowerCase().includes(q)));
  list = list.slice().sort((a, b) => b.release.localeCompare(a.release));
  let html = '', series = null;
  for (const s of list) {
    if (s.series !== series) { series = s.series; html += `<div class="series-title">${esc(series)}</div>`; }
    html += `<button class="set-choice" data-pick-set="${esc(s.id)}">${s.logo ? `<img src="${esc(s.logo)}" alt="" loading="lazy">` : `<span class="ph">${esc(s.lang.toUpperCase())}</span>`}
      <span><div class="t">${esc(s.name)}</div><div class="s">${esc(s.release.slice(0, 4))} · ${s.total} cards${s.owned ? ` · you have ${s.owned}` : ''}${s.ready ? ' · ✓ ready' : ''}</div></span></button>`;
  }
  $('#set-choices').innerHTML = html || '<p class="muted">No sets match.</p>';
}

// ------------------------------------------------------------ add / correct dialog
let addMode = null;   // {mode:'add'} or {mode:'correct', scanId}
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
  $('#add-results').innerHTML = r.cards.length ? r.cards.map((c) => `<div class="add-row" data-add-key="${esc(c.key)}">${cardImg(c)}
    <div class="nm"><b>${esc(c.name)}</b><div class="muted small">${esc(c.setName)} · #${esc(c.number)} · ${esc(c.rarity || '')}</div></div><b>${money(c.price)}</b></div>`).join('')
    : `<p class="muted">Nothing found${set ? ' in this set. Tick “All sets” to search everywhere' : ''}.</p>`;
}

// ------------------------------------------------------------ card detail
function showCard(c) {
  const d = $('#card-dialog');
  d.innerHTML = `<div class="dialog-head"><h2>${esc(c.name)}</h2><button class="btn btn-ghost" data-close>✕</button></div>
    <div class="card-detail"><img src="${esc(c.imgLarge || c.img)}" alt="">
    <div><p class="muted">${esc(c.setName)} · #${esc(c.number)}<br>${esc(c.rarity || '')}${c.nameLocal ? '<br>' + esc(c.nameLocal) : ''}</p>
      <div class="stats"><div class="stat"><div class="k">Market</div><div class="v">${money(c.price)}</div></div>
      <div class="stat"><div class="k">Reverse holo</div><div class="v">${money(c.reversePrice)}</div></div></div>
      ${c.priceKnown ? '' : '<p class="muted small">No market price yet; estimated from similar cards in the set.</p>'}
      ${c.owned != null ? `<p>You have <b>${c.owned}</b>${c.variants && c.variants.length ? ' (' + esc(c.variants.join(', ')) + ')' : ''}.</p>` : ''}
      <div class="row gap" style="margin-top:12px"><button class="btn btn-primary" data-add-key="${esc(c.key)}" data-direct>Add to collection</button></div>
    </div></div>`;
  d.showModal();
}

// ------------------------------------------------------------ set progress view
let progSet = null, progFilter = 'all', progSort = 'number', progData = null;
async function showProgress() {
  await loadSets(true);
  const sel = $('#prog-lang');
  if (!sel.options.length) langOptions(sel, true);
  if (!progSet) progSet = S.settings.active_set || (SETS.find((s) => s.owned) || SETS[SETS.length - 1] || {}).id;
  renderProgSets();
  if (progSet) loadProgSet(progSet);
}
function renderProgSets() {
  const q = $('#prog-search').value.trim().toLowerCase(), lang = $('#prog-lang').value, started = $('#prog-started').checked;
  const list = SETS.filter((s) => (!lang || s.lang === lang) && (!started || s.owned) && (!q || `${s.name} ${s.series} ${s.release}`.toLowerCase().includes(q)))
    .sort((a, b) => (b.owned > 0) - (a.owned > 0) || b.release.localeCompare(a.release));
  $('#prog-sets').innerHTML = list.slice(0, 400).map((s) => `<li data-prog="${esc(s.id)}" class="${s.id === progSet ? 'active' : ''}">
    ${s.symbol ? `<img class="sym" src="${esc(s.symbol)}" alt="" loading="lazy">` : '<span class="sym"></span>'}
    <div class="nm"><div>${esc(s.name)}</div><div class="bar thin"><i style="width:${pct(s.owned, s.mainCount || s.total)}%"></i></div></div>
    <span class="ct">${s.owned}/${s.mainCount || s.total}</span></li>`).join('');
}
async function loadProgSet(id) {
  progSet = id;
  $$('#prog-sets li').forEach((li) => li.classList.toggle('active', li.dataset.prog === id));
  progData = await api.get('/api/set/' + encodeURIComponent(id));
  renderProgMain();
}
function renderProgMain() {
  const d = progData;
  if (!d || d.error) { $('#prog-main').innerHTML = '<div class="panel muted">Pick a set.</div>'; return; }
  const s = d.set;
  let cards = d.cards.slice();
  const hit = S.settings.hit_value;
  if (progFilter === 'owned') cards = cards.filter((c) => c.owned);
  if (progFilter === 'missing') cards = cards.filter((c) => !c.owned);
  if (progFilter === 'dupes') cards = cards.filter((c) => c.owned > 1);
  if (progFilter === 'hits') cards = cards.filter((c) => c.price >= hit);
  if (progFilter === 'main') cards = cards.filter((c) => c.mainSet);
  if (progSort === 'price') cards.sort((a, b) => b.price - a.price);
  else if (progSort === 'rarity') cards.sort((a, b) => (a.rarity || '').localeCompare(b.rarity || '') || a.order - b.order);
  else cards.sort((a, b) => a.order - b.order);
  const pk = d.packs || {};
  $('#prog-main').innerHTML = `<div class="panel">
      <div class="prog-hero">
        ${s.logo ? `<img class="logo" src="${esc(s.logo)}" alt="">` : '<span></span>'}
        <div>
          <h2 style="font-size:24px">${esc(s.name)}</h2>
          <div class="muted">${esc(s.series)} · ${esc(s.release)} · ${s.total} cards${s.estimated ? ' · prices mostly estimated' : ''}</div>
          <div class="prog-bars">
            <div><div class="lbl"><span>Set (${s.printed || d.main_total} cards)</span><span>${d.main_owned}/${d.main_total} · ${Math.floor(d.main_pct)}%</span></div><div class="bar"><i style="width:${d.main_pct}%"></i></div></div>
            <div><div class="lbl"><span>Master set (with secret rares)</span><span>${d.master_owned}/${d.master_total}</span></div><div class="bar violet"><i style="width:${pct(d.master_owned, d.master_total)}%"></i></div></div>
          </div>
        </div>
        <div class="row gap" style="flex-direction:column;align-items:stretch">
          <button class="btn btn-primary" data-pick-set="${esc(s.id)}">Open this set</button>
          ${d.scannable ? '<span class="muted small" style="text-align:center">✓ ready to scan</span>' : `<button class="btn btn-sm" data-prepare="${esc(s.id)}">Download for scanning</button>`}
        </div>
      </div>
      <div class="stats" style="margin-top:16px">
        <div class="stat"><div class="k">Your cards are worth</div><div class="v">${money(d.owned_value)}</div></div>
        <div class="stat"><div class="k">Missing cards cost</div><div class="v">${money(d.missing_value)}</div></div>
        <div class="stat"><div class="k">Packs opened</div><div class="v">${pk.packs || 0}</div></div>
        <div class="stat"><div class="k">Spent / pulled</div><div class="v">${money(pk.cost || 0)} / ${money(pk.value || 0)}</div></div>
      </div>
      <div class="rarities">${d.rarities.map((r) => `<span class="rarity">${esc(r.rarity)} <b>${r.owned}/${r.total}</b></span>`).join('')}</div>
    </div>
    <div class="panel" style="margin-top:16px">
      <div class="panel-head">
        <div class="filters" id="prog-filters">${[['all', 'All'], ['owned', 'Have'], ['missing', 'Missing'], ['dupes', 'Duplicates'], ['hits', 'Hits'], ['main', 'Main set']].map(([k, l]) => `<button data-filter="${k}" class="${progFilter === k ? 'active' : ''}">${l}</button>`).join('')}</div>
        <select id="prog-sort"><option value="number">By number</option><option value="price">By price</option><option value="rarity">By rarity</option></select>
      </div>
      <div class="card-grid">${cards.map((c) => `<div class="tile ${c.owned ? '' : 'missing'}" data-tile="${esc(c.key)}">${cardImg(c)}
        ${c.owned ? '<span class="check-mark">✓</span>' : ''}${c.owned > 1 ? `<span class="count">×${c.owned}</span>` : ''}
        <div class="meta"><span>#${esc(c.number)} ${esc(c.name)}</span><b>${money(c.price)}</b></div></div>`).join('') || '<p class="muted">No cards here.</p>'}</div>
    </div>`;
  $('#prog-sort').value = progSort;
}

// ------------------------------------------------------------ packs view
let packsOffset = 0;
async function showPacks(append) {
  await loadSets();
  const sel = $('#packs-set');
  if (!sel.dataset.filled) {
    const started = SETS.filter((s) => s.owned);
    sel.innerHTML = '<option value="">All sets</option>' + started.map((s) => `<option value="${esc(s.id)}">${esc(s.name)}</option>`).join('');
    sel.dataset.filled = '1';
  }
  if (!append) packsOffset = 0;
  const r = await api.get(`/api/packs?limit=30&offset=${packsOffset}&set=${encodeURIComponent(sel.value)}`);
  const t = r.totals || {};
  const profit = (t.value || 0) - (t.cost || 0);
  $('#packs-totals').innerHTML = `
    <div class="stat"><div class="k">Packs opened</div><div class="v">${t.packs || 0}</div></div>
    <div class="stat"><div class="k">Spent</div><div class="v">${money(t.cost || 0)}</div></div>
    <div class="stat"><div class="k">Pulled</div><div class="v">${money(t.value || 0)}</div></div>
    <div class="stat"><div class="k">Profit</div><div class="v ${profit >= 0 ? 'good' : 'bad'}">${signed(profit)}</div></div>
    <div class="stat"><div class="k">Avg. pack</div><div class="v">${money(t.packs ? t.value / t.packs : 0)}</div></div>
    <div class="stat"><div class="k">Best pack</div><div class="v">${money(t.best_pack || 0)}</div></div>`;
  const html = r.packs.map((p) => `<div class="pack-row" data-pack="${p.id}">
      <div class="top"><span class="num">#${p.id}</span><span class="set">${esc(p.setName)}<div class="muted small">${new Date(p.started * 1000).toLocaleString()} · ${p.cards}/${p.size} cards${p.ended ? (p.complete ? '' : ' · ended early') : ' · <b class="accent">open now</b>'}</div></span>
      <span class="muted small">${p.dupes} dupes</span>
      <span class="val">${money(p.value)}</span>
      <span class="${p.profit == null ? 'muted' : p.profit >= 0 ? 'good' : 'bad'}" style="min-width:90px;text-align:right;font-weight:700">${p.profit == null ? '' : signed(p.profit)}</span>
      <button class="btn btn-sm btn-ghost" data-del-pack="${p.id}" title="Delete this pack and its scans">🗑</button></div>
      <div class="cards" hidden></div></div>`).join('');
  if (append) $('#pack-list').insertAdjacentHTML('beforeend', html); else $('#pack-list').innerHTML = html || '<div class="panel muted">No packs yet. Open one on the Scan tab!</div>';
  $('#packs-more').hidden = r.packs.length < 30;
}
async function togglePack(row) {
  const box = $('.cards', row);
  if (!box.hidden) { box.hidden = true; return; }
  const p = await api.get('/api/pack/' + row.dataset.pack);
  box.innerHTML = p.scans.map((sc) => `<div class="mini ${sc.dupe ? 'dupe' : ''}" data-card="${esc(sc.card_key)}">${cardImg(sc.card)}<div class="p">${money(sc.price)}</div></div>`).join('') || '<span class="muted">No cards.</span>';
  box.hidden = false;
}

// ------------------------------------------------------------ collection view
async function showCollection() {
  const r = await api.get(`/api/collection?sort=${$('#col-sort').value}&q=${encodeURIComponent($('#col-search').value)}`);
  const t = r.totals || {};
  $('#col-totals').innerHTML = `
    <div class="stat"><div class="k">Different cards</div><div class="v">${t.unique_cards || 0}</div></div>
    <div class="stat"><div class="k">Total scans</div><div class="v">${t.scans || 0}</div></div>
    <div class="stat"><div class="k">Duplicates</div><div class="v">${t.dupes || 0}</div></div>
    <div class="stat"><div class="k">Collection value</div><div class="v">${money(r.value)}</div></div>`;
  $('#col-grid').innerHTML = r.cards.map((c) => `<div class="tile" data-tile-card='${esc(JSON.stringify(c))}'>${cardImg(c)}${c.owned > 1 ? `<span class="count">×${c.owned}</span>` : ''}
    <div class="meta"><span>${esc(c.name)}</span><b>${money(c.price)}</b></div><div class="muted small">${esc(c.setName)}</div></div>`).join('')
    || '<p class="muted">Nothing scanned yet.</p>';
}

// ------------------------------------------------------------ settings view
const SETTINGS = [
  { title: 'Camera', items: [
    { key: 'camera', label: 'Camera', type: 'camera', help: 'The headset camera needs SteamVR running with its camera turned on (SteamVR Settings → Video → Camera). Any webcam works too, e.g. one on a stand pointing at your table.' },
    { key: 'camera_res', label: 'Resolution' },
    { key: 'camera_fps', label: 'Frame rate', type: 'number', unit: 'fps' },
    { key: 'camera_rotate', label: 'Rotate image', labels: { 0: 'No', 90: '90°', 180: '180°', 270: '270°' } },
    { key: 'camera_mirror', label: 'Mirror image', type: 'bool' },
    { key: 'stereo_eye', label: 'Headset camera: which eye', labels: { left: 'Left', right: 'Right', full: 'Whole frame' }, help: 'SteamVR sends both eyes in one picture. The scanner reads one of them.' },
    { key: 'camera_exposure', label: 'Exposure', type: 'range', step: 1, help: '0 = automatic. Turn it down if shiny cards wash out.' },
    { key: 'show_preview', label: 'Show the camera in this window', type: 'bool' },
    { action: 'restart_camera', label: 'Restart camera' },
  ] },
  { title: 'Scanning', items: [
    { key: 'sensitivity', label: 'Scan sensitivity', labels: { relaxed: 'Relaxed: fastest, more mistakes', balanced: 'Balanced', strict: 'Strict: slowest, fewest mistakes' } },
    { key: 'use_keypoints', label: 'Recognise cards by their artwork', type: 'bool', help: 'Finds cards even when their edges are hard to see: busy tables, sleeves, toploaders, fingers over the border. Recommended.' },
    { key: 'max_cards', label: 'Cards scanned at once', type: 'number', help: 'Lay several new cards on the table under the camera and they are all scanned together.' },
    { key: 'detect_mode', label: 'Finding the card’s edges', labels: { auto: 'Automatic', both: 'Automatic, else guide box', guide: 'Guide box only' }, help: 'Use the guide box if your camera struggles to see card edges: hold the card to fill the box.' },
    { key: 'guide_size', label: 'Guide box size', type: 'range', step: 0.01, pct: true },
    { key: 'scope', label: 'Cards to look for', labels: { pack: 'Only the set I am opening', selected: 'That set + sets I pick', prepared: 'Every downloaded set' }, help: 'Fewer sets = faster and more accurate.' },
    { key: 'scope_sets', label: 'Extra sets to look for', type: 'sets' },
    { key: 'rescan_gap', label: 'Card must leave view for', type: 'range', step: 0.1, unit: 's', help: 'Stops a card you are still holding from being scanned twice.' },
    { key: 'same_in_pack', label: 'Same card again in one pack', labels: { ignore: 'Ignore (you showed it twice)', allow: 'Count it again' } },
    { key: 'duplicates', label: 'Cards you already own', labels: { count: 'Count the spare copies', ignore: 'Flag them, but don’t count spares' } },
    { key: 'max_fps', label: 'Max scans per second', type: 'number', help: 'Lower it to use less CPU while a game runs.' },
  ] },
  { title: 'Packs', items: [
    { key: 'pack_size', label: 'Cards per pack', type: 'number', help: '0 = the set’s normal pack size (code cards aren’t scanned, so don’t count them).' },
    { key: 'pack_cost', label: 'Price you paid per pack (US$)', type: 'money', help: 'Empty = the sealed pack price from MivimooseRips.' },
    { key: 'auto_next_pack', label: 'Start the next pack automatically', type: 'bool' },
    { key: 'default_variant', label: 'Scanned cards count as', labels: { normal: 'Normal', reverse: 'Reverse holo', holo: 'Holo' } },
  ] },
  { title: 'Sounds & voice', items: [
    { key: 'sound', label: 'Sound effects', type: 'bool' },
    { key: 'volume', label: 'Volume', type: 'range', step: 0.05, pct: true },
    { key: 'snd_new', label: 'New card', type: 'bool', test: 'new' },
    { key: 'snd_dupe', label: 'Duplicate', type: 'bool', test: 'dupe' },
    { key: 'snd_hit', label: 'Hits', type: 'bool', test: 'hit', test2: 'big' },
    { key: 'snd_pack', label: 'Pack complete', type: 'bool', test: 'pack' },
    { key: 'snd_milestone', label: 'Set milestones (25/50/75/100%)', type: 'bool', test: 'milestone' },
    { key: 'snd_ignored', label: 'Card already in this pack', type: 'bool', test: 'ignored' },
    { key: 'hit_value', label: 'A “hit” is worth at least (US$)', type: 'money' },
    { key: 'big_hit_value', label: 'A “big hit” is worth at least (US$)', type: 'money' },
    { key: 'voice', label: 'Read prices aloud', labels: { off: 'Off', price: 'Price only', name_price: 'Card name + price', hits: 'Only for hits' } },
    { key: 'voice_rate', label: 'Voice speed', type: 'range', step: 1 },
    { action: 'test_voice', label: 'Test voice' },
  ] },
  { title: 'Prices', items: [
    { key: 'currency', label: 'Currency', help: 'Prices are TCGplayer market prices in US dollars, converted with daily rates.' },
    { key: 'price_basis', label: 'Collection value uses', labels: { market: 'Today’s market price', scan: 'Price when scanned' } },
  ] },
  { title: 'VR headset (SteamVR)', items: [
    { key: 'vr_enabled', label: 'Show the scanner panel in VR', type: 'bool' },
    { key: 'hud_mode', label: 'Panel position', labels: { world: 'Floating in front of you', head: 'Follows your head', left: 'Left hand', right: 'Right hand' } },
    { key: 'hud_width', label: 'Panel size', type: 'range', step: 0.01, unit: 'm' },
    { key: 'hud_distance', label: 'Distance', type: 'range', step: 0.05, unit: 'm' },
    { key: 'hud_height', label: 'Height (from eye level)', type: 'range', step: 0.01, unit: 'm' },
    { key: 'hud_opacity', label: 'Opacity', type: 'range', step: 0.02, pct: true },
    { key: 'hud_camera', label: 'Show the camera on the panel', type: 'bool' },
    { key: 'hud_fps', label: 'Panel refresh rate', type: 'number', unit: 'fps' },
    { key: 'vr_autostart', label: 'Start with SteamVR', type: 'bool', help: 'Only works for the installed .exe.' },
    { key: 'vr_flip_y', label: 'Fix upside-down clicks', type: 'bool', help: 'Turn this off if pressing a button on the VR panel triggers the one above/below it.' },
    { action: 'vr_check', label: 'Run VR check', help: 'Shows what SteamVR offers: headset, controllers, and whether the headset camera can be used.' },
  ] },
  { title: 'Data', items: [
    { key: 'site_url', label: 'MivimooseRips site', type: 'text', help: 'Card list and prices come from here.' },
    { key: 'refresh_hours', label: 'Refresh prices every', type: 'number', unit: 'h' },
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
  const html = SETTINGS.map((sec) => `<div class="panel"><h2 style="margin-bottom:6px">${sec.title}</h2>${sec.items.map((it) => {
    if (it.action) return `<div class="setting"><div class="lbl">${it.label}</div><div class="btns"><button class="btn btn-sm" data-act="${it.action}">${it.label}</button></div></div>`;
    const k = it.key, val = v[k];
    let ctl;
    const type = it.type || 'select';
    if (type === 'bool') ctl = `<label class="switch"><input type="checkbox" data-key="${k}" ${val ? 'checked' : ''}><span></span></label>`;
    else if (type === 'camera') ctl = `<select data-key="${k}">${cams.map((c) => `<option value="${esc(c.id)}" ${c.id === val ? 'selected' : ''}>${esc(c.name)}</option>`).join('')}${cams.some((c) => c.id === val) ? '' : `<option selected value="${esc(val)}">${esc(val)}</option>`}</select>`;
    else if (type === 'range') {
      const [lo, hi] = rg[k];
      const show = it.pct ? Math.round(val * 100) + '%' : val + (it.unit || '');
      ctl = `<div class="range"><input type="range" data-key="${k}" min="${lo}" max="${hi}" step="${it.step}" value="${val}"><output>${show}</output></div>`;
    } else if (type === 'number') ctl = `<input type="number" data-key="${k}" value="${val}" min="${rg[k] ? rg[k][0] : ''}" max="${rg[k] ? rg[k][1] : ''}">`;
    else if (type === 'money') ctl = `<input type="number" step="0.01" min="0" data-key="${k}" value="${val ?? ''}" placeholder="auto">`;
    else if (type === 'text') ctl = `<input type="text" data-key="${k}" value="${esc(val)}">`;
    else if (type === 'sets') ctl = `<select data-key="scope_sets_add"><option value="">Add a set…</option>${SETS.filter((s) => !val.includes(s.id)).map((s) => `<option value="${esc(s.id)}">${esc(s.name)}${s.lang !== 'en' ? ' (' + esc(s.lang) + ')' : ''}</option>`).join('')}</select>
        <div class="btns" style="margin-top:6px">${val.map((id) => { const s = SETS.find((x) => x.id === id); return `<button class="btn btn-sm" data-remove-scope="${esc(id)}">${esc(s ? s.name : id)} ✕</button>`; }).join('')}</div>`;
    else ctl = `<select data-key="${k}">${(ch[k] || []).map((o) => `<option value="${esc(o)}" ${String(o) === String(val) ? 'selected' : ''}>${esc((it.labels && it.labels[o]) || o)}</option>`).join('')}</select>`;
    const tests = it.test ? `<button class="btn btn-sm btn-ghost" data-test="${it.test}" title="Play">▶</button>${it.test2 ? `<button class="btn btn-sm btn-ghost" data-test="${it.test2}" title="Play big hit">▶▶</button>` : ''}` : '';
    return `<div class="setting"><div><div class="lbl">${it.label}</div>${it.help ? `<div class="help">${it.help}</div>` : ''}</div><div class="row gap" style="justify-content:flex-end">${tests}${ctl}</div></div>`;
  }).join('')}</div>`).join('') + `<div class="panel"><h2 style="margin-bottom:6px">Danger zone</h2>
    <div class="setting"><div><div class="lbl">Delete my whole collection</div><div class="help">Removes every scan and pack. Can’t be undone.</div></div><div class="btns"><button class="btn btn-sm btn-danger" id="reset-all">Delete everything</button></div></div>
    <div class="setting"><div><div class="lbl">Quit the scanner</div><div class="help">Version ${esc(S.version)}</div></div><div class="btns"><button class="btn btn-sm" id="quit-app">Quit</button></div></div></div>`;
  $('#settings').innerHTML = html;
}

async function saveSetting(key, value) {
  try {
    const r = await api.post('/api/settings', { changes: { [key]: value } });
    if (S) S.settings = r.settings;
    if (Object.keys(r.changed).length) toast('Saved', 'good', 1200);
    refreshSoon();
  } catch (e) { toast(e.message, 'bad'); }
}

// ------------------------------------------------------------ events from the scanner
function connectEvents() {
  const es = new EventSource('/api/events');
  es.onmessage = (m) => {
    const ev = JSON.parse(m.data);
    switch (ev.type) {
      case 'scan': {
        const sc = ev.scan, c = sc.card || {};
        if (sc.price >= (S ? S.settings.big_hit_value : 50)) celebrate('BIG HIT!', money(sc.price), c.name);
        else if (sc.dupe) toast(`Duplicate: <b>${esc(c.name)}</b> · ${money(sc.price)}`, 'bad');
        else if (sc.price >= (S ? S.settings.hit_value : 5)) toast(`Hit! <b>${esc(c.name)}</b> · ${money(sc.price)}`, 'hit');
        refreshSoon(0);
        break;
      }
      case 'pack_done': {
        const p = ev.pack;
        celebrate(`Pack ${p.number} done!`, money(p.value), p.profit != null ? `${signed(p.profit)} vs. pack price · ${p.new} new, ${p.dupes} dupes` : `${p.new} new, ${p.dupes} dupes`);
        refreshSoon(0);
        break;
      }
      case 'milestone':
        celebrate(ev.kind === 'master' ? 'Master set complete!' : `${ev.pct}% complete!`, ev.name, 'Set progress');
        break;
      case 'ignored':
        toast(`${esc(ev.card.name)} is already in this pack, skipped`, 'info', 2200);
        break;
      case 'prep':
        refreshSoon(300);
        break;
      case 'toast':
        toast(esc(ev.message), ev.level || 'info');
        break;
      case 'status':
        refreshSoon(200);
        break;
      default:
        refreshSoon();
    }
  };
  es.onerror = () => { /* the browser reconnects by itself */ };
}

// ------------------------------------------------------------ tabs & wiring
const TABS = ['scan', 'progress', 'packs', 'collection', 'settings'];
function showTab(t) {
  tab = t;
  if (location.hash.slice(1) !== t) history.replaceState(null, '', '#' + t);
  $$('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === t));
  $$('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + t));
  const img = $('#camera');
  if (t !== 'scan' && img.dataset.on) { img.removeAttribute('src'); delete img.dataset.on; }
  if (t === 'scan') refresh();
  if (t === 'progress') showProgress();
  if (t === 'packs') showPacks();
  if (t === 'collection') showCollection();
  if (t === 'settings') { renderSettings.built = false; renderSettings(); }
}

document.addEventListener('click', async (e) => {
  const t = e.target.closest('button, [data-tile], [data-card], [data-add-key], [data-prog], .pack-row .top, [data-tile-card]');
  if (!t) return;
  const d = t.dataset;
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
    t.closest('dialog') && t.closest('dialog').close();
    addMode = null;
    toast('Added', 'good', 1500);
    if (tab === 'progress' && progSet) loadProgSet(progSet);
    return refreshSoon(0);
  }
  if (d.card) { const r = await api.get(`/api/search?q=${encodeURIComponent(d.card.split('|')[2] || '')}&set=${encodeURIComponent(d.card.split('|')[0])}&limit=60`); const c = r.cards.find((x) => x.key === d.card); if (c) showCard(c); return; }
  if (d.tile) { const c = progData && progData.cards.find((x) => x.key === d.tile); if (c) showCard(c); return; }
  if (d.tileCard) return showCard(JSON.parse(d.tileCard));
  if (d.prog) return loadProgSet(d.prog);
  if (d.filter) { progFilter = d.filter; return renderProgMain(); }
  if (d.prepare) { await act('prepare_set', { set_id: d.prepare }); toast('Downloading the set’s card images…', 'info'); return; }
  if (d.delPack) { e.stopPropagation(); if (confirm('Delete this pack and all its scans?')) { await act('delete_pack', { pack_id: +d.delPack }); showPacks(); } return; }
  if (t.matches('.pack-row .top')) return togglePack(t.closest('.pack-row'));
  if (d.test) return act('test_sound', { sound: d.test });
  if (d.removeScope) { return saveSetting('scope_sets', S.settings.scope_sets.filter((x) => x !== d.removeScope)).then(() => { renderSettings.built = false; renderSettings(); }); }
  if (d.act === 'vr_check') {
    toast('Checking SteamVR…', 'info', 2500);
    const r = await api.get('/api/vr/check');
    const dlg = $('#card-dialog');
    const cam = r.camera_frame ? `<img src="/vr-camera-test.jpg?t=${Date.now()}" style="max-width:100%;border-radius:10px;margin-top:10px">` : '';
    dlg.innerHTML = `<div class="dialog-head"><h2>VR check</h2><button class="btn btn-ghost" data-close>✕</button></div>
      <pre style="white-space:pre-wrap;font-size:13px;max-height:50vh;overflow:auto;margin:0">${esc(JSON.stringify(r, null, 1))}</pre>${cam}`;
    dlg.showModal();
    return;
  }
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
  if (t.id === 'quit-app') { if (confirm('Quit the scanner?')) api.post('/api/quit'); return; }
});

$('#btn-pause').onclick = () => act('pause').then(() => refreshSoon(0));
$('#btn-undo').onclick = () => act('undo').then(() => refreshSoon(0));
$('#btn-reverse').onclick = () => act('next_variant', { variant: S && S.next_variant === 'reverse' ? null : 'reverse' }).then(() => refreshSoon(0));
$('#btn-next').onclick = () => act('next_pack').then(() => refreshSoon(0));
$('#btn-add').onclick = () => openAdd({ mode: 'add' });
$('#set-search').oninput = renderSetChoices;
$('#set-lang').onchange = renderSetChoices;
$('#add-search').oninput = () => { clearTimeout(addTimer); addTimer = setTimeout(runAddSearch, 180); };
$('#add-anyset').onchange = runAddSearch;
$('#prog-search').oninput = renderProgSets;
$('#prog-lang').onchange = renderProgSets;
$('#prog-started').onchange = renderProgSets;
$('#packs-set').onchange = () => showPacks();
$('#packs-more').onclick = () => { packsOffset += 30; showPacks(true); };
$('#col-sort').onchange = showCollection;
let colTimer = null;
$('#col-search').oninput = () => { clearTimeout(colTimer); colTimer = setTimeout(showCollection, 200); };
document.addEventListener('change', (e) => {
  const el = e.target;
  if (el.id === 'prog-sort') { progSort = el.value; return renderProgMain(); }
  const k = el.dataset.key;
  if (!k) return;
  if (k === 'scope_sets_add') { if (el.value) saveSetting('scope_sets', [...S.settings.scope_sets, el.value]).then(() => { renderSettings.built = false; renderSettings(); }); return; }
  let v = el.type === 'checkbox' ? el.checked : el.value;
  if (el.type === 'number' || el.type === 'range') v = el.value === '' ? null : Number(el.value);
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
  if (TABS.includes(h) && h !== 'scan') showTab(h);
  else if (S && S.state === 'no_set') setTimeout(openSetDialog, 400);
});
if (!NOLIVE) {
  connectEvents();
  pollLive();
  setInterval(() => { if (tab === 'scan' && document.visibilityState === 'visible') refreshSoon(0); }, 4000);
}
