let token        = localStorage.getItem('jwtToken') || '';
let wsClient     = null;
let pricePoller  = null;
let statusPoller = null;

const STRATEGIES = [
  { key: 'NIFTY_SCALP',        title: 'NIFTY Scalping',         subtitle: '1-Min Scalping · NIFTY',     icon: '⚡', index: 'NIFTY',     breakout: false },
  { key: 'BANKNIFTY_SCALP',    title: 'Bank Nifty Scalping',     subtitle: '1-Min Scalping · BANKNIFTY', icon: '🏦', index: 'BANKNIFTY', breakout: false },
  { key: 'NIFTY_BREAKOUT',     title: 'NIFTY Breakout',          subtitle: '5-Min Breakout · NIFTY',     icon: '🚀', index: 'NIFTY',     breakout: true  },
  { key: 'BANKNIFTY_BREAKOUT', title: 'Bank Nifty Breakout',     subtitle: '5-Min Breakout · BANKNIFTY', icon: '💥', index: 'BANKNIFTY', breakout: true  },
  { key: 'SENSEX_SCALP',       title: 'Sensex Scalping',         subtitle: '1-Min Scalping · SENSEX',    icon: '🎯', index: 'SENSEX',     breakout: false },
  { key: 'SENSEX_BREAKOUT',    title: 'Sensex Breakout',         subtitle: '5-Min Breakout · SENSEX',    icon: '🌩️', index: 'SENSEX',     breakout: true  },
];

// Groups the sidebar nav into sections, one per underlying index.
const STRATEGY_GROUPS = [
  { key: 'NIFTY',     title: 'NIFTY',      icon: '⚡' },
  { key: 'BANKNIFTY', title: 'BANK NIFTY', icon: '🏦' },
  { key: 'SENSEX',    title: 'SENSEX',     icon: '🎯' },
];

// Per-key runtime state: { currentSession, savedConfig, pnlFrozen }
const cardState = {};

// Which strategy's full card is currently shown in the detail panel. All 4 cards stay mounted
// in the DOM (so status polling/WebSocket updates keep every strategy live in the background) —
// selecting a sidebar item just toggles which one is visible.
let selectedStrategy = null;

function isAdmin() {
  return localStorage.getItem('userRole') === 'GNANESH';
}

window.onload = () => {
  applyStoredTheme();

  if (!token) {
    window.location.replace('/login');
    return;
  }

  const path = window.location.pathname;
  if (path === '/' || path === '/index.html') {
    window.location.replace('/algo' + window.location.search);
    return;
  }

  const params = new URLSearchParams(window.location.search);
  if (params.get('kite') === 'connected') {
    window.history.replaceState({}, document.title, '/algo');
    showToast('✅ Kite connected successfully!', 'success');
    refreshKiteStatus();
  } else if (params.get('kite') === 'error') {
    window.history.replaceState({}, document.title, '/algo');
    const msg = params.get('message') || 'Kite authentication failed';
    showToast('❌ ' + msg, 'error', 9000);
  }

  init();
  setInterval(updateClock, 1000);
  updateClock();
};

function init() {
  const detail = document.getElementById('strategy-detail');
  detail.innerHTML = STRATEGIES.map(buildCardHtml).join('') + buildOiPanelHtml();

  const sidebar = document.getElementById('strategy-sidebar');
  if (sidebar) sidebar.innerHTML = buildSidebarHtml();

  STRATEGIES.forEach(s => {
    cardState[s.key] = { currentSession: null, savedConfig: null, pnlFrozen: false };
  });

  // Restore the last-viewed strategy/view (per browser), falling back to the first card.
  const stored = localStorage.getItem('selectedStrategy');
  selectView(VIEW_KEYS.includes(stored) ? stored : STRATEGIES[0].key);

  applyRoleVisibility();
  connectWebSocket();
  refreshKiteStatus();
  startPricePoller();

  STRATEGIES.forEach(s => {
    const key = s.key;
    loadFuturesDropdown(key);
    const expiryEl = document.getElementById('cfg-expiry-' + key);
    if (expiryEl) expiryEl.addEventListener('change', () => loadOptionChain(key));
    populateStartTimes(key);
    const lotQtyEl = document.getElementById('cfg-lot-qty-' + key);
    if (lotQtyEl) {
      lotQtyEl.addEventListener('input', () => updateQtyDisplay(key));
      updateQtyDisplay(key);
    }
    setStrategyButtonsLoading(key, true);
  });

  setInterval(() => STRATEGIES.forEach(s => populateStartTimes(s.key)), 60000);

  startStatusPoller();
  fetchAndRenderStatusAll();

  refreshQuotesBar();
  setInterval(refreshQuotesBar, 60000);

  refreshOiSignal();
  setInterval(refreshOiSignal, 30000);
}

async function refreshQuotesBar() {
  const bar = document.getElementById('quotes-bar');
  if (!bar) return;

  let chips = '';
  try {
    const quotes = await get('/api/quotes');
    chips += quotes.map(q => `
      <span class="quote-chip">
        <span class="q-name">${escapeHtml(q.symbol)}</span>
        <span>${escapeHtml(q.price)}</span>
        <span class="${q.up ? 'q-up' : 'q-down'}">${escapeHtml(q.change)}</span>
      </span>`).join('');
  } catch (_) { /* quotes are best-effort */ }

  try {
    const funds = await get('/api/kite/funds');
    if (funds && !funds.error) {
      const marginVal = funds.availableMargin ?? funds.net ?? funds.liveBalance ?? funds.availableCash;
      chips += `
      <span class="quote-chip">
        <span class="q-name">💰 Available Margin</span>
        <span>₹${escapeHtml(typeof marginVal === 'number' ? marginVal.toLocaleString('en-IN', {maximumFractionDigits: 2}) : (marginVal ?? 'N/A'))}</span>
      </span>`;
    }
  } catch (_) { /* funds require Kite connection — best-effort */ }

  if (chips) bar.innerHTML = chips;
}

// ===== NIFTY OI / PCR REVERSAL SIGNAL (right-side panel) =====
const OI_SIGNAL_LABELS = {
  STRONG_BULLISH_REVERSAL: '🚀 Strong Bullish Reversal',
  BULLISH:                 '📈 Bullish',
  NEUTRAL:                 '➖ Neutral / Range-bound',
  BEARISH:                 '📉 Bearish',
  STRONG_BEARISH_REVERSAL: '🔻 Strong Bearish Reversal',
};

// Rendered as just another selectable card in the sidebar/detail workspace — same
// `.strategy-card`/`active-view` show-hide mechanism as the 4 strategy cards (see selectView()).
function buildOiPanelHtml() {
  return `
  <div class="panel strategy-card oi-panel-card" id="card-OI_SIGNAL">
    <div class="card-header">
      <div class="card-title-group">
        <span class="card-icon">🎯</span>
        <div>
          <div style="font-weight:700;font-size:15px;">NIFTY Reversal Signal</div>
          <div class="card-subtitle" id="oi-expiry">—</div>
        </div>
      </div>
    </div>
    <div class="oi-placeholder" id="oi-placeholder">Connect Kite to see the live NIFTY OI signal</div>
    <div id="oi-panel-content" class="hidden">
      <div class="oi-signal-badge" id="oi-signal-badge">NEUTRAL</div>

      <div class="oi-gauge-wrap">
        <div class="oi-gauge"><div class="oi-gauge-marker" id="oi-gauge-marker"></div></div>
        <div class="oi-gauge-labels">
          <span>Strong Bearish</span><span>Neutral</span><span>Strong Bullish</span>
        </div>
      </div>

      <div class="oi-stat-row">
        <div class="oi-stat"><div class="oi-stat-label">LTP</div><div class="oi-stat-value" id="oi-ltp">—</div></div>
        <div class="oi-stat"><div class="oi-stat-label">PCR</div><div class="oi-stat-value" id="oi-pcr">—</div></div>
      </div>
      <div class="oi-stat-row">
        <div class="oi-stat oi-support"><div class="oi-stat-label">Support</div><div class="oi-stat-value" id="oi-support">—</div></div>
        <div class="oi-stat oi-resistance"><div class="oi-stat-label">Resistance</div><div class="oi-stat-value" id="oi-resistance">—</div></div>
      </div>
      <div class="oi-stat-row">
        <div class="oi-stat"><div class="oi-stat-label">Call OI Δ</div><div class="oi-stat-value" id="oi-call-change">—</div></div>
        <div class="oi-stat"><div class="oi-stat-label">Put OI Δ</div><div class="oi-stat-value" id="oi-put-change">—</div></div>
      </div>

      <div class="oi-reasoning-title">Why</div>
      <ul class="oi-reasoning-list" id="oi-reasoning-list"></ul>

      <div class="oi-disclaimer">Heuristic read of OI/PCR positioning — not financial advice. Updated <span id="oi-asof">—</span></div>
    </div>
  </div>`;
}

