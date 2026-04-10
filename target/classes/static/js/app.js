/* ============================================================
   NIFTY Algo Trading — Frontend Application
   Backend: Spring Boot on same origin
   ============================================================ */

let token       = localStorage.getItem('jwtToken') || '';
let wsClient    = null;
let pricePoller = null;
let statusPoller = null;
let currentSession = null;   // AlgoStatusResponse shape
let savedConfig    = null;   // locally stored form values (set by saveConfig)

// instrument tokens from dropdowns (needed for /algo/start)
let futureToken = 0;
let ceToken     = 0;
let peToken     = 0;

// ============================================================
// INIT
// ============================================================
window.onload = () => {
  const params = new URLSearchParams(window.location.search);

  // Handle Kite OAuth callback (?kite=connected)
  if (params.get('kite') === 'connected') {
    window.history.replaceState({}, document.title, '/');
    if (token) {
      showDashboard();
      showMsg('config-msg', '✅ Kite connected successfully!', 'success');
      refreshKiteStatus();
    }
  }

  // Handle JWT expiry redirect (?session=expired)
  if (params.get('session') === 'expired') {
    window.history.replaceState({}, document.title, '/');
    const errEl = document.getElementById('login-error');
    if (errEl) {
      errEl.textContent = '⏰ Your session has expired. Please log in again.';
      errEl.classList.remove('hidden');
    }
  }

  if (token) { showDashboard(); init(); }
  setInterval(updateClock, 1000);
  updateClock();
};

function init() {
  connectWebSocket();
  refreshKiteStatus();
  startPricePoller();
  loadFuturesDropdown();
  startStatusPoller();
  document.getElementById('cfg-expiry').addEventListener('change', loadOptionChain);
  populateStartTimes();                    // fill start-time dropdown on load
  setInterval(populateStartTimes, 60000); // refresh every minute so past slots disappear
  // Live qty display: update "Total qty" whenever lot count changes
  const lotQtyEl = document.getElementById('cfg-lot-qty');
  if (lotQtyEl) {
    lotQtyEl.addEventListener('input', updateQtyDisplay);
    updateQtyDisplay();
  }
  // Fetch status immediately so buttons reflect the real state right away.
  // Without this, both buttons sit in default state for the first 3-second poller tick.
  setStrategyButtonsLoading(true);
  fetchAndRenderStatus();
}

/** Show how many units will be ordered (lots × 65) */
function updateQtyDisplay() {
  const lots  = parseInt(document.getElementById('cfg-lot-qty')?.value) || 1;
  const total = document.getElementById('qty-total');
  if (total) total.textContent = (lots * 65).toLocaleString('en-IN');
}

// ============================================================
// START TIME DROPDOWN  (09:15 → 15:27, future minute only)
// ============================================================

/**
 * Populates #cfg-start-time with every 1-minute slot from 09:15 to 15:27.
 * Slots at or before the CURRENT minute are disabled (greyed) — you can only
 * enter on a future candle that hasn't opened yet.
 * Preserves the previously selected value when re-populating.
 */
function populateStartTimes() {
  const sel      = document.getElementById('cfg-start-time');
  const prevVal  = sel.value;

  const now      = new Date();
  const nowMins  = now.getHours() * 60 + now.getMinutes(); // minutes since midnight

  // Market: 09:15 → 15:27  (entry at 15:27 = 1st candle, 2nd closes at 15:28, enter 15:29 = EOD)
  const START_H = 9, START_M = 15;
  const END_H   = 15, END_M  = 27;

  const startTotal = START_H * 60 + START_M;
  const endTotal   = END_H   * 60 + END_M;

  let html = '';
  for (let t = startTotal; t <= endTotal; t++) {
    const h   = Math.floor(t / 60);
    const m   = t % 60;
    const hh  = String(h).padStart(2, '0');
    const mm  = String(m).padStart(2, '0');
    const val = `${hh}:${mm}`;

    // Disable any slot whose 1st candle is at or before the current minute
    // (you need at least 2 minutes of future candles: 1st + 2nd before entry)
    // const disabled = t <= nowMins;
    const disabled = false;
    const label    = disabled ? `${val}  ✗ past` : val;
    html += `<option value="${val}" ${disabled ? 'disabled' : ''}>${label}</option>`;
  }

  sel.innerHTML = html;

  // Restore previous selection if it is still valid
  if (prevVal && !sel.querySelector(`option[value="${prevVal}"]`)?.disabled) {
    sel.value = prevVal;
  } else {
    // Auto-select the earliest valid (future) slot
    const firstValid = sel.querySelector('option:not([disabled])');
    if (firstValid) sel.value = firstValid.value;
  }
}

