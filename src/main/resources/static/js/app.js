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
  // Handle Kite OAuth callback (?kite=connected)
  const params = new URLSearchParams(window.location.search);
  if (params.get('kite') === 'connected') {
    window.history.replaceState({}, document.title, '/');
    if (token) {
      showDashboard();
      showMsg('config-msg', '✅ Kite connected successfully!', 'success');
      refreshKiteStatus();
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
    const disabled = t <= nowMins;
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
  document.getElementById('dashboard').classList.add('hidden');
  document.getElementById('login-screen').classList.remove('hidden');
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
  try {
    await post('/api/kite/access-token', { accessToken: t });
    showMsg('config-msg', '✅ Access token set. Kite connected!', 'success');
    await refreshKiteStatus();
    loadFuturesDropdown();
    loadOptionChain();
  } catch (e) {
    showMsg('config-msg', '❌ Failed to set token: ' + e.message, 'error');
  }
}

async function refreshKiteStatus() {
  try {
    const status = await get('/api/kite/status');
    const badge  = document.getElementById('kite-status-badge');
    const banner = document.getElementById('kite-banner');
    if (status.connected) {
      badge.textContent  = '✅ Kite: Connected';
      badge.className    = 'badge badge-connected';
      banner.style.display = 'none';
    } else {
      badge.textContent  = '⚠️ Kite: Not Connected';
      badge.className    = 'badge badge-disconnected';
      banner.style.display = 'flex';
    }
  } catch (_) {}
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
        `<option value="${i.tradingsymbol}" data-token="${i.instrumentToken || 0}" data-lot="${i.lotSize || 75}">
          ${i.tradingsymbol} (exp: ${i.expiry || '—'})
        </option>`
      ).join('');
      // Pre-fill lot size from first instrument
      const first = instruments[0];
      if (first.lotSize) document.getElementById('cfg-lot-size').value = first.lotSize;
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
  if (opt && opt.dataset.lot) {
    document.getElementById('cfg-lot-size').value = opt.dataset.lot;
  }
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
  const selOpt = startTimeSel.selectedOptions[0];
  if (!selOpt || selOpt.disabled) {
    showMsg('config-msg', '❌ Selected start time has already passed. Please choose a future minute.', 'error');
    populateStartTimes();   // refresh so the user sees updated options
    return;
  }
  const [selH, selM] = startTimeSel.value.split(':').map(Number);
  const now = new Date();
  const nowMins = now.getHours() * 60 + now.getMinutes();
  if (selH * 60 + selM <= nowMins) {
    showMsg('config-msg', '❌ Start time must be a future minute candle (strictly after current time).', 'error');
    populateStartTimes();
    return;
  }
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

  savedConfig = {
    futureSymbol:    futSel.value,
    futureToken,
    ceSymbol,  ceToken,
    peSymbol,  peToken,
    expiryType:      expiry,
    entryStartTime:  startTimeSel.value,
    strikeMode:      strikeMode === 'AUTO_ATM' ? 'AUTO' : 'MANUAL',
    lotSize:         parseInt(document.getElementById('cfg-lot-size').value),
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

  try {
    await post('/algo/start', savedConfig);
    showMsg('config-msg', '▶️ Strategy started!', 'success');
    document.getElementById('btn-start').disabled = true;
    document.getElementById('btn-stop').disabled  = false;
    fetchAndRenderStatus();
  } catch (e) {
    const msg = e.message || 'Start failed';
    // 409 = another user's session is already running
    const isConflict = msg.includes('409') || msg.toLowerCase().includes('already running');
    showMsg('config-msg',
      isConflict ? '⚠️ ' + msg : '❌ ' + msg,
      isConflict ? 'info' : 'error');
    if (isConflict) fetchAndRenderStatus(); // refresh to show who owns the session
  }
}

/**
 * Stop the algo — POST /algo/stop
 */
async function stopStrategy() {
  try {
    await post('/algo/stop', {});
    document.getElementById('btn-start').disabled = false;
    document.getElementById('btn-stop').disabled  = true;
    fetchAndRenderStatus();
  } catch (e) {
    showMsg('config-msg', '❌ ' + (e.message || 'Stop failed'), 'error');
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
  try {
    const session = await get('/algo/status');
    if (session) {
      currentSession = session;
      renderSession(session);
    }
  } catch (_) {}
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
  renderCandle(s.firstCandle,  'c1');
  renderCandle(s.secondCandle, 'c2');
  renderCandle(s.thirdCandle,  'c3');

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
 */
function renderCandle(candle, prefix) {
  if (!candle) return;
  const closeEl = document.getElementById(prefix + '-close');
  const timeEl  = document.getElementById(prefix + '-time');
  if (closeEl) closeEl.textContent = fmt(candle.close);
  if (timeEl)  timeEl.textContent  = candle.time || '';
}

/**
 * Render HistoryRow[] into the trade legs table.
 * HistoryRow: {legNumber, position, symbol, entryPrice, exitPrice,
 *              pnlPoints, pnlAmount, entryTime, exitTime, exitReason}
 */
function renderHistory(rows) {
  const tbody = document.getElementById('legs-body');
  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty-row">No trades yet</td></tr>';
    return;
  }

  tbody.innerHTML = rows.map(row => {
    const isOpen   = row.exitReason === 'OPEN';
    const pnl      = row.pnlAmount || 0;
    const pnlClass = pnl >= 0 ? 'pnl-positive' : 'pnl-negative';
    const typeTag  = `<span class="tag-${(row.position || '').toLowerCase()}">${row.position}</span>`;
    const statusTag = isOpen
      ? `<span class="tag-open">Open</span>`
      : `<span class="tag-closed">Closed</span>`;

    return `<tr>
      <td>${row.legNumber}</td>
      <td>${typeTag}</td>
      <td>
        <span class="monospace">₹${fmt(row.entryPrice)}</span>
        <br><small>${formatTime(row.entryTime)}</small>
      </td>
      <td>${isOpen
        ? '<span style="color:var(--text2)">Open</span>'
        : `<span class="monospace">₹${fmt(row.exitPrice)}</span><br><small>${formatTime(row.exitTime)}</small>`
      }</td>
      <td class="${pnlClass}" style="font-weight:600;font-family:monospace">
        ${pnl >= 0 ? '+' : ''}₹${fmt(pnl)}
        <br><small style="font-weight:400">${isOpen ? '' : fmt(row.pnlPoints) + ' pts'}</small>
      </td>
      <td><small>${row.exitReason || '—'}</small></td>
      <td>${statusTag}</td>
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
    wsClient.connect({}, () => {
      document.getElementById('footer-ws').textContent = 'WS: Connected';
      // On any trade update from backend → refresh status immediately
      wsClient.subscribe('/topic/trade-updates', () => {
        fetchAndRenderStatus();
      });
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
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

async function post(url, body, needAuth = true) {
  const headers = { 'Content-Type': 'application/json' };
  if (needAuth && token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
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

function showMsg(id, msg, type) {
  const el = document.getElementById(id);
  if (!el) return;
  el.textContent = msg;
  el.className   = 'msg-box msg-' + type;
  el.classList.remove('hidden');
  if (type === 'success') setTimeout(() => el.classList.add('hidden'), 5000);
}