async function refreshOiSignal() {
  const setText = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };
  try {
    const data = await get('/api/oi-signal');
    const placeholder = document.getElementById('oi-placeholder');
    const content = document.getElementById('oi-panel-content');
    const sideDot = document.getElementById('oi-side-dot');
    const sideStatus = document.getElementById('oi-side-status');

    if (!data || !data.dataAvailable) {
      if (placeholder) { placeholder.textContent = (data && data.message) || 'OI data unavailable'; placeholder.classList.remove('hidden'); }
      if (content) content.classList.add('hidden');
      if (sideDot) sideDot.className = 'side-dot side-dot-idle';
      if (sideStatus) sideStatus.textContent = 'No data';
      return;
    }
    if (placeholder) placeholder.classList.add('hidden');
    if (content) content.classList.remove('hidden');

    setText('oi-expiry', 'Expiry ' + (data.expiryLabel || '—'));
    setText('oi-ltp', fmt(data.ltp));
    setText('oi-pcr', (data.pcr || 0).toFixed(2));
    setText('oi-support', data.supportStrike);
    setText('oi-resistance', data.resistanceStrike);
    setText('oi-call-change', (data.callOiChangePct >= 0 ? '+' : '') + (data.callOiChangePct || 0).toFixed(1) + '%');
    setText('oi-put-change', (data.putOiChangePct >= 0 ? '+' : '') + (data.putOiChangePct || 0).toFixed(1) + '%');

    const signalClass = String(data.signal || 'neutral').toLowerCase().replace(/_/g, '-');
    const badge = document.getElementById('oi-signal-badge');
    if (badge) {
      badge.textContent = OI_SIGNAL_LABELS[data.signal] || data.signal;
      badge.className = 'oi-signal-badge oi-signal-' + signalClass;
    }
    if (sideDot) sideDot.className = 'side-dot oi-side-dot-' + signalClass;
    if (sideStatus) sideStatus.textContent = OI_SIGNAL_LABELS[data.signal] || data.signal || '—';

    const marker = document.getElementById('oi-gauge-marker');
    if (marker) {
      const pct = Math.max(0, Math.min(100, (data.signalScore + 100) / 2));
      marker.style.left = pct + '%';
    }

    const list = document.getElementById('oi-reasoning-list');
    if (list) list.innerHTML = (data.reasoning || []).map(r => `<li>${escapeHtml(r)}</li>`).join('');

    setText('oi-asof', data.asOf ? formatTime(data.asOf) : '—');
  } catch (e) {
    console.warn('OI signal fetch failed:', e);
  }
}

function getMaxLotSize() {
  return parseInt(localStorage.getItem('maxLotSize') || '1');
}

function applyRoleVisibility() {
  const admin = isAdmin();
  document.querySelectorAll('.admin-only').forEach(el => {
    el.style.display = admin ? '' : 'none';
  });
  document.querySelectorAll('.user-only').forEach(el => {
    el.style.display = admin ? 'none' : '';
  });

  STRATEGIES.forEach(s => {
    const lotEl = document.getElementById('cfg-lot-qty-' + s.key);
    if (lotEl) {
      const max = getMaxLotSize();
      lotEl.max = max;
      const hint = lotEl.closest('.form-group')?.querySelector('span');
      if (hint && !admin) {
        hint.textContent = `Max ${max} lot${max !== 1 ? 's' : ''} allowed for your account`;
      }
    }
  });
}

function updateQtyDisplay(key) {
  const lots  = parseInt(document.getElementById('cfg-lot-qty-' + key)?.value) || 1;
  const strat = STRATEGIES.find(s => s.key === key);
  // Display-only preview — the backend (TradingConfig.lotSizeForInstrument()) is authoritative.
  const lotSize = strat && strat.index === 'BANKNIFTY' ? 30 : strat && strat.index === 'SENSEX' ? 20 : 65;
  const total = document.getElementById('qty-total-' + key);
  if (total) total.textContent = (lots * lotSize).toLocaleString('en-IN');
  const totalUser = document.getElementById('qty-total-user-' + key);
  if (totalUser) totalUser.textContent = (lots * lotSize).toLocaleString('en-IN');
}

function populateStartTimes(key) {
  const sel = document.getElementById('cfg-start-time-' + key);
  if (!sel) return;
  const prevVal = sel.value;
  const strat = STRATEGIES.find(x => x.key === key) || {};

  const nowIst  = new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Kolkata' }));
  const nowMins = nowIst.getHours() * 60 + nowIst.getMinutes();

  // Scalping cards (NIFTY/Bank Nifty): full session window 9:15 to 3:27 PM.
  // Breakout card: same window but restricted to 5-min-aligned slots (9:15, 9:20, 9:25 ...)
  // since the strategy's candles are 5 minutes wide and the first two candles after the
  // selected start time form the breakout reference (e.g. start=9:15 → candle1=9:15-9:20,
  // candle2=9:20-9:25, breakout checks begin from the candle closing at/after 9:25).
  const START_H = 9, START_M = 15;
  const END_H   = 15, END_M = 27;
  const STEP    = strat.breakout ? 5 : 1;

  const startTotal = START_H * 60 + START_M;
  const endTotal   = END_H   * 60 + END_M;

  let html = '';
  for (let t = startTotal; t <= endTotal; t += STEP) {
    const h   = Math.floor(t / 60);
    const m   = t % 60;
    const hh  = String(h).padStart(2, '0');
    const mm  = String(m).padStart(2, '0');
    const val = `${hh}:${mm}`;

    const disabled = t <= nowMins;
    const label    = disabled ? `${val}  ✗` : val;
    html += `<option value="${val}" ${disabled ? 'disabled style="color:#555;"' : ''}>${label}</option>`;
  }

  sel.innerHTML = html;

  if (prevVal && !sel.querySelector(`option[value="${prevVal}"]`)?.disabled) {
    sel.value = prevVal;
  } else {
    const firstValid = sel.querySelector('option:not([disabled])');
    if (firstValid) sel.value = firstValid.value;
  }
}

function logout() {
  token = '';
  try { localStorage.removeItem('jwtToken');    } catch(_) {}
  try { localStorage.removeItem('jwtUsername'); } catch(_) {}
  try { localStorage.removeItem('userRole');    } catch(_) {}
  try { localStorage.removeItem('maxLotSize');  } catch(_) {}
  try { if (wsClient)     { wsClient.disconnect(); wsClient = null; } } catch(_) {}
  try { if (pricePoller)  { clearInterval(pricePoller);  pricePoller  = null; } } catch(_) {}
  try { if (statusPoller) { clearInterval(statusPoller); statusPoller = null; } } catch(_) {}
  window.location.replace('/login');
}

// ===== THEME TOGGLE =====
function toggleTheme() {
  const isLight = document.body.classList.toggle('light-theme');
  localStorage.setItem('theme', isLight ? 'light' : 'dark');
  const btn = document.getElementById('btn-theme');
  if (btn) btn.textContent = isLight ? '🌙' : '☀️';
}

function applyStoredTheme() {
  const theme = localStorage.getItem('theme');
  if (theme === 'light') {
    document.body.classList.add('light-theme');
    const btn = document.getElementById('btn-theme');
    if (btn) btn.textContent = '🌙';
  }
}

async function connectKite() {
  try {
    const res = await get('/api/kite/login-url');
    window.open(res.loginUrl, '_blank');
    showToast('⏳ Complete Kite login in the new tab. This page will update automatically.', 'info');
    let attempts = 0;
    const poll = setInterval(async () => {
      attempts++;
      try {
        const status = await get('/api/kite/status');
        if (status.connected) {
          clearInterval(poll);
          await refreshKiteStatus();
          refreshAllInstruments();
          showToast('✅ Kite connected! Instruments loading...', 'success');
        }
      } catch (_) {}
      if (attempts >= 60) clearInterval(poll);
    }, 3000);
  } catch (e) {
    showToast('❌ Failed to get Kite login URL', 'error');
  }
}

async function setManualToken() {
  const t = document.getElementById('manual-token').value.trim();
  if (!t) return;
  await withBtnLoad('btn-set-token', '⏳ Saving...', async () => {
  try {
    await post('/api/kite/my-access-token', { accessToken: t });
    showToast('✅ Kite token saved — connected!', 'success');
    await refreshKiteStatus();
    refreshAllInstruments();
  } catch (e) {
    showToast('❌ Failed to set token: ' + e.message, 'error');
  }
  });
}

async function refreshKiteStatus() {
  try {
    const status = await get('/api/kite/status');
    const badge  = document.getElementById('kite-status-badge');
    const banner = document.getElementById('kite-banner');
    const disconnectBtn = document.getElementById('btn-kite-disconnect');
    if (status.connected) {
      badge.textContent    = status.tickerActive ? '✅ Kite: Live Feed' : '🔄 Kite: REST Polling';
      badge.className      = 'badge badge-connected';
      banner.style.display = 'none';
      if (disconnectBtn) disconnectBtn.style.display = 'inline-flex';
    } else {
      badge.textContent    = '⚠️ Kite: Not Connected';
      badge.className      = 'badge badge-disconnected';
      banner.style.display = 'flex';
      if (disconnectBtn) disconnectBtn.style.display = 'none';
    }
  } catch (_) {}
}

async function disconnectKite() {
  if (!confirm('Clear saved Kite token? You will need to set a new token tomorrow morning.')) return;
  try {
    await post('/api/kite/disconnect', {});
    showToast('🔌 Kite disconnected. Set a new token to reconnect.', 'info');
    await refreshKiteStatus();
  } catch (e) {
    showToast('❌ ' + (e.message || 'Disconnect failed'), 'error');
  }
}

function refreshAllInstruments() {
  STRATEGIES.forEach(s => loadFuturesDropdown(s.key));
}

async function loadFuturesDropdown(key) {
  const strat = STRATEGIES.find(s => s.key === key);
  try {
    const instruments = await get('/api/kite/instruments/futures?index=' + strat.index);
    const sel = document.getElementById('cfg-futures-' + key);
    if (!sel) return;
    if (instruments && instruments.length > 0) {
      sel.innerHTML = instruments.map(i => {
        const label = i.expiry ? `exp: ${i.expiry}` : 'spot';
        return `<option value="${i.tradingsymbol}" data-token="${i.instrumentToken || 0}">
          ${i.tradingsymbol} (${label})
        </option>`;
      }).join('');

      const saved = cardState[key].savedConfig;
      if (saved && saved.futureSymbol) {
        const match = sel.querySelector(`option[value="${saved.futureSymbol}"]`);
        if (match) sel.value = saved.futureSymbol;
      }

      onFuturesChange(key);
    }
  } catch (_) {}
}