// ============================================================
// AUTH
// ============================================================
async function login() {
  const username = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value.trim();
  const errEl    = document.getElementById('login-error');
  errEl.classList.add('hidden');
  try {
    const res = await post('/api/auth/login', { username, password }, false);
    token = res.token;
    localStorage.setItem('jwtToken', token);
    localStorage.setItem('jwtUsername', username);   // stored for "started by" comparison
    showDashboard();
    init();
  } catch (e) {
    errEl.textContent = 'Invalid username or password';
    errEl.classList.remove('hidden');
  }
}

function logout() {
  token = '';
  localStorage.removeItem('jwtToken');
  localStorage.removeItem('jwtUsername');
  if (wsClient)     wsClient.close();
  if (pricePoller)  clearInterval(pricePoller);
  if (statusPoller) clearInterval(statusPoller);
  // Freeze the UI immediately so any in-flight fetch can't re-enable buttons
  // before the page redirect completes.
  const s = document.getElementById('btn-start');
  const e = document.getElementById('btn-stop');
  if (s) s.disabled = true;
  if (e) e.disabled = true;
  window.location.href = '/';
}

function showDashboard() {
  document.getElementById('login-screen').classList.add('hidden');
  document.getElementById('dashboard').classList.remove('hidden');
}

// ============================================================
// KITE CONNECT
// ============================================================
async function connectKite() {
  try {
    const res = await get('/api/kite/login-url');
    window.open(res.loginUrl, '_blank');
    showMsg('config-msg', '⏳ Complete Kite login in the new tab. This page will update automatically.', 'info');
    // Poll every 3s for connection (callback redirects to /?kite=connected)
    let attempts = 0;
    const poll = setInterval(async () => {
      attempts++;
      try {
        const status = await get('/api/kite/status');
        if (status.connected) {
          clearInterval(poll);
          await refreshKiteStatus();
          loadFuturesDropdown();
          loadOptionChain();
          showMsg('config-msg', '✅ Kite connected! Instruments loading...', 'success');
        }
      } catch (_) {}
      if (attempts >= 60) clearInterval(poll); // stop after 3 minutes
    }, 3000);
  } catch (e) {
    showMsg('config-msg', '❌ Failed to get Kite login URL', 'error');
  }
}

async function setManualToken() {
  const t = document.getElementById('manual-token').value.trim();
  if (!t) return;
  await withBtnLoad('btn-set-token', '⏳ Saving...', async () => {
  try {
    // Single endpoint — saves per-user token AND updates global Kite connection + WebSocket
    await post('/api/kite/my-access-token', { accessToken: t });
    showMsg('config-msg', '✅ Kite token saved — connected!', 'success');
    await refreshKiteStatus();
    loadFuturesDropdown();
    loadOptionChain();
  } catch (e) {
    showMsg('config-msg', '❌ Failed to set token: ' + e.message, 'error');
  }
  }); // end withBtnLoad
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
    showMsg('config-msg', '🔌 Kite disconnected. Set a new token to reconnect.', 'info');
    await refreshKiteStatus();
  } catch (e) {
    showMsg('config-msg', '❌ ' + (e.message || 'Disconnect failed'), 'error');
  }
}

// ============================================================
// CONFIGURATION — Futures & Option Chain
// ============================================================

/**
 * Load NIFTY futures into the futures dropdown.
 * Stores instrumentToken in data-token attribute for later use.
 * Endpoint: GET /api/kite/instruments/futures
 * Returns: [{tradingsymbol, expiry, instrumentToken, lotSize, ...}]
 */
async function loadFuturesDropdown() {
  try {
    const instruments = await get('/api/kite/instruments/futures');
    const sel = document.getElementById('cfg-futures');
    if (instruments && instruments.length > 0) {
      sel.innerHTML = instruments.map(i =>
        `<option value="${i.tradingsymbol}" data-token="${i.instrumentToken || 0}">
          ${i.tradingsymbol} (exp: ${i.expiry || '—'})
        </option>`
      ).join('');

      // Restore previously saved futures symbol so the dropdown shows what user last used
      if (savedConfig && savedConfig.futureSymbol) {
        const match = sel.querySelector(`option[value="${savedConfig.futureSymbol}"]`);
        if (match) sel.value = savedConfig.futureSymbol;
      }

      // Capture token + load option chain for the selected instrument
      onFuturesChange();
    }
  } catch (_) { /* keep defaults */ }
}

/**
 * Load NIFTY option chain for the selected futures + expiry type.
 * Stores ceToken/peToken in data-token attributes on each option element.
 * Endpoint: GET /api/kite/instruments/options?expiryType=&futuresSymbol=
 * Returns: OptionChainResponse with strikes[{strikePrice, ceInstrument, ceToken, peInstrument, peToken, ceLtp, peLtp, isAtm}]
 */
async function loadOptionChain() {
  const futures    = document.getElementById('cfg-futures').value;
  const expiry     = document.getElementById('cfg-expiry').value;
  const niftyPrice = parseFloat(document.getElementById('price-futures').textContent) || undefined;

  try {
    let url = `/api/kite/instruments/options?expiryType=${expiry}&futuresSymbol=${futures}`;
    if (niftyPrice && niftyPrice > 0) url += `&niftyPrice=${niftyPrice}`;
    const chain = await get(url);
    populateStrikeDropdowns(chain);
  } catch (e) {
    console.warn('Option chain load error:', e);
  }
}

function populateStrikeDropdowns(chain) {
  if (!chain || !chain.strikes) return;
  const strikes = chain.strikes;

  const ceOpts = strikes
    .filter(s => s.ceInstrument)
    .map(s =>
      `<option value="${s.ceInstrument}" data-token="${s.ceToken || 0}" data-strike="${s.strikePrice}" ${s.atm ? 'selected' : ''}>
        ${s.strikePrice} CE ${s.atm ? '' : ''} ${s.ceLtp > 0 ? '(₹' + s.ceLtp.toFixed(1) + ')' : ''}
      </option>`
    ).join('');

  const peOpts = strikes
    .filter(s => s.peInstrument)
    .map(s =>
      `<option value="${s.peInstrument}" data-token="${s.peToken || 0}" data-strike="${s.strikePrice}" ${s.atm ? 'selected' : ''}>
        ${s.strikePrice} PE ${s.atm ? '' : ''} ${s.peLtp > 0 ? '(₹' + s.peLtp.toFixed(1) + ')' : ''}
      </option>`
    ).join('');

  document.getElementById('cfg-ce-strike').innerHTML = ceOpts || '<option>No data</option>';
  document.getElementById('cfg-pe-strike').innerHTML = peOpts || '<option>No data</option>';
}

function onStrikeModeChange() {
  const mode = document.getElementById('cfg-strike-mode').value;
  document.getElementById('manual-strike-section').style.display =
    mode === 'MANUAL' ? 'block' : 'none';
}

function onFuturesChange() {
  // Update stored token when futures selection changes
  const sel = document.getElementById('cfg-futures');
  const opt = sel.selectedOptions[0];
  futureToken = opt ? parseInt(opt.dataset.token || 0) : 0;
  // NOTE: 'cfg-lot-size' element does NOT exist — we use 'cfg-lot-qty' (number of lots).
  // Lot size is always 65 (fixed by NSE) and is NOT user-configurable.
  loadOptionChain();
}

/**
 * Save config locally (validates form, stores to savedConfig, no API call).
 * The actual /algo/start POST happens when Start Strategy is clicked.
 */