async function loadOptionChain(key) {
  const strat   = STRATEGIES.find(s => s.key === key);
  const futures = document.getElementById('cfg-futures-' + key)?.value;
  const expiry  = document.getElementById('cfg-expiry-' + key)?.value || 'CURRENT_WEEK';
  const price   = parseFloat(document.getElementById('price-futures-' + key)?.textContent) || undefined;
  if (!futures) return;

  try {
    let url = `/api/kite/instruments/options?expiryType=${expiry}&futuresSymbol=${futures}&index=${strat.index}`;
    if (price && price > 0) url += `&niftyPrice=${price}`;
    const chain = await get(url);
    populateStrikeDropdowns(key, chain);
  } catch (e) {
    console.warn('Option chain load error:', e);
  }
}

function populateStrikeDropdowns(key, chain) {
  if (!chain || !chain.strikes) return;
  const strikes = chain.strikes;

  const ceSel = document.getElementById('cfg-ce-strike-' + key);
  const peSel = document.getElementById('cfg-pe-strike-' + key);
  if (!ceSel || !peSel) return;

  const ceOpts = strikes
    .filter(s => s.ceInstrument)
    .map(s =>
      `<option value="${s.ceInstrument}" data-token="${s.ceToken || 0}" data-strike="${s.strikePrice}" ${s.atm ? 'selected' : ''}>
        ${s.strikePrice} CE ${s.ceLtp > 0 ? '(₹' + s.ceLtp.toFixed(1) + ')' : ''}
      </option>`
    ).join('');

  const peOpts = strikes
    .filter(s => s.peInstrument)
    .map(s =>
      `<option value="${s.peInstrument}" data-token="${s.peToken || 0}" data-strike="${s.strikePrice}" ${s.atm ? 'selected' : ''}>
        ${s.strikePrice} PE ${s.peLtp > 0 ? '(₹' + s.peLtp.toFixed(1) + ')' : ''}
      </option>`
    ).join('');

  ceSel.innerHTML = ceOpts || '<option>No data</option>';
  peSel.innerHTML = peOpts || '<option>No data</option>';
}

function toggleSlEnabled(key) {
  const enabled = document.getElementById('sl-enabled-' + key).checked;
  const slInput = document.getElementById('cfg-sl-' + key);
  slInput.disabled = !enabled;
  slInput.style.opacity = enabled ? '1' : '0.5';
}

function toggleLiveSlEnabled(key) {
  const enabled = document.getElementById('live-sl-enabled-' + key).checked;
  const slInput = document.getElementById('live-sl-' + key);
  slInput.disabled = !enabled;
  slInput.style.opacity = enabled ? '1' : '0.5';
}

function onFuturesChange(key) {
  const sel = document.getElementById('cfg-futures-' + key);
  if (!sel) return;
  loadOptionChain(key);
}

async function saveConfig(key) {
  const strat       = STRATEGIES.find(s => s.key === key);
  const ceSel       = document.getElementById('cfg-ce-strike-' + key);
  const peSel       = document.getElementById('cfg-pe-strike-' + key);
  const futSel      = document.getElementById('cfg-futures-' + key);
  const expiry      = document.getElementById('cfg-expiry-' + key).value;
  const direction   = document.getElementById('cfg-direction-' + key).value;

  const startTimeSel = document.getElementById('cfg-start-time-' + key);

  const futOpt = futSel.selectedOptions[0];
  const futureToken = futOpt ? parseInt(futOpt.dataset.token || 0) : 0;

  // Manual strike selection only — Auto ATM has been removed.
  const ceOpt = ceSel.selectedOptions[0];
  const peOpt = peSel.selectedOptions[0];
  const ceSymbol = ceSel.value;
  const peSymbol = peSel.value;
  const ceToken  = ceOpt ? parseInt(ceOpt.dataset.token || 0) : 0;
  const peToken  = peOpt ? parseInt(peOpt.dataset.token || 0) : 0;
  // data-strike carries the numeric strike (e.g. 21000) from the option-chain dropdown —
  // needed so the backend can lock/report the selected strike (see lockedCeStrike/lockedPeStrike
  // in AlgoStatusResponse, used by the breakout reference-candle High/Low display).
  const ceStrikePrice = ceOpt ? parseInt(ceOpt.dataset.strike || 0) : 0;
  const peStrikePrice = peOpt ? parseInt(peOpt.dataset.strike || 0) : 0;

  const eodChecked = document.getElementById('cfg-eod-' + key).checked;
  const slEnabled  = document.getElementById('sl-enabled-' + key).checked;
  const lots = parseInt(document.getElementById('cfg-lot-qty-' + key).value) || 1;

  const maxLots = getMaxLotSize();
  if (lots > maxLots) {
    showToast(`❌ Lot quantity ${lots} exceeds your account limit of ${maxLots} lot${maxLots !== 1 ? 's' : ''}. Please reduce.`, 'error');
    return;
  }

  const maxReversals = isAdmin()
    ? parseInt(document.getElementById('cfg-max-reversals-' + key).value)
    : 10;

  const reversalEnabled = strat.breakout
    ? document.getElementById('cfg-reversal-enabled-' + key).checked
    : true;

  cardState[key].savedConfig = {
    strategyKey:     key,
    tradeDirection:  direction,
    futureSymbol:    futSel.value,
    futureToken,
    ceSymbol,  ceToken,  ceStrikePrice,
    peSymbol,  peToken,  peStrikePrice,
    expiryType:      expiry,
    entryStartTime:  startTimeSel.value,
    strikeMode:      'MANUAL',
    lotQuantity:     lots,
    maxReversals,
    reversalEnabled,
    targetPrice:     parseFloat(document.getElementById('cfg-target-' + key).value),
    stopLoss:        slEnabled ? parseFloat(document.getElementById('cfg-sl-' + key).value) : 0,
    trailingProfit:  parseFloat(document.getElementById('cfg-trailing-' + key).value) || 0,
    squareOffEod:    eodChecked,
    paperTrade:      document.getElementById('cfg-trade-mode-' + key).value === 'PAPER',
  };

  showMsg('config-msg-' + key, '✅ Configuration ready. Click Start to begin.', 'success');
}

async function startStrategy(key) {
  if (!cardState[key].savedConfig) await saveConfig(key);

  cardState[key].pnlFrozen = false;

  const setText = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };
  setText('ce-label-' + key, 'CE');
  setText('pe-label-' + key, 'PE');
  setText('price-ce-' + key, '—');
  setText('price-pe-' + key, '—');
  setText('s-locked-ce-' + key, '—');
  setText('s-locked-pe-' + key, '—');
  setText('cfg-chip-ce-' + key, 'CE —');
  setText('cfg-chip-pe-' + key, 'PE —');

  setStrategyButtonsLoading(key, true);
  try {
    await post('/algo/start', cardState[key].savedConfig);
    showMsg('config-msg-' + key, '▶️ Strategy started!', 'success');
    fetchAndRenderStatusAll();
  } catch (e) {
    const msg = e.message || 'Start failed';
    const isConflict = msg.includes('409') || msg.toLowerCase().includes('already running') || msg.toLowerCase().includes('already have an active');
    showMsg('config-msg-' + key, isConflict ? '⚠️ ' + msg : '❌ ' + msg, isConflict ? 'info' : 'error');
    fetchAndRenderStatusAll();
  }
}

async function stopStrategy(key) {
  const strat = STRATEGIES.find(s => s.key === key);
  const label = strat ? strat.title : key;
  if (!confirm(`Exit ${label}? Any open position will be squared off.`)) return;
  setStrategyButtonsLoading(key, true);
  try {
    await post('/algo/stop?strategy=' + key, {});
    showToast(`⏹️ ${label} exited.`, 'success');
    fetchAndRenderStatusAll();
  } catch (e) {
    showMsg('config-msg-' + key, '❌ ' + (e.message || 'Stop failed'), 'error');
    fetchAndRenderStatusAll();
  }
}

async function stopAllStrategies() {
  const activeKeys = STRATEGIES
    .filter(s => cardState[s.key] && cardState[s.key].currentSession && cardState[s.key].currentSession.active)
    .map(s => s.key);

  if (activeKeys.length === 0) {
    showToast('No active strategies to stop.', 'info');
    return;
  }

  const names = activeKeys.map(k => (STRATEGIES.find(s => s.key === k) || {}).title || k).join(', ');
  if (!confirm(`Stop ALL ${activeKeys.length} active strategy(ies)?\n\n${names}\n\nAny open positions will be squared off.`)) return;

  const stopAllBtn = document.getElementById('btn-stop-all');
  if (stopAllBtn) stopAllBtn.disabled = true;
  activeKeys.forEach(key => setStrategyButtonsLoading(key, true));

  try {
    const results = await Promise.allSettled(activeKeys.map(key => post('/algo/stop?strategy=' + key, {})));
    const failed = results
      .map((r, i) => ({ r, key: activeKeys[i] }))
      .filter(x => x.r.status === 'rejected');
    if (failed.length === 0) {
      showToast(`🛑 Stopped all ${activeKeys.length} active strategy(ies).`, 'success');
    } else {
      const failedNames = failed.map(x => (STRATEGIES.find(s => s.key === x.key) || {}).title || x.key).join(', ');
      showToast(`⚠️ Stopped ${activeKeys.length - failed.length}/${activeKeys.length}. Failed: ${failedNames}`, 'error', 9000);
    }
  } finally {
    if (stopAllBtn) stopAllBtn.disabled = false;
    fetchAndRenderStatusAll();
  }
}