async function saveConfig() {
  const strikeMode  = document.getElementById('cfg-strike-mode').value;
  const ceSel       = document.getElementById('cfg-ce-strike');
  const peSel       = document.getElementById('cfg-pe-strike');
  const futSel      = document.getElementById('cfg-futures');
  const expiry      = document.getElementById('cfg-expiry').value;

  // ── Start-time validation ──────────────────────────────────────
  const startTimeSel = document.getElementById('cfg-start-time');
  // const selOpt = startTimeSel.selectedOptions[0];
  // if (!selOpt || selOpt.disabled) {
  //   showMsg('config-msg', '❌ Selected start time has already passed. Please choose a future minute.', 'error');
  //   populateStartTimes();   // refresh so the user sees updated options
  //   return;
  // }
  const [selH, selM] = startTimeSel.value.split(':').map(Number);
  const now = new Date();
  const nowMins = now.getHours() * 60 + now.getMinutes();
  // if (selH * 60 + selM <= nowMins) {
  //   showMsg('config-msg', '❌ Start time must be a future minute candle (strictly after current time).', 'error');
  //   populateStartTimes();
  //   return;
  // }
  // ──────────────────────────────────────────────────────────────

  // Read futures token
  const futOpt = futSel.selectedOptions[0];
  futureToken = futOpt ? parseInt(futOpt.dataset.token || 0) : 0;

  let ceSymbol = '', peSymbol = '';
  ceToken = 0; peToken = 0;

  if (strikeMode === 'MANUAL') {
    const ceOpt = ceSel.selectedOptions[0];
    const peOpt = peSel.selectedOptions[0];
    ceSymbol = ceSel.value;
    peSymbol = peSel.value;
    ceToken  = ceOpt ? parseInt(ceOpt.dataset.token || 0) : 0;
    peToken  = peOpt ? parseInt(peOpt.dataset.token || 0) : 0;
  }

  // Sync the hidden cfg-eod from the visible alt checkbox (if trailing row is hidden)
  const eodChecked = document.getElementById('cfg-eod').checked;

  const lots = parseInt(document.getElementById('cfg-lot-qty').value) || 1;
  savedConfig = {
    futureSymbol:    futSel.value,
    futureToken,
    ceSymbol,  ceToken,
    peSymbol,  peToken,
    expiryType:      expiry,
    entryStartTime:  startTimeSel.value,
    strikeMode:      strikeMode === 'AUTO_ATM' ? 'AUTO' : 'MANUAL',
    lotQuantity:     lots,          // number of lots (1 lot = 65 qty, hardcoded by NIFTY spec)
    maxReversals:    parseInt(document.getElementById('cfg-max-reversals').value),
    targetPrice:     parseFloat(document.getElementById('cfg-target').value),
    stopLoss:        parseFloat(document.getElementById('cfg-sl').value),
    trailingProfit:  parseFloat(document.getElementById('cfg-trailing').value) || 0,
    squareOffEod:    eodChecked,
    paperTrade:      document.getElementById('cfg-trade-mode').value === 'PAPER',
  };

  updateFooter(savedConfig.paperTrade ? 'PAPER' : 'LIVE');
  showMsg('config-msg', '✅ Configuration ready. Click Start Strategy to begin.', 'success');
}

// ============================================================
// STRATEGY CONTROL
// ============================================================

/**
 * Start the algo — sends AlgoStartRequest to POST /algo/start.
 * Uses the tokens collected from dropdown data-token attributes.
 * The backend subscribes these tokens to the KiteTicker WebSocket.
 */
async function startStrategy() {
  // Auto-build request from current form values if saveConfig wasn't called
  if (!savedConfig) await saveConfig();

  setStrategyButtonsLoading(true);
  try {
    await post('/algo/start', savedConfig);
    showMsg('config-msg', '▶️ Strategy started!', 'success');
    fetchAndRenderStatus();  // clears loading + sets correct button states
  } catch (e) {
    const msg = e.message || 'Start failed';
    // 409 = another user's session is already running
    const isConflict = msg.includes('409') || msg.toLowerCase().includes('already running');
    showMsg('config-msg',
      isConflict ? '⚠️ ' + msg : '❌ ' + msg,
      isConflict ? 'info' : 'error');
    // Let fetchAndRenderStatus decide the definitive state in all error cases
    fetchAndRenderStatus();
  }
}

/**
 * Stop the algo — POST /algo/stop
 */
async function stopStrategy() {
  setStrategyButtonsLoading(true);
  try {
    await post('/algo/stop', {});
    fetchAndRenderStatus();  // clears loading + sets correct button states
  } catch (e) {
    showMsg('config-msg', '❌ ' + (e.message || 'Stop failed'), 'error');
    fetchAndRenderStatus();  // re-sync state (stop may have partially succeeded)
  }
}

// ============================================================
// STATUS POLLING  (GET /algo/status → AlgoStatusResponse)
// ============================================================
function startStatusPoller() {
  if (statusPoller) clearInterval(statusPoller);
  statusPoller = setInterval(fetchAndRenderStatus, 3000);
}

async function fetchAndRenderStatus() {
  if (!token) return;   // user logged out — don't touch the UI
  try {
    const session = await get('/algo/status');
    // Clear loading state FIRST (resets disabled to defaults: Start on, Stop off).
    // renderSession then overrides disabled correctly based on actual session data.
    setStrategyButtonsLoading(false);
    if (session) {
      currentSession = session;
      renderSession(session);
    }
  } catch (_) {
    // On network error fall back to defaults so buttons are never stuck disabled.
    setStrategyButtonsLoading(false);
  }
}

// ============================================================
// RENDER SESSION  (AlgoStatusResponse shape)
// ============================================================
function renderSession(s) {
  if (!s) return;

  const status = s.status || 'STOPPED';   // WAITING | RUNNING | TARGET_HIT | SL_HIT | STOPPED

  // ---- Strategy badge ----
  const stratBadge = document.getElementById('strategy-badge');
  stratBadge.textContent = 'Strategy: ' + status;
  stratBadge.className   = 'badge badge-' + status.toLowerCase().replace(/_/g, '');

  // ---- Active Config Strip ----
  // Show a compact pill row so the user always knows what params are running
  const strip = document.getElementById('active-config-strip');
  if (strip) {
    strip.classList.remove('hidden');
    const setText = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };
    setText('cfg-chip-future', s.futureSymbol || '—');
    setText('cfg-chip-time',   s.entryStartTime ? '⏱ ' + s.entryStartTime : '—');
    setText('cfg-chip-target', 'Target ₹' + fmt(s.targetPnL || 0));
    setText('cfg-chip-sl',     'SL ₹'     + fmt(s.stopLossPoints || 0));
    setText('cfg-chip-lots',   (s.lotQuantity || 1) + ' lot × 65 = ' + (s.totalQuantity || 65) + ' qty');
    setText('cfg-chip-mode',   s.paperTrade ? '📄 Paper' : '🔴 Live');
    setText('cfg-chip-strike', s.strikeMode === 'AUTO_ATM' ? '🎯 Auto ATM' : '✋ Manual Strike');
  }

  // ---- State chip ----
  const stateEl = document.getElementById('s-state');
  stateEl.textContent = status;
  stateEl.className   = 'status-value badge badge-' + status.toLowerCase().replace(/_/g, '');

  // ---- Started by ----
  const startedByEl = document.getElementById('s-started-by');
  if (startedByEl) {
    startedByEl.textContent = s.startedBy || '—';
    // Highlight when someone else's session is running (compare against logged-in user)
    const me = localStorage.getItem('jwtUsername') || '';
    startedByEl.style.color = (s.startedBy && me && s.startedBy !== me)
      ? 'var(--warning, #f59e0b)' : 'inherit';
  }

  // ---- Reversals ----
  document.getElementById('s-reversals').textContent =
    (s.reversalCount || 0) + ' / ' + (s.maxReversals || 0);

  // ---- Direction (current open position CE/PE) ----
  const dirEl = document.getElementById('s-direction');
  dirEl.textContent  = s.currentPosition || '—';
  dirEl.style.color  = s.currentPosition === 'CE' ? '#58a6ff'
                     : s.currentPosition === 'PE' ? '#f85149' : 'inherit';

  // ---- Locked instruments (CE/PE symbols) ----
  // currentSymbol holds whichever leg is open; use history to find both
  const hist = s.history || [];
  const ceRows = hist.filter(r => r.position === 'CE');
  const peRows = hist.filter(r => r.position === 'PE');
  const ceSym  = ceRows.length ? ceRows[ceRows.length - 1].symbol : '—';
  const peSym  = peRows.length ? peRows[peRows.length - 1].symbol : '—';
  document.getElementById('s-locked-ce').textContent = ceSym;
  document.getElementById('s-locked-pe').textContent = peSym;

  // Update price card labels
  if (ceSym !== '—') document.getElementById('ce-label').textContent = ceSym;
  if (peSym !== '—') document.getElementById('pe-label').textContent = peSym;
  if (s.futureSymbol) {
    document.getElementById('futures-label').textContent = s.futureSymbol;
    document.getElementById('nav-nifty-price').textContent =
      'NIFTY ' + (document.getElementById('price-futures').textContent || '—');
  }

  // Update current option price in active price card
  if (s.currentPosition === 'CE' && s.currentOptionPrice > 0) {
    document.getElementById('price-ce').textContent = fmt(s.currentOptionPrice);
  } else if (s.currentPosition === 'PE' && s.currentOptionPrice > 0) {
    document.getElementById('price-pe').textContent = fmt(s.currentOptionPrice);
  }

  // ---- Candles ----
  // Always call renderCandle even when null so boxes clear on new-session / restart.
  // renderCandle handles null by resetting to '—'.
  renderCandle(s.firstCandle  || null, 'c1');
  renderCandle(s.secondCandle || null, 'c2');
  renderCandle(s.thirdCandle  || null, 'c3');

  // ---- P&L ----
  const realized = s.cumulativePnL          || 0;
  const openPnl  = s.currentLegUnrealizedPnL || 0;
  const total    = s.totalPnL               || 0;
  const target   = s.targetPnL              || 0;
  const sl       = s.stopLossPoints         || 0;

  setAmount('pnl-realized', realized);
  setAmount('pnl-open',     openPnl);
  setAmount('pnl-total',    total, true);
  document.getElementById('pnl-target').textContent = '₹' + fmt(target);
  document.getElementById('pnl-sl').textContent     = '₹' + fmt(sl);

  // ---- Trailing profit status ----
  const trailingBox = document.getElementById('trailing-status-box');
  if (trailingBox) {
    const trStep = s.trailingProfit || 0;
    if (trStep > 0) {
      trailingBox.classList.remove('hidden');
      const hwEl    = document.getElementById('trailing-watermark');
      const exitEl  = document.getElementById('trailing-exit-level');
      const labelEl = document.getElementById('trailing-status-label');
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
        if (labelEl) labelEl.style.color = 'var(--muted)';
      }
    } else {
      trailingBox.classList.add('hidden');
    }
  }

  // Progress bar: left = -SL, centre = 0, right = Target / trailing watermark
  const effectiveTarget = (s.trailingProfit > 0 && s.trailingActive)
    ? s.trailingHighWatermark : target;
  const range = effectiveTarget + sl;
  const pct   = range > 0 ? ((total + sl) / range) * 100 : 50;
  const bar   = document.getElementById('pnl-bar');
  bar.style.width      = Math.max(0, Math.min(100, pct)) + '%';
  bar.style.background = total >= 0 ? 'var(--success)' : 'var(--danger)';

  // ---- Stop reason ----
  const stopBox = document.getElementById('stop-reason-box');
  if (s.stopReason) {
    stopBox.classList.remove('hidden');
    document.getElementById('stop-reason-text').textContent = s.stopReason;
  } else {
    stopBox.classList.add('hidden');
  }

  // ---- Control buttons ----
  const isStopped = !s.active;
  document.getElementById('btn-start').disabled = !isStopped;
  document.getElementById('btn-stop').disabled  =  isStopped;

  // ---- Footer mode ----
  updateFooter(s.paperTrade ? 'PAPER' : 'LIVE');

  // ---- Trade history table ----
  renderHistory(s.history || []);
}