async function resetPnl(key) {
  cardState[key].pnlFrozen = true;
  try {
    await post('/algo/reset?strategy=' + key, {});
  } catch (_) {}
  setAmount('pnl-realized-' + key, 0);
  setAmount('pnl-open-' + key, 0);
  setAmount('pnl-total-' + key, 0, true);
  const tgt = document.getElementById('pnl-target-' + key);
  const sl  = document.getElementById('pnl-sl-' + key);
  if (tgt) tgt.textContent = '₹—';
  if (sl)  sl.textContent  = '₹—';
  const bar = document.getElementById('pnl-bar-' + key);
  if (bar) { bar.style.width = '50%'; bar.style.background = 'var(--success)'; }
  const tbody = document.getElementById('legs-body-' + key);
  if (tbody) tbody.innerHTML = '<tr><td colspan="8" class="empty-row">No trades yet</td></tr>';
  cardState[key].currentSession = null;
}

async function updateLiveParams(key) {
  const targetPrice    = parseFloat(document.getElementById('live-target-' + key).value);
  const stopLoss       = parseFloat(document.getElementById('live-sl-' + key).value) || 0;
  const slEnabled      = document.getElementById('live-sl-enabled-' + key).checked;
  const trailingProfit = parseFloat(document.getElementById('live-trailing-' + key).value) || 0;

  if (isNaN(targetPrice) || targetPrice <= 0) {
    showMsg('live-params-msg-' + key, '❌ Enter a valid target', 'error');
    return;
  }

  try {
    await post('/algo/update-params?strategy=' + key, { targetPrice, stopLoss, stopLossEnabled: slEnabled, trailingProfit });
    showMsg('live-params-msg-' + key, '✅ Parameters updated', 'success');
    fetchAndRenderStatusAll();
  } catch (e) {
    showMsg('live-params-msg-' + key, '❌ ' + (e.message || 'Update failed'), 'error');
  }
}

function startStatusPoller() {
  if (statusPoller) clearInterval(statusPoller);
  statusPoller = setInterval(fetchAndRenderStatusAll, 3000);
}

let firstStatusPollDone = false;

async function fetchAndRenderStatusAll() {
  if (!token) return;
  try {
    const list = await get('/algo/status/all');
    const byKey = {};
    (list || []).forEach(s => { byKey[s.strategyKey] = s; });

    let runningCount = 0;
    let firstActiveKey = null;
    STRATEGIES.forEach(strat => {
      const key = strat.key;
      setStrategyButtonsLoading(key, false);
      const s = byKey[key];
      if (s && !cardState[key].pnlFrozen) {
        cardState[key].currentSession = s;
        renderSession(key, s);
        if (s.active) {
          runningCount++;
          if (!firstActiveKey) firstActiveKey = key;
        }
      } else if (!s) {
        renderIdleCard(key);
      }
    });

    // On the very first poll after page load, if the user has never picked a strategy to view
    // (fresh browser/localStorage) and exactly one is already running, jump straight to it.
    if (!firstStatusPollDone && localStorage.getItem('selectedStrategy') === null
        && runningCount === 1 && firstActiveKey) {
      selectView(firstActiveKey);
    }
    firstStatusPollDone = true;

    const badge = document.getElementById('running-badge');
    if (badge) badge.textContent = `${runningCount} / ${STRATEGIES.length} Running`;
    const stopAllBtn = document.getElementById('btn-stop-all');
    if (stopAllBtn) stopAllBtn.disabled = runningCount === 0;
    updateSidebarPnlSummary();
  } catch (_) {
    STRATEGIES.forEach(s => setStrategyButtonsLoading(s.key, false));
  }
}

// Two numbers, both computed straight from the same session data already rendered in each
// card/sidebar item — no separate API call needed:
//  - Active P&L:      totalPnL summed over strategies currently running/waiting only.
//  - Total P&L (Today): totalPnL summed over EVERY strategy that has a session today, active
//    or already stopped — e.g. 2 running + 2 stopped still counts all 4 here.
function updateSidebarPnlSummary() {
  const activeEl = document.getElementById('sidebar-active-pnl-value');
  const totalEl  = document.getElementById('sidebar-total-pnl-value');
  if (!activeEl && !totalEl) return;

  let activePnl = 0, totalPnl = 0;
  STRATEGIES.forEach(strat => {
    const s = cardState[strat.key] && cardState[strat.key].currentSession;
    if (!s) return;
    totalPnl += (s.totalPnL || 0);
    if (s.active) activePnl += (s.totalPnL || 0);
  });

  const setVal = (el, val) => {
    if (!el) return;
    el.textContent = (val >= 0 ? '₹' : '-₹') + fmt(Math.abs(val));
    el.classList.toggle('scp-pos', val >= 0);
    el.classList.toggle('scp-neg', val < 0);
  };
  setVal(activeEl, activePnl);
  setVal(totalEl, totalPnl);
}

function renderIdleCard(key) {
  const startBtn = document.getElementById('btn-start-' + key);
  const stopBtn  = document.getElementById('btn-stop-' + key);
  if (startBtn) { startBtn.disabled = false; startBtn.classList.remove('hidden'); }
  if (stopBtn)  { stopBtn.disabled  = true;  stopBtn.classList.add('hidden'); }

  const sideItem = document.getElementById('side-item-' + key);
  if (sideItem) sideItem.classList.remove('is-live');
  const sideDot = document.getElementById('side-dot-' + key);
  if (sideDot) sideDot.className = 'side-dot side-dot-idle';
  const sideStatus = document.getElementById('side-status-' + key);
  if (sideStatus) sideStatus.textContent = 'IDLE';
  const sidePnl = document.getElementById('side-pnl-' + key);
  if (sidePnl) { sidePnl.textContent = '₹0.00'; sidePnl.classList.remove('side-pnl-pos', 'side-pnl-neg'); }
}