/**
 * Render a CandleInfo {close, time} into candle boxes.
 * When candle is null (not yet captured), clears the box so stale data from
 * a previous session never lingers on screen after a restart.
 */
function renderCandle(candle, prefix) {
  const closeEl = document.getElementById(prefix + '-close');
  const timeEl  = document.getElementById(prefix + '-time');
  if (!candle) {
    // Clear — no candle captured yet for this session
    if (closeEl) closeEl.textContent = '—';
    if (timeEl)  timeEl.textContent  = '';
    return;
  }
  if (closeEl) closeEl.textContent = fmt(candle.close);
  if (timeEl)  timeEl.textContent  = candle.time || '';
}

/**
 * Render HistoryRow[] into the trade legs table.
 * Columns: # | Type | Entry Price | Entry Time | Exit Price | P&L (₹) | Reason/Status
 */
function renderHistory(rows) {
  const tbody = document.getElementById('legs-body');
  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty-row">No trades yet</td></tr>';
    return;
  }

  tbody.innerHTML = rows.map((row, idx) => {
    const isOpen    = row.exitReason === 'OPEN';
    const pnl       = row.pnlAmount || 0;
    const pnlClass  = pnl >= 0 ? 'pnl-positive' : 'pnl-negative';
    const typeTag   = `<span class="tag-${(row.position || '').toLowerCase()}">${row.position}</span>`;

    // Highlight new leg after a reversal (all legs after leg 1)
    const isReversal   = !isOpen && row.exitReason && row.exitReason.startsWith('REVERSAL');
    const rowStyle     = isReversal ? ' style="border-left:3px solid #f59e0b;"' : '';
    const openRowStyle = (isOpen && idx > 0) ? ' style="border-left:3px solid #3fb950;"' : '';

    const reasonBadge = isOpen
      ? `<span style="color:#3fb950;font-weight:600;font-size:11px;">⬤ OPEN</span>`
      : `<span style="font-size:11px;color:var(--text2);">${row.exitReason || '—'}${row.exitTime ? '<br><small>' + formatTime(row.exitTime) + '</small>' : ''}</span>`;

    return `<tr${isOpen ? openRowStyle : rowStyle}>
      <td style="font-weight:700;">${row.legNumber}</td>
      <td>${typeTag}<br><span style="font-size:10px;color:var(--text2);">${row.symbol || ''}</span></td>
      <td class="monospace">₹${fmt(row.entryPrice)}</td>
      <td style="font-size:11px;color:var(--text2);">${formatTime(row.entryTime)}</td>
      <td>${isOpen
        ? '<span style="color:var(--text2);font-size:12px;">—</span>'
        : `<span class="monospace">₹${fmt(row.exitPrice)}</span>`
      }</td>
      <td class="${isOpen ? '' : pnlClass}" style="font-weight:700;font-family:monospace">
        ${isOpen ? '<span style="color:var(--text2)">—</span>' : (pnl >= 0 ? '+' : '') + '₹' + fmt(pnl)}
        ${!isOpen && row.pnlPoints ? `<br><small style="font-weight:400;color:var(--text2)">${fmt(row.pnlPoints)} pts</small>` : ''}
      </td>
      <td>${reasonBadge}</td>
    </tr>`;
  }).join('');
}

// ============================================================
// PRICE POLLER  (GET /api/market-data/ticks → {symbol: {lastPrice}})
// ============================================================
function startPricePoller() {
  if (pricePoller) clearInterval(pricePoller);
  pricePoller = setInterval(async () => {
    try {
      const ticks = await get('/api/market-data/ticks');
      if (!ticks) return;

      // Futures price — use symbol from current session or saved config
      const futSym = (currentSession && currentSession.futureSymbol)
                   || (savedConfig && savedConfig.futureSymbol)
                   || document.getElementById('cfg-futures').value;

      if (futSym && ticks[futSym]) {
        const futPrice = ticks[futSym].lastPrice;
        if (futPrice > 0) {
          document.getElementById('price-futures').textContent = fmt(futPrice);
          document.getElementById('nav-nifty-price').textContent = 'NIFTY ' + fmt(futPrice);
        }
      }

      // CE / PE prices from the locked instruments in current session
      if (currentSession) {
        const hist   = currentSession.history || [];
        const ceRows = hist.filter(r => r.position === 'CE');
        const peRows = hist.filter(r => r.position === 'PE');
        const ceSym  = ceRows.length ? ceRows[ceRows.length - 1].symbol : null;
        const peSym  = peRows.length ? peRows[peRows.length - 1].symbol : null;

        if (ceSym && ticks[ceSym] && ticks[ceSym].lastPrice > 0)
          document.getElementById('price-ce').textContent = fmt(ticks[ceSym].lastPrice);
        if (peSym && ticks[peSym] && ticks[peSym].lastPrice > 0)
          document.getElementById('price-pe').textContent = fmt(ticks[peSym].lastPrice);
      }
    } catch (_) {}
  }, 2000);
}