function renderSession(key, s) {
  if (!s) return;

  const status = s.status || 'STOPPED';
  const card = document.getElementById('card-' + key);

  const stratBadge = document.getElementById('strategy-badge-' + key);
  if (stratBadge) {
    stratBadge.textContent = status;
    stratBadge.className   = 'badge badge-' + status.toLowerCase().replace(/_/g, '');
  }

  const strip = document.getElementById('active-config-strip-' + key);
  if (strip) {
    strip.classList.remove('hidden');
    const setText = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };
    setText('cfg-chip-future-' + key, s.futureSymbol || '—');
    setText('cfg-chip-dir-' + key,    s.tradeDirection === 'SELL' ? '📉 Sell' : '📈 Buy');
    setText('cfg-chip-time-' + key,   s.entryStartTime ? '⏱ ' + s.entryStartTime : '—');
    setText('cfg-chip-target-' + key, 'Target ₹' + fmt(s.targetPnL || 0));
    setText('cfg-chip-sl-' + key,     'SL ₹'     + fmt(s.stopLossPoints || 0));
    setText('cfg-chip-lots-' + key,   (s.lotQuantity || 1) + ' lot = ' + (s.totalQuantity || 0) + ' qty');
    setText('cfg-chip-mode-' + key,   s.paperTrade ? '📄 Paper' : '🔴 Live');
    setText('cfg-chip-strike-' + key, '✋ Manual Strike');
    if (s.lockedCeInstrument) setText('cfg-chip-ce-' + key, 'CE: ' + s.lockedCeInstrument);
    if (s.lockedPeInstrument) setText('cfg-chip-pe-' + key, 'PE: ' + s.lockedPeInstrument);
    // Bank Nifty trades monthly now — swap "Week" -> "Month" in the backend's label text for
    // display only (e.g. "Current Week (28 Jul)" -> "Current Month (28 Jul)").
    const strat = STRATEGIES.find(x => x.key === key);
    const expiryLabel = strat && strat.index === 'BANKNIFTY'
      ? (s.lockedExpiryLabel || '').replace(/Week/g, 'Month')
      : s.lockedExpiryLabel;
    setText('cfg-chip-expiry-' + key, expiryLabel || '—');
  }

  const stateEl = document.getElementById('s-state-' + key);
  if (stateEl) {
    stateEl.textContent = status;
    stateEl.className   = 'status-value badge badge-' + status.toLowerCase().replace(/_/g, '');
  }

  const startedByEl = document.getElementById('s-started-by-' + key);
  if (startedByEl) {
    startedByEl.textContent = s.startedBy || '—';
    const me = localStorage.getItem('jwtUsername') || '';
    startedByEl.style.color = (s.startedBy && me && s.startedBy !== me)
      ? 'var(--warning, #f59e0b)' : 'inherit';
  }

  const reversalsEl = document.getElementById('s-reversals-' + key);
  if (reversalsEl) reversalsEl.textContent = (s.reversalCount || 0) + ' / ' + (s.maxReversals || 0);

  const dirEl = document.getElementById('s-direction-' + key);
  if (dirEl) {
    dirEl.textContent  = s.currentPosition || '—';
    dirEl.style.color  = s.currentPosition === 'CE' ? '#58a6ff'
                       : s.currentPosition === 'PE' ? '#f85149' : 'inherit';
  }

  const hist = s.history || [];
  const ceRows = hist.filter(r => r.position === 'CE');
  const peRows = hist.filter(r => r.position === 'PE');
  const ceSym  = ceRows.length ? ceRows[ceRows.length - 1].symbol : '—';
  const peSym  = peRows.length ? peRows[peRows.length - 1].symbol : '—';
  const setText2 = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };
  setText2('s-locked-ce-' + key, ceSym);
  setText2('s-locked-pe-' + key, peSym);

  if (ceSym !== '—') setText2('ce-label-' + key, ceSym);
  if (peSym !== '—') setText2('pe-label-' + key, peSym);
  if (s.futureSymbol) setText2('futures-label-' + key, s.futureSymbol);

  if (s.currentPosition === 'CE' && s.currentOptionPrice > 0) {
    setText2('price-ce-' + key, fmt(s.currentOptionPrice));
  } else if (s.currentPosition === 'PE' && s.currentOptionPrice > 0) {
    setText2('price-pe-' + key, fmt(s.currentOptionPrice));
  }

  renderCandle(s.firstCandle  || null, 'c1-' + key);
  renderCandle(s.secondCandle || null, 'c2-' + key);
  renderCandle(s.thirdCandle  || null, 'c3-' + key);

  // Breakout-only: per-leg (CE/PE) reference candle with High/Low. No-op on non-breakout
  // cards since the ref-ce-/ref-pe- elements don't exist there (getElementById → null).
  renderReferenceCandle(s.ceReferenceCandle || null, 'ref-ce-' + key, 'CE', s.lockedCeStrike);
  renderReferenceCandle(s.peReferenceCandle || null, 'ref-pe-' + key, 'PE', s.lockedPeStrike);

  const realized = s.cumulativePnL          || 0;
  const openPnl  = s.currentLegUnrealizedPnL || 0;
  const total    = s.totalPnL               || 0;
  const target   = s.targetPnL              || 0;
  const sl       = s.stopLossPoints         || 0;

  setAmount('pnl-realized-' + key, realized);
  setAmount('pnl-open-' + key,     openPnl);
  setAmount('pnl-total-' + key,    total, true);

  const pnlText = (total >= 0 ? '₹' : '-₹') + fmt(Math.abs(total));
  const sideItem = document.getElementById('side-item-' + key);
  if (sideItem) sideItem.classList.toggle('is-live', !!s.active);
  const sideDot = document.getElementById('side-dot-' + key);
  if (sideDot) sideDot.className = 'side-dot side-dot-' + status.toLowerCase().replace(/_/g, '');
  const sideStatus = document.getElementById('side-status-' + key);
  if (sideStatus) sideStatus.textContent = status;
  const sidePnl = document.getElementById('side-pnl-' + key);
  if (sidePnl) {
    sidePnl.textContent = pnlText;
    sidePnl.classList.toggle('side-pnl-pos', total >= 0);
    sidePnl.classList.toggle('side-pnl-neg', total < 0);
  }
  const pnlTargetEl = document.getElementById('pnl-target-' + key);
  const pnlSlEl     = document.getElementById('pnl-sl-' + key);
  if (pnlTargetEl) pnlTargetEl.textContent = '₹' + fmt(target);
  if (pnlSlEl)     pnlSlEl.textContent     = sl > 0 ? '₹' + fmt(sl) : '—';

  const trailingBox = document.getElementById('trailing-status-box-' + key);
  if (trailingBox) {
    const trStep = s.trailingProfit || 0;
    if (trStep > 0) {
      trailingBox.classList.remove('hidden');
      const hwEl    = document.getElementById('trailing-watermark-' + key);
      const exitEl  = document.getElementById('trailing-exit-level-' + key);
      const labelEl = document.getElementById('trailing-status-label-' + key);
      if (s.trailingActive) {
        const hwVal   = s.trailingHighWatermark || 0;
        const exitLvl = hwVal - trStep;
        if (hwEl)    hwEl.textContent   = '₹' + fmt(hwVal);
        if (exitEl)  exitEl.textContent = '₹' + fmt(exitLvl);
        if (labelEl) labelEl.textContent = '🔒 Trailing Active';
        if (labelEl) labelEl.style.color = 'var(--success)';
      } else {
        if (hwEl)    hwEl.textContent   = '—';
        if (exitEl)  exitEl.textContent = '—';
        if (labelEl) labelEl.textContent = '⏳ Waiting for Target';
        if (labelEl) labelEl.style.color = 'var(--text2)';
      }
    } else {
      trailingBox.classList.add('hidden');
    }
  }

  const effectiveTarget = (s.trailingProfit > 0 && s.trailingActive)
    ? s.trailingHighWatermark : target;
  const range = effectiveTarget + sl;
  const pct   = range > 0 ? ((total + sl) / range) * 100 : 50;
  const bar   = document.getElementById('pnl-bar-' + key);
  if (bar) {
    bar.style.width      = Math.max(0, Math.min(100, pct)) + '%';
    bar.style.background = total >= 0 ? 'var(--success)' : 'var(--danger)';
  }

  const stopBox = document.getElementById('stop-reason-box-' + key);
  if (stopBox) {
    if (s.stopReason) {
      stopBox.classList.remove('hidden');
      setText2('stop-reason-text-' + key, s.stopReason);
    } else {
      stopBox.classList.add('hidden');
    }
  }

  const isStopped = !s.active;
  const startBtn = document.getElementById('btn-start-' + key);
  const stopBtn  = document.getElementById('btn-stop-' + key);
  if (startBtn) { startBtn.disabled = !isStopped; startBtn.classList.toggle('hidden', !isStopped); }
  if (stopBtn)  { stopBtn.disabled  =  isStopped; stopBtn.classList.toggle('hidden',  isStopped); }

  if (strip) strip.style.opacity = isStopped ? '0.55' : '1';
  if (card)  card.classList.toggle('card-stopped-dim', isStopped);

  const liveParamsSection = document.getElementById('live-params-section-' + key);
  if (liveParamsSection) {
    if (!isStopped) {
      liveParamsSection.classList.remove('hidden');
      const liveTgt   = document.getElementById('live-target-' + key);
      const liveSl    = document.getElementById('live-sl-' + key);
      const liveTrl   = document.getElementById('live-trailing-' + key);
      const liveSlChk = document.getElementById('live-sl-enabled-' + key);
      if (liveTgt && !liveTgt.value) liveTgt.value = target || '';
      if (liveSl  && !liveSl.value)  liveSl.value  = sl > 0 ? sl : '';
      if (liveTrl && !liveTrl.value) liveTrl.value  = s.trailingProfit || 0;
      if (liveSlChk && sl > 0 && !liveSlChk.dataset.init) {
        liveSlChk.checked = true;
        liveSlChk.dataset.init = '1';
        toggleLiveSlEnabled(key);
      }
    } else {
      liveParamsSection.classList.add('hidden');
      const liveSlChk = document.getElementById('live-sl-enabled-' + key);
      if (liveSlChk) delete liveSlChk.dataset.init;
      const liveTgt = document.getElementById('live-target-' + key);
      const liveSl  = document.getElementById('live-sl-' + key);
      const liveTrl = document.getElementById('live-trailing-' + key);
      if (liveTgt) liveTgt.value = '';
      if (liveSl)  liveSl.value  = '';
      if (liveTrl) liveTrl.value  = '';
    }
  }

  renderHistory(key, s.history || []);

  if (!isAdmin()) {
    renderUserSignalPanel(key, s);
    renderUserSessionCard(key, s);
  }
}

function renderUserSignalPanel(key, s) {
  const dirIcon  = document.getElementById('user-dir-icon-' + key);
  const dirLabel = document.getElementById('user-dir-label-' + key);
  if (dirIcon && dirLabel) {
    if (s.currentPosition === 'CE') {
      dirIcon.textContent  = '▲';
      dirIcon.style.color  = '#58a6ff';
      dirLabel.textContent = 'CE — Bullish';
      dirLabel.style.color = '#58a6ff';
    } else if (s.currentPosition === 'PE') {
      dirIcon.textContent  = '▼';
      dirIcon.style.color  = '#f85149';
      dirLabel.textContent = 'PE — Bearish';
      dirLabel.style.color = '#f85149';
    } else {
      const st = s.status || '';
      dirIcon.textContent  = st === 'WAITING_FOR_CANDLES' || st === 'WAITING' ? '⏳' : '—';
      dirIcon.style.color  = 'var(--text2)';
      dirLabel.textContent = st === 'WAITING_FOR_CANDLES' || st === 'WAITING' ? 'Scanning...' : 'No Position';
      dirLabel.style.color = 'var(--text2)';
    }
  }
  const userMode   = document.getElementById('user-trade-mode-info-' + key);
  const userLots   = document.getElementById('user-lots-info-' + key);
  const userTarget = document.getElementById('user-target-info-' + key);
  const userSl     = document.getElementById('user-sl-info-' + key);
  if (userMode)   userMode.textContent   = s.paperTrade ? '📄 Paper' : '🔴 Live';
  if (userLots)   userLots.textContent   = (s.lotQuantity || 1) + ' lot(s)';
  if (userTarget) userTarget.textContent = s.targetPnL ? '₹' + fmt(s.targetPnL) : '—';
  if (userSl)     userSl.textContent     = (s.stopLossPoints || 0) > 0 ? '₹' + fmt(s.stopLossPoints) : 'Off';
}

function renderUserSessionCard(key, s) {
  const dot    = document.getElementById('usc-dot-' + key);
  const status = document.getElementById('usc-status-text-' + key);
  const date   = document.getElementById('usc-date-' + key);
  if (!dot) return;

  const active = s.active;
  const st     = s.status || 'IDLE';

  if (active) {
    dot.className    = 'usc-dot usc-dot-active';
    status.textContent = (st === 'WAITING_FOR_CANDLES' || st === 'WAITING') ? 'Scanning market…' : 'Strategy running';
  } else if (st === 'STOPPED') {
    dot.className    = 'usc-dot usc-dot-stopped';
    status.textContent = 'Session complete';
  } else {
    dot.className    = 'usc-dot usc-dot-idle';
    status.textContent = 'Awaiting start';
  }

  const now = new Date();
  date.textContent = now.toLocaleDateString('en-IN', {
    timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', year: 'numeric'
  });
}

function renderCandle(candle, prefix) {
  const closeEl = document.getElementById(prefix + '-close');
  const timeEl  = document.getElementById(prefix + '-time');
  if (!candle) {
    if (closeEl) closeEl.textContent = '—';
    if (timeEl)  timeEl.textContent  = '';
    return;
  }
  if (closeEl) closeEl.textContent = fmt(candle.close);
  if (timeEl)  timeEl.textContent  = candle.time ? formatTime(candle.time) : '';
}