// ============================================================
// WEBSOCKET  (STOMP — triggers a status refresh on trade events)
// ============================================================
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

    // Client-side heartbeat: send a ping every 10s, expect server pong every 10s.
    // This keeps the STOMP connection alive on Render (which closes idle WS after 30s).
    wsClient.heartbeat.outgoing = 10000;
    wsClient.heartbeat.incoming = 10000;

    wsClient.connect({}, () => {
      document.getElementById('footer-ws').textContent = 'WS: Connected';

      // Subscribe to the shared broadcast topic (backward compat)
      wsClient.subscribe('/topic/trade-updates', () => {
        fetchAndRenderStatus();
      });

      // Also subscribe to the per-user topic so this user only gets their own updates
      // even when multiple users are trading simultaneously.
      const myUsername = localStorage.getItem('jwtUsername');
      if (myUsername) {
        wsClient.subscribe('/topic/trade-updates/' + myUsername, () => {
          fetchAndRenderStatus();
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

// ============================================================
// HELPERS
// ============================================================
async function get(url) {
  const res = await fetch(url, {
    headers: { 'Authorization': 'Bearer ' + token }
  });
  if (res.status === 401) { handleSessionExpired(); return; }
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

async function post(url, body, needAuth = true) {
  const headers = { 'Content-Type': 'application/json' };
  if (needAuth && token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
  if (res.status === 401 && needAuth) { handleSessionExpired(); return; }
  if (!res.ok) {
    // Prefer a plain-text body (e.g. 409 conflict message) over JSON error
    const ct  = res.headers.get('content-type') || '';
    const txt = ct.includes('application/json')
      ? (await res.json().catch(() => ({}))).message
      : await res.text().catch(() => '');
    throw new Error(txt || ('HTTP ' + res.status));
  }
  // Some endpoints return plain string (e.g. /algo/start, /algo/stop)
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) return res.json();
  return res.text();
}

function handleSessionExpired() {
  token = '';
  localStorage.removeItem('jwtToken');
  localStorage.removeItem('jwtUsername');
  if (wsClient)     wsClient.close();
  if (pricePoller)  clearInterval(pricePoller);
  if (statusPoller) clearInterval(statusPoller);
  // Redirect to login with a message
  window.location.href = '/?session=expired';
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

function formatTime(dt) {
  if (!dt) return '';
  try {
    return new Date(dt).toLocaleTimeString('en-IN', {
      hour: '2-digit', minute: '2-digit', second: '2-digit'
    });
  } catch (_) { return ''; }
}

function updateClock() {
  document.getElementById('footer-time').textContent =
    new Date().toLocaleTimeString('en-IN', { timeZone: 'Asia/Kolkata', hour12: false }) + ' IST';
}

function updateFooter(mode) {
  document.getElementById('footer-mode').textContent =
    'Mode: ' + (mode === 'LIVE' ? '🔴 LIVE' : '📄 PAPER');
}

// ============================================================
// LOADING STATE HELPERS
// ============================================================

/**
 * Put BOTH strategy buttons into a unified "loading" state while waiting
 * for the initial status fetch (or Start/Stop API calls).
 * renderSession() will set the correct enabled/disabled state once data arrives.
 */
function setStrategyButtonsLoading(loading) {
  const startBtn = document.getElementById('btn-start');
  const stopBtn  = document.getElementById('btn-stop');
  if (!startBtn || !stopBtn) return;

  if (loading) {
    startBtn.disabled = true;
    stopBtn.disabled  = true;
    startBtn.dataset.origHtml = startBtn.innerHTML;
    stopBtn.dataset.origHtml  = stopBtn.innerHTML;
    startBtn.innerHTML = '<span class="btn-spinner"></span> Loading…';
    stopBtn.innerHTML  = '<span class="btn-spinner"></span> Loading…';
  } else {
    // Restore original labels
    if (startBtn.dataset.origHtml) startBtn.innerHTML = startBtn.dataset.origHtml;
    if (stopBtn.dataset.origHtml)  stopBtn.innerHTML  = stopBtn.dataset.origHtml;
    // Reset disabled to safe defaults: Start enabled, Stop disabled.
    // If renderSession() is called right after this it will override with the correct state.
    startBtn.disabled = false;
    stopBtn.disabled  = true;
  }
}

/**
 * Run an async function while showing a spinner on a specific button.
 * Restores the original label when done (success or error).
 * @param {string}   btnId  - element id of the button
 * @param {string}   label  - text to show while loading (e.g. '⏳ Saving…')
 * @param {Function} fn     - async function to execute
 */
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
  // Mirror errors, warnings and important info as toast popups visible anywhere on the page
  if (type === 'error' || type === 'warning') showToast(msg, type);
  else if (type === 'info') showToast(msg, 'info', 5000);
}

/**
 * Show a floating toast notification in the top-right corner.
 * type: 'error' | 'success' | 'info' | 'warning'
 * duration: auto-dismiss after N ms (0 = manual dismiss only)
 */
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
  toast.addEventListener('click', dismiss);   // click anywhere on toast to dismiss
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