// Per-leg breakout reference candle (CE/PE shown separately, includes High/Low).
// `strike` is the locked strike for that leg (e.g. 21000) — shown alongside the option type
// so the user can see exactly which contract's candle this is, per their request.
function renderReferenceCandle(candle, prefix, optionType, strike) {
  const labelEl = document.getElementById(prefix + '-label');
  const closeEl = document.getElementById(prefix + '-close');
  const hlEl    = document.getElementById(prefix + '-hl');
  const timeEl  = document.getElementById(prefix + '-time');

  if (labelEl) labelEl.textContent = strike ? `${optionType} ${strike}` : `${optionType} —`;

  if (!candle) {
    if (closeEl) closeEl.textContent = '—';
    if (hlEl)    hlEl.textContent    = 'H: — · L: —';
    if (timeEl)  timeEl.textContent  = '';
    return;
  }
  if (closeEl) closeEl.textContent = fmt(candle.close);
  if (hlEl) {
    const h = candle.high != null ? fmt(candle.high) : '—';
    const l = candle.low  != null ? fmt(candle.low)  : '—';
    hlEl.textContent = `H: ${h} · L: ${l}`;
  }
  if (timeEl) timeEl.textContent = candle.time ? formatTime(candle.time) : '';
}

function renderHistory(key, rows) {
  const tbody = document.getElementById('legs-body-' + key);
  if (!tbody) return;
  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="8" class="empty-row">No trades yet</td></tr>';
    return;
  }

  tbody.innerHTML = rows.map((row, idx) => {
    const isOpen    = row.exitReason === 'OPEN';
    const pnl       = row.pnlAmount || 0;
    const pnlClass  = pnl >= 0 ? 'pnl-positive' : 'pnl-negative';
    const typeTag   = `<span class="tag-${(row.position || '').toLowerCase()}">${row.position}</span>`;

    const isReversal   = !isOpen && row.exitReason && row.exitReason.startsWith('REVERSAL');
    const rowStyle     = isReversal ? ' style="border-left:3px solid #f59e0b;"' : '';
    const openRowStyle = (isOpen && idx > 0) ? ' style="border-left:3px solid #3fb950;"' : '';

    const reasonBadge = isOpen
      ? `<span style="color:#3fb950;font-weight:600;font-size:11px;">⬤ OPEN</span>`
      : `<span style="font-size:11px;color:var(--text2);">${row.exitReason || '—'}</span>`;

    const exitTimeCell = isOpen
      ? '<span style="color:var(--text2);font-size:12px;">—</span>'
      : `<span style="font-size:11px;color:var(--text2);">${formatIstTime(row.exitTime)}</span>`;

    return `<tr${isOpen ? openRowStyle : rowStyle}>
      <td style="font-weight:700;">${row.legNumber}</td>
      <td>${typeTag}<br><span style="font-size:10px;color:var(--text2);">${row.symbol || ''}</span></td>
      <td class="monospace">₹${fmt(row.entryPrice)}</td>
      <td style="font-size:11px;color:var(--text2);">${formatIstTime(row.entryTime)}</td>
      <td>${isOpen
        ? '<span style="color:var(--text2);font-size:12px;">—</span>'
        : `<span class="monospace">₹${fmt(row.exitPrice)}</span>`
      }</td>
      <td>${exitTimeCell}</td>
      <td class="${isOpen ? '' : pnlClass}" style="font-weight:700;font-family:monospace">
        ${isOpen ? '<span style="color:var(--text2)">—</span>' : (pnl >= 0 ? '+' : '') + '₹' + fmt(pnl)}
        ${!isOpen && row.pnlPoints ? `<br><small style="font-weight:400;color:var(--text2)">${fmt(row.pnlPoints)} pts</small>` : ''}
      </td>
      <td>${reasonBadge}</td>
    </tr>`;
  }).join('');
}

function startPricePoller() {
  if (pricePoller) clearInterval(pricePoller);
  pricePoller = setInterval(async () => {
    try {
      const ticks = await get('/api/market-data/ticks');
      if (!ticks) return;

      STRATEGIES.forEach(strat => {
        const key = strat.key;
        const state = cardState[key];
        const futSym = (state.currentSession && state.currentSession.futureSymbol)
                     || (state.savedConfig && state.savedConfig.futureSymbol)
                     || document.getElementById('cfg-futures-' + key)?.value;

        if (futSym && ticks[futSym]) {
          const futPrice = ticks[futSym].lastPrice;
          if (futPrice > 0) {
            const el = document.getElementById('price-futures-' + key);
            if (el) el.textContent = fmt(futPrice);
          }
        }

        if (state.currentSession) {
          const hist   = state.currentSession.history || [];
          const ceRows = hist.filter(r => r.position === 'CE');
          const peRows = hist.filter(r => r.position === 'PE');
          const ceSym  = ceRows.length ? ceRows[ceRows.length - 1].symbol : null;
          const peSym  = peRows.length ? peRows[peRows.length - 1].symbol : null;

          if (ceSym && ticks[ceSym] && ticks[ceSym].lastPrice > 0) {
            const el = document.getElementById('price-ce-' + key);
            if (el) el.textContent = fmt(ticks[ceSym].lastPrice);
          }
          if (peSym && ticks[peSym] && ticks[peSym].lastPrice > 0) {
            const el = document.getElementById('price-pe-' + key);
            if (el) el.textContent = fmt(ticks[peSym].lastPrice);
          }
        }
      });
    } catch (_) {}
  }, 2000);
}

function connectWebSocket() {
  if (typeof SockJS === 'undefined' || typeof Stomp === 'undefined') {
    loadScript('https://cdnjs.cloudflare.com/ajax/libs/sockjs-client/1.6.1/sockjs.min.js', () =>
      loadScript('https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js', doConnect)
    );
  } else {
    doConnect();
  }
}

function doConnect() {
  try {
    const socket = new SockJS('/ws');
    wsClient = Stomp.over(socket);
    wsClient.debug = null;

    wsClient.heartbeat.outgoing = 10000;
    wsClient.heartbeat.incoming = 10000;

    wsClient.connect({}, () => {
      document.getElementById('footer-ws').textContent = 'WS: Connected';

      wsClient.subscribe('/topic/trade-updates', () => {
        fetchAndRenderStatusAll();
      });

      const myUsername = localStorage.getItem('jwtUsername');
      if (myUsername) {
        wsClient.subscribe('/topic/trade-updates/' + myUsername, () => {
          fetchAndRenderStatusAll();
        });
      }
    }, () => {
      document.getElementById('footer-ws').textContent = 'WS: Reconnecting...';
      setTimeout(doConnect, 5000);
    });
  } catch (e) {
    document.getElementById('footer-ws').textContent = 'WS: Error';
  }
}

function loadScript(src, cb) {
  const s = document.createElement('script');
  s.src = src; s.onload = cb;
  document.head.appendChild(s);
}

async function get(url) {
  const res = await fetch(url, {
    headers: { 'Authorization': 'Bearer ' + token }
  });
  if (res.status === 401) { handleSessionExpired(); return; }
  if (res.status === 204) return null;
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

async function post(url, body, needAuth = true) {
  const headers = { 'Content-Type': 'application/json' };
  if (needAuth && token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
  if (res.status === 401 && needAuth) { handleSessionExpired(); return; }
  if (!res.ok) {
    const ct  = res.headers.get('content-type') || '';
    const txt = ct.includes('application/json')
      ? (await res.json().catch(() => ({}))).message
      : await res.text().catch(() => '');
    throw new Error(txt || ('HTTP ' + res.status));
  }
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) return res.json();
  return res.text();
}

function handleSessionExpired() {
  token = '';
  localStorage.removeItem('jwtToken');
  localStorage.removeItem('jwtUsername');
  localStorage.removeItem('userRole');
  localStorage.removeItem('maxLotSize');
  if (wsClient)     wsClient.close();
  if (pricePoller)  clearInterval(pricePoller);
  if (statusPoller) clearInterval(statusPoller);
  window.location.replace('/login?session=expired');
}

function fmt(n) {
  if (n === undefined || n === null || isNaN(n)) return '—';
  return Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function setAmount(id, value, large = false) {
  const el = document.getElementById(id);
  if (!el) return;
  el.textContent = (value >= 0 ? '₹' : '-₹') + fmt(Math.abs(value));
  el.className   = 'pnl-value' + (large ? ' pnl-total' : '') +
    (value >= 0 ? ' pnl-positive' : ' pnl-negative');
}

function formatIstTime(dt) {
  if (!dt) return '';
  try {
    const d = typeof dt === 'string' ? new Date(dt) : new Date(dt);
    if (isNaN(d.getTime())) return '';
    return d.toLocaleTimeString('en-IN', {
      timeZone: 'Asia/Kolkata',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
      hour12: false
    });
  } catch (_) { return ''; }
}

function formatTime(dt) {
  return formatIstTime(dt);
}

function updateClock() {
  document.getElementById('footer-time').textContent =
    new Date().toLocaleTimeString('en-IN', { timeZone: 'Asia/Kolkata', hour12: false }) + ' IST';
}

function setStrategyButtonsLoading(key, loading) {
  const startBtn = document.getElementById('btn-start-' + key);
  const stopBtn  = document.getElementById('btn-stop-' + key);
  if (!startBtn || !stopBtn) return;

  if (loading) {
    startBtn.disabled = true;
    stopBtn.disabled  = true;
    startBtn.dataset.origHtml = startBtn.innerHTML;
    stopBtn.dataset.origHtml  = stopBtn.innerHTML;
    startBtn.innerHTML = '<span class="btn-spinner"></span> Loading…';
    stopBtn.innerHTML  = '<span class="btn-spinner"></span> Loading…';
  } else {
    if (startBtn.dataset.origHtml) startBtn.innerHTML = startBtn.dataset.origHtml;
    if (stopBtn.dataset.origHtml)  stopBtn.innerHTML  = stopBtn.dataset.origHtml;
    startBtn.disabled = false;
    stopBtn.disabled  = true;
  }
}

async function withBtnLoad(btnId, label, fn) {
  const btn = document.getElementById(btnId);
  let origHtml = null;
  if (btn) {
    origHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = `<span class="btn-spinner"></span> ${label}`;
  }
  try {
    return await fn();
  } finally {
    if (btn) {
      btn.innerHTML = origHtml;
      btn.disabled  = false;
    }
  }
}

function showMsg(id, msg, type) {
  const el = document.getElementById(id);
  if (!el) return;
  el.textContent = msg;
  el.className   = 'msg-box msg-' + type;
  el.classList.remove('hidden');
  if (type === 'success') setTimeout(() => el.classList.add('hidden'), 5000);
  if (type === 'error' || type === 'warning') showToast(msg, type);
  else if (type === 'info') showToast(msg, 'info', 5000);
}

function showToast(msg, type = 'error', duration = 6000) {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const icons = { error: '❌', success: '✅', info: 'ℹ️', warning: '⚠️' };
  const icon  = icons[type] || '❌';

  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;
  toast.innerHTML = `
    <span class="toast-icon">${icon}</span>
    <span class="toast-body" style="flex:1">${escapeHtml(msg)}</span>
    <span class="toast-close" title="Dismiss">✕</span>`;

  const dismiss = () => {
    toast.classList.add('toast-hiding');
    toast.addEventListener('animationend', () => toast.remove(), { once: true });
  };

  toast.querySelector('.toast-close').addEventListener('click', dismiss);
  toast.addEventListener('click', dismiss);
  container.appendChild(toast);

  if (duration > 0) setTimeout(dismiss, duration);
}

function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// ===== SIDEBAR NAV + MASTER-DETAIL SWITCHING =====
// Instead of showing 4 full cards at once (grid) or collapsing them into an accordion, this
// design keeps a persistent left-hand nav listing all 4 strategies (icon, live status, P&L),
// and only ever renders ONE full strategy card in the main detail panel at a time — selected
// by clicking a nav item. All 4 cards stay mounted (hidden via CSS) so status polling/WebSocket
// updates keep every strategy live in the background regardless of which one is on screen.

// All "views" that can be shown in the detail panel: the 4 strategy cards plus the NIFTY OI
// signal card (moved into the sidebar nav + detail panel, same selection mechanism as a strategy).
const VIEW_KEYS = [...STRATEGIES.map(s => s.key), 'OI_SIGNAL'];

function selectView(key) {
  selectedStrategy = key;
  localStorage.setItem('selectedStrategy', key);

  VIEW_KEYS.forEach(k => {
    const card = document.getElementById('card-' + k);
    const item = document.getElementById('side-item-' + k);
    const isActive = k === key;
    if (card) card.classList.toggle('active-view', isActive);
    if (item) item.classList.toggle('active', isActive);
  });

  const detail = document.getElementById('strategy-detail');
  if (detail) detail.scrollTop = 0;
}

function buildSidebarHtml() {
  const groups = STRATEGY_GROUPS.map(g => {
    const items = STRATEGIES.filter(s => s.index === g.key).map(s => `
        <div class="side-item" id="side-item-${s.key}" onclick="selectView('${s.key}')">
          <span class="side-item-icon">${s.icon}</span>
          <div class="side-item-main">
            <div class="side-item-title">${s.breakout ? 'Breakout' : 'Scalping'}</div>
            <div class="side-item-sub">
              <span class="side-dot side-dot-idle" id="side-dot-${s.key}"></span>
              <span id="side-status-${s.key}">IDLE</span>
            </div>
          </div>
          <div class="side-item-pnl" id="side-pnl-${s.key}">₹0.00</div>
          <button type="button" class="side-item-stop" id="side-stop-${s.key}"
                  onclick="event.stopPropagation(); stopStrategy('${s.key}');" title="Exit ${s.title}">⏹️</button>
        </div>`).join('');
    return `
      <div class="side-group">
        <div class="side-group-label">${g.icon} ${g.title}</div>
        ${items}
      </div>`;
  }).join('');

  const oiGroup = `
      <div class="side-group">
        <div class="side-group-label">📡 MARKET DATA</div>
        <div class="side-item" id="side-item-OI_SIGNAL" onclick="selectView('OI_SIGNAL')">
          <span class="side-item-icon">🎯</span>
          <div class="side-item-main">
            <div class="side-item-title">NIFTY Reversal Signal</div>
            <div class="side-item-sub">
              <span class="side-dot side-dot-idle" id="oi-side-dot"></span>
              <span id="oi-side-status">—</span>
            </div>
          </div>
        </div>
      </div>`;

  return groups + oiGroup + `
    <div class="sidebar-stopall-wrap">
      <div class="sidebar-cumulative-pnl" id="sidebar-cumulative-pnl">
        <div class="scp-row">
          <div class="scp-col">
            <div class="scp-label">Active P&amp;L</div>
            <div class="scp-value" id="sidebar-active-pnl-value">₹0.00</div>
          </div>
          <div class="scp-col">
            <div class="scp-label">Total P&amp;L (Today)</div>
            <div class="scp-value" id="sidebar-total-pnl-value">₹0.00</div>
          </div>
        </div>
      </div>
      <button id="btn-stop-all" class="btn btn-danger btn-lg btn-full" onclick="stopAllStrategies()" disabled title="Stop every active strategy">🛑 Stop All</button>
    </div>`;
}

// ===== CARD TEMPLATE =====
function buildCardHtml(s) {
  const k = s.key;
  // Manual strike selection only — Auto ATM has been removed, so the strike-mode picker
  // is gone entirely and the CE/PE strike dropdowns are always shown.
  // Bank Nifty now trades monthly contracts (NSE moved it off weekly expiry) — the dropdown
  // values stay CURRENT_WEEK/NEXT_WEEK (that's what the backend expects), only the label text
  // shown to the user changes for Bank Nifty cards.
  const expiryOptCurrent = s.index === 'BANKNIFTY' ? 'Current Month' : 'Current Week';
  const expiryOptNext    = s.index === 'BANKNIFTY' ? 'Next Month'    : 'Next Week';
  const strikeModeBlock = `
        <div class="form-row">
          <div class="form-group">
            <label>Expiry</label>
            <select id="cfg-expiry-${k}">
              <option value="CURRENT_WEEK">${expiryOptCurrent}</option>
              <option value="NEXT_WEEK">${expiryOptNext}</option>
            </select>
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label>CE Strike</label>
            <select id="cfg-ce-strike-${k}"><option>—</option></select>
          </div>
          <div class="form-group">
            <label>PE Strike</label>
            <select id="cfg-pe-strike-${k}"><option>—</option></select>
          </div>
        </div>`;

  const reversalRow = s.breakout ? `
        <div class="form-row">
          <div class="form-group" style="flex-direction:row;align-items:center;gap:8px;">
            <input type="checkbox" id="cfg-reversal-enabled-${k}" style="width:auto;" checked/>
            <label style="text-transform:none;font-size:13px;">Enable Reversal (CE ⇄ PE)</label>
          </div>
        </div>` : '';

  return `
  <div class="panel strategy-card" id="card-${k}">
    <div class="card-header">
      <div class="card-title-group">
        <span class="card-icon">${s.icon}</span>
        <div>
          <div style="font-weight:700;font-size:15px;">${s.title}</div>
          <div class="card-subtitle">${s.subtitle}</div>
        </div>
      </div>
      <span id="strategy-badge-${k}" class="badge badge-idle">IDLE</span>
    </div>

    <div class="card-body-grid">
    <div class="card-col-left">
    <details class="card-config">
      <summary>Configuration</summary>
      <div class="form-row">
        <div class="form-group">
          <label>Direction</label>
          <select id="cfg-direction-${k}">
            <option value="BUY">Buy</option>
            <option value="SELL">Sell</option>
          </select>
        </div>
        <div class="form-group">
          <label>Trade Mode</label>
          <select id="cfg-trade-mode-${k}">
            <option value="PAPER">Paper</option>
            <option value="LIVE">Live</option>
          </select>
        </div>
      </div>
      <div class="form-row ${s.breakout ? 'hidden' : ''}">
        <div class="form-group">
          <label>Futures Instrument</label>
          <select id="cfg-futures-${k}" onchange="onFuturesChange('${k}')"><option>Loading…</option></select>
        </div>
      </div>
      ${strikeModeBlock}
      <div class="form-row">
        <div class="form-group">
          <label>Start Time</label>
          <select id="cfg-start-time-${k}"></select>
        </div>
        <div class="form-group">
          <label>Lots</label>
          <input type="number" id="cfg-lot-qty-${k}" value="1" min="1"/>
          <span id="qty-total-hint-${k}" class="admin-only" style="font-size:11px;color:var(--text2);">Qty: <span id="qty-total-${k}">65</span></span>
          <span id="qty-total-hint-user-${k}" class="user-only" style="font-size:11px;color:var(--text2);">Qty: <span id="qty-total-user-${k}">65</span></span>
        </div>
      </div>
      <div class="form-row admin-only">
        <div class="form-group">
          <label>Max Reversals</label>
          <input type="number" id="cfg-max-reversals-${k}" value="10" min="0"/>
        </div>
      </div>
      ${reversalRow}
      <div class="form-row">
        <div class="form-group">
          <label>Target (₹)</label>
          <input type="number" id="cfg-target-${k}" value="2000"/>
        </div>
        <div class="form-group">
          <label style="display:flex;align-items:center;gap:6px;">
            <input type="checkbox" id="sl-enabled-${k}" style="width:auto;" onchange="toggleSlEnabled('${k}')"/> Stop Loss (₹)
          </label>
          <input type="number" id="cfg-sl-${k}" value="1000" disabled style="opacity:0.5;"/>
        </div>
      </div>
      <div class="form-row">
        <div class="form-group">
          <label>Trailing Step (₹)</label>
          <input type="number" id="cfg-trailing-${k}" value="0"/>
        </div>
        <div class="form-group" style="flex-direction:row;align-items:center;gap:8px;">
          <input type="checkbox" id="cfg-eod-${k}" style="width:auto;" checked/>
          <label style="text-transform:none;font-size:13px;">Square-off EOD</label>
        </div>
      </div>
      <button class="btn btn-primary btn-full" onclick="saveConfig('${k}')">💾 Save Configuration</button>
      <div id="config-msg-${k}" class="msg-box hidden"></div>
    </details>

    <div class="strategy-controls">
      <button id="btn-start-${k}" class="btn btn-success" onclick="startStrategy('${k}')" disabled>▶️ Start</button>
      <button id="btn-stop-${k}"  class="btn btn-danger hidden" onclick="stopStrategy('${k}')" disabled>⏹️ Exit</button>
    </div>

    <div id="live-params-section-${k}" class="live-params hidden">
      <div class="live-params-title">⚙️ Live Parameter Adjustment</div>
      <div class="live-params-row">
        <div class="form-group">
          <label>Target (₹)</label>
          <input type="number" id="live-target-${k}"/>
        </div>
        <div class="form-group">
          <label style="display:flex;align-items:center;gap:6px;">
            <input type="checkbox" id="live-sl-enabled-${k}" style="width:auto;" onchange="toggleLiveSlEnabled('${k}')"/> SL (₹)
          </label>
          <input type="number" id="live-sl-${k}" disabled style="opacity:0.5;"/>
        </div>
        <div class="form-group">
          <label>Trailing (₹)</label>
          <input type="number" id="live-trailing-${k}"/>
        </div>
        <button class="btn btn-sm btn-primary" onclick="updateLiveParams('${k}')">Update</button>
      </div>
      <div id="live-params-msg-${k}" class="msg-box hidden"></div>
    </div>

    <div class="active-config-strip hidden" id="active-config-strip-${k}">
      <span class="cfg-chip" id="cfg-chip-future-${k}">—</span>
      <span class="cfg-chip" id="cfg-chip-dir-${k}">—</span>
      <span class="cfg-chip" id="cfg-chip-strike-${k}">—</span>
      <span class="cfg-chip" id="cfg-chip-expiry-${k}">—</span>
      <span class="cfg-chip cfg-chip-time" id="cfg-chip-time-${k}">—</span>
      <span class="cfg-chip cfg-chip-target" id="cfg-chip-target-${k}">—</span>
      <span class="cfg-chip cfg-chip-sl" id="cfg-chip-sl-${k}">—</span>
      <span class="cfg-chip" id="cfg-chip-lots-${k}">—</span>
      <span class="cfg-chip" id="cfg-chip-mode-${k}">—</span>
      <span class="cfg-chip" id="cfg-chip-ce-${k}">CE —</span>
      <span class="cfg-chip" id="cfg-chip-pe-${k}">PE —</span>
    </div>
    </div>

    <div class="card-col-right">
    <div class="price-grid">
      <div class="price-card ${s.breakout ? 'hidden' : ''}">
        <div class="price-label" id="futures-label-${k}">FUT</div>
        <div class="price-value" id="price-futures-${k}">—</div>
      </div>
      <div class="price-card ce-card">
        <div class="price-label" id="ce-label-${k}">CE</div>
        <div class="price-value" id="price-ce-${k}">—</div>
      </div>
      <div class="price-card pe-card">
        <div class="price-label" id="pe-label-${k}">PE</div>
        <div class="price-value" id="price-pe-${k}">—</div>
      </div>
    </div>

    <div class="status-section admin-only">
      <div class="status-row">
        <span class="status-label">State</span>
        <span class="status-value badge badge-idle" id="s-state-${k}">IDLE</span>
      </div>
      <div class="status-row">
        <span class="status-label">Started By</span>
        <span class="status-value" id="s-started-by-${k}">—</span>
      </div>
      <div class="status-row">
        <span class="status-label">Reversals</span>
        <span class="status-value" id="s-reversals-${k}">0 / 0</span>
      </div>
      <div class="status-row">
        <span class="status-label">Direction</span>
        <span class="status-value" id="s-direction-${k}">—</span>
      </div>
      <div class="status-row">
        <span class="status-label">Locked CE</span>
        <span class="status-value monospace" id="s-locked-ce-${k}">—</span>
      </div>
      <div class="status-row">
        <span class="status-label">Locked PE</span>
        <span class="status-value monospace" id="s-locked-pe-${k}">—</span>
      </div>
    </div>

    <div class="signal-monitor user-only">
      <div class="signal-card-row">
        <div class="signal-dir-card">
          <div class="signal-dir-icon" id="user-dir-icon-${k}">—</div>
          <div class="signal-dir-label" id="user-dir-label-${k}">No Position</div>
        </div>
        <div class="signal-info-card">
          <div class="signal-info-row">
            <span class="signal-info-label">Mode</span>
            <span class="signal-info-value" id="user-trade-mode-info-${k}">—</span>
          </div>
          <div class="signal-info-row">
            <span class="signal-info-label">Lots</span>
            <span class="signal-info-value" id="user-lots-info-${k}">—</span>
          </div>
          <div class="signal-info-row">
            <span class="signal-info-label">Target</span>
            <span class="signal-info-value" id="user-target-info-${k}">—</span>
          </div>
          <div class="signal-info-row">
            <span class="signal-info-label">SL</span>
            <span class="signal-info-value" id="user-sl-info-${k}">—</span>
          </div>
        </div>
      </div>
    </div>

${!s.breakout ? `
    <div class="candle-section admin-only">
      <h4>1-Min Candles</h4>
      <div class="candle-row">
        <div class="candle-box">
          <div class="c-label">1st</div>
          <div class="c-value" id="c1-${k}-close">—</div>
          <div class="c-time" id="c1-${k}-time"></div>
        </div>
        <div class="candle-box">
          <div class="c-label">2nd</div>
          <div class="c-value" id="c2-${k}-close">—</div>
          <div class="c-time" id="c2-${k}-time"></div>
        </div>
        <div class="candle-box active">
          <div class="c-label">3rd</div>
          <div class="c-value" id="c3-${k}-close">—</div>
          <div class="c-time" id="c3-${k}-time"></div>
        </div>
      </div>
    </div>` : ''}
${s.breakout ? `
    <div class="candle-section admin-only" id="ref-candle-section-${k}">
      <h4>Reference Candle (High / Low per Leg)</h4>
      <div class="candle-row">
        <div class="candle-box ce-card">
          <div class="c-label" id="ref-ce-label-${k}">CE —</div>
          <div class="c-value" id="ref-ce-${k}-close">—</div>
          <div class="c-hl" id="ref-ce-${k}-hl">H: — · L: —</div>
          <div class="c-time" id="ref-ce-${k}-time"></div>
        </div>
        <div class="candle-box pe-card">
          <div class="c-label" id="ref-pe-label-${k}">PE —</div>
          <div class="c-value" id="ref-pe-${k}-close">—</div>
          <div class="c-hl" id="ref-pe-${k}-hl">H: — · L: —</div>
          <div class="c-time" id="ref-pe-${k}-time"></div>
        </div>
      </div>
    </div>` : ''}

    <div id="stop-reason-box-${k}" class="stop-reason hidden">
      <span id="stop-reason-text-${k}"></span>
    </div>

    <div class="pnl-grid">
      <div class="pnl-card">
        <div class="pnl-label">Realized</div>
        <div class="pnl-value" id="pnl-realized-${k}">₹0.00</div>
      </div>
      <div class="pnl-card">
        <div class="pnl-label">Open</div>
        <div class="pnl-value" id="pnl-open-${k}">₹0.00</div>
      </div>
      <div class="pnl-card pnl-total-card">
        <div class="pnl-label">Total P&amp;L</div>
        <div class="pnl-value pnl-total" id="pnl-total-${k}">₹0.00</div>
      </div>
    </div>

    <div class="progress-section">
      <div class="progress-bar-wrap">
        <div class="progress-bar" id="pnl-bar-${k}" style="width:50%;"></div>
      </div>
      <div class="progress-labels">
        <span class="sl-label" id="pnl-sl-${k}">₹—</span>
        <span class="target-label" id="pnl-target-${k}">₹—</span>
      </div>
    </div>

    <div id="trailing-status-box-${k}" class="hidden" style="margin-bottom:14px;font-size:12px;">
      <div class="status-row">
        <span class="status-label" id="trailing-status-label-${k}">⏳ Waiting for Target</span>
        <span class="status-value">HW: <span id="trailing-watermark-${k}">—</span> · Exit: <span id="trailing-exit-level-${k}">—</span></span>
      </div>
    </div>

    <button class="btn btn-outline btn-sm" style="margin-bottom:14px;" onclick="resetPnl('${k}')">🔄 Reset P&amp;L</button>

    <div class="table-wrap admin-only">
      <table class="trade-table">
        <thead>
          <tr>
            <th>Leg</th><th>Type</th><th>Entry</th><th>Entry Time</th>
            <th>Exit</th><th>Exit Time</th><th>P&amp;L</th><th>Reason</th>
          </tr>
        </thead>
        <tbody id="legs-body-${k}">
          <tr><td colspan="8" class="empty-row">No trades yet</td></tr>
        </tbody>
      </table>
    </div>

    <div class="usc-card user-only">
      <div class="usc-header">
        <span class="usc-dot usc-dot-idle" id="usc-dot-${k}"></span>
        <span class="usc-status" id="usc-status-text-${k}">Awaiting start</span>
      </div>
      <div class="usc-date" id="usc-date-${k}">—</div>
      <div class="usc-note">Trade details are managed by the system. Contact admin for a full report.</div>
    </div>
    </div>
    </div>
  </div>`;
}
