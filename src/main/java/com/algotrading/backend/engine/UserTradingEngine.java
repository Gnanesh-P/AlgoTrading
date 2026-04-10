package com.algotrading.backend.engine;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.dto.AlgoStatusResponse;
import com.algotrading.backend.model.*;
import com.algotrading.backend.service.KiteInstrumentService;
import com.algotrading.backend.service.OptionInstrumentService;
import com.algotrading.backend.service.SessionPersistenceService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.*;
import com.zerodhatech.ticker.KiteTicker;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Per-user isolated trading engine.
 *
 * Each subscriber gets exactly ONE instance while their algo is active.
 * Multiple instances run concurrently — one per active user — with zero
 * shared mutable state between them.
 *
 * Responsibilities:
 *  • Owns a private KiteConnect + KiteTicker for LIVE trading (user's own Kite account).
 *  • Maintains a per-user LTP cache, candle aggregation state, and TradeSession.
 *  • Runs the full strategy: candle capture → direction decision → entry/exit/reversal.
 *  • For PAPER mode: receives ticks from the global ticker via TradingEngineRegistry.routeTickToPaperEngines().
 *  • Persists session to ./data/sessions/{username}.json for crash recovery.
 */
public class UserTradingEngine {

    private static final Logger log = LoggerFactory.getLogger(UserTradingEngine.class);

    // ── Identity ──────────────────────────────────────────────────────────────
    @Getter private final String username;
    private final PlatformUser   platformUser;

    // ── Per-user Kite (LIVE mode only) ────────────────────────────────────────
    private KiteConnect  kiteConnect;
    private KiteTicker   kiteTicker;
    private volatile boolean tickerConnected = false;

    // ── Per-user state ────────────────────────────────────────────────────────
    @Getter private volatile TradeSession session;

    /** LTP cache populated by per-user KiteTicker ticks (LIVE) or global feed (PAPER). */
    private final Map<String, Double>  priceCache     = new ConcurrentHashMap<>();
    /** Forming 1-min candles per instrument. */
    private final Map<String, Candle>  formingCandles = new ConcurrentHashMap<>();
    /** token → trading symbol mapping for this engine's KiteTicker subscription. */
    private final Map<Long, String>    tokenToSymbol  = new ConcurrentHashMap<>();
    private final Set<Long>            subscribedTokens = ConcurrentHashMap.newKeySet();

    // ── Shared (read-only) dependencies ───────────────────────────────────────
    /** Global price cache — used as LTP fallback for PAPER mode. */
    private final MarketDataCache         globalCache;
    private final OptionInstrumentService optionInstrumentService;
    private final KiteInstrumentService   kiteInstrumentService;
    private final SimpMessagingTemplate   messagingTemplate;
    private final SessionPersistenceService sessionPersistence;

    // ─────────────────────────────────────────────────────────────────────────

    public UserTradingEngine(PlatformUser platformUser,
                             MarketDataCache globalCache,
                             OptionInstrumentService optionInstrumentService,
                             KiteInstrumentService kiteInstrumentService,
                             SimpMessagingTemplate messagingTemplate,
                             SessionPersistenceService sessionPersistence) {
        this.platformUser          = platformUser;
        this.username              = platformUser.getUsername();
        this.globalCache           = globalCache;
        this.optionInstrumentService = optionInstrumentService;
        this.kiteInstrumentService = kiteInstrumentService;
        this.messagingTemplate     = messagingTemplate;
        this.sessionPersistence    = sessionPersistence;

        // Pre-create KiteConnect with user's credentials if available
        if (platformUser.getKiteApiKey() != null && !platformUser.getKiteApiKey().isBlank()) {
            this.kiteConnect = new KiteConnect(platformUser.getKiteApiKey());
            if (platformUser.getKiteAccessToken() != null) {
                this.kiteConnect.setAccessToken(platformUser.getKiteAccessToken());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  KITE TICKER MANAGEMENT  (LIVE mode only)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Connect a private KiteTicker WebSocket using this user's own Kite credentials.
     * Called only for LIVE trade mode engines.
     */
    public void connectKiteTicker() {
        if (platformUser.getKiteApiKey() == null || platformUser.getKiteAccessToken() == null) {
            log.warn("[{}] Cannot connect KiteTicker: kiteApiKey or kiteAccessToken missing in user profile", username);
            return;
        }
        try {
            kiteTicker = new KiteTicker(platformUser.getKiteAccessToken(), platformUser.getKiteApiKey());
            kiteTicker.setTryReconnection(true);
            kiteTicker.setMaximumRetries(10);
            kiteTicker.setMaximumRetryInterval(30);

            kiteTicker.setOnConnectedListener(() -> {
                log.info("[{}] KiteTicker connected", username);
                tickerConnected = true;
                if (!subscribedTokens.isEmpty()) {
                    doSubscribeTokens(new ArrayList<>(subscribedTokens));
                }
            });

            kiteTicker.setOnDisconnectedListener(() -> {
                log.warn("[{}] KiteTicker disconnected", username);
                tickerConnected = false;
            });

            kiteTicker.setOnTickerArrivalListener(this::onKiteTicksArrived);
            kiteTicker.connect();
            log.info("[{}] KiteTicker connecting (LIVE mode)...", username);
        } catch (Exception e) {
            log.error("[{}] Failed to initialize KiteTicker: {}", username, e.getMessage());
        } catch (KiteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Disconnect and clean up the per-user KiteTicker. */
    public void disconnectKiteTicker() {
        if (kiteTicker != null) {
            try { kiteTicker.disconnect(); } catch (Exception ignore) {}
            kiteTicker = null;
        }
        tickerConnected = false;
        subscribedTokens.clear();
        tokenToSymbol.clear();
        log.info("[{}] KiteTicker disconnected and cleared", username);
    }

    /**
     * Subscribe instruments to this engine's KiteTicker.
     * For LIVE engines: subscribes to own ticker.
     * For PAPER engines: just registers the token→symbol mapping; ticks come via routeTickToPaperEngines().
     */
    public void subscribeInstruments(Map<Long, String> instruments) {
        if (instruments == null || instruments.isEmpty()) return;
        tokenToSymbol.putAll(instruments);
        subscribedTokens.addAll(instruments.keySet());

        if (tickerConnected && kiteTicker != null) {
            doSubscribeTokens(new ArrayList<>(instruments.keySet()));
        }
        log.info("[{}] Registered {} instruments: {}", username, instruments.size(), instruments.values());
    }

    private void doSubscribeTokens(ArrayList<Long> tokens) {
        try {
            kiteTicker.subscribe(tokens);
            kiteTicker.setMode(tokens, KiteTicker.modeLTP);
            log.info("[{}] KiteTicker subscribed {} tokens", username, tokens.size());
        } catch (Exception e) {
            log.error("[{}] KiteTicker subscribe error: {}", username, e.getMessage());
        }
    }

    /** Called by KiteTicker's arrival listener — LIVE mode only. */
    private void onKiteTicksArrived(ArrayList<Tick> ticks) {
        if (ticks == null) return;
        for (Tick t : ticks) {
            String symbol = tokenToSymbol.get(t.getInstrumentToken());
            if (symbol == null) continue;
            double ltp = t.getLastTradedPrice();
            priceCache.put(symbol, ltp);
            // Real-time SL/Target check on every tick BEFORE candle aggregation
            checkSLTargetOnTick(symbol, ltp);
            processTickForCandle(symbol, ltp);
        }
    }

    /**
     * Entry point for PAPER-mode ticks pushed by TradingEngineRegistry.
     * Ticks come from the global KiteTicker (admin's Kite connection).
     *
     * For LIVE mode, ticks arrive directly via onKiteTicksArrived() from the
     * per-user KiteTicker — this method is NOT called in live mode.
     */
    public void processTick(MarketTick tick) {
        if (tick == null) return;
        priceCache.put(tick.getInstrument(), tick.getLastPrice());
        // Real-time SL/Target check fires on every tick BEFORE candle aggregation
        checkSLTargetOnTick(tick.getInstrument(), tick.getLastPrice());
        processTickForCandle(tick.getInstrument(), tick.getLastPrice());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  CANDLE AGGREGATION
    // ═══════════════════════════════════════════════════════════════════════

    private void processTickForCandle(String instrument, double price) {
        if (session == null || session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime candleMinute = now.truncatedTo(ChronoUnit.MINUTES);

        Candle forming = formingCandles.get(instrument);

        if (forming == null) {
            formingCandles.put(instrument, newCandle(instrument, price, candleMinute));
            return;
        }

        if (candleMinute.isAfter(forming.getOpenTime())) {
            // Minute boundary crossed — candle closed
            forming.setCloseTime(forming.getOpenTime().plusMinutes(1).minusNanos(1));
            log.debug("[{}] Candle closed: {} close={}", username, instrument, forming.getClose());

            // If this is the futures instrument, run strategy
            TradingConfig cfg = session.getConfig();
            if (cfg != null && instrument.equals(cfg.getFuturesInstrument())) {
                onCandleClose(forming);
            }

            formingCandles.put(instrument, newCandle(instrument, price, candleMinute));
        } else {
            if (price > forming.getHigh()) forming.setHigh(price);
            if (price < forming.getLow())  forming.setLow(price);
            forming.setClose(price);
        }
    }

    private Candle newCandle(String instrument, double price, LocalDateTime minute) {
        return Candle.builder()
                .instrument(instrument)
                .openTime(minute)
                .open(price).high(price).low(price).close(price)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  BROKER OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * LTP lookup: per-user cache → per-user kiteConnect REST → global cache (fallback).
     */
    double getLtp(String instrument) {
        Double cached = priceCache.get(instrument);
        if (cached != null && cached > 0) return cached;

        // For LIVE mode: try REST via user's own kiteConnect
        if (session != null && session.getConfig().getTradeMode() == TradeMode.LIVE
                && kiteConnect != null && platformUser.getKiteAccessToken() != null) {
            try {
                Map<String, LTPQuote> map = kiteConnect.getLTP(new String[]{"NFO:" + instrument});
                LTPQuote q = map != null ? map.get("NFO:" + instrument) : null;
                if (q != null && q.lastPrice > 0) {
                    priceCache.put(instrument, q.lastPrice);
                    return q.lastPrice;
                }
            } catch (Exception | KiteException e) {
                log.warn("[{}] LTP REST failed for {}: {}", username, instrument, e.getMessage());
            }
        }

        // Fallback: global cache (populated by admin's Kite ticker — useful for PAPER mode)
        double globalLtp = globalCache.getLastPrice(instrument);
        if (globalLtp > 0) return globalLtp;

        return 0;
    }

    private double placeBuyOrder(String instrument, int qty) {
        if (session.getConfig().getTradeMode() == TradeMode.PAPER) {
            double ltp = getLtp(instrument);
            log.info("[{}][PAPER] BUY {} x{} @ {}", username, instrument, qty, ltp);
            return ltp;
        }
        // LIVE — use own kiteConnect
        if (kiteConnect == null || platformUser.getKiteAccessToken() == null) {
            throw new IllegalStateException("[" + username + "] KiteConnect not initialized for live trading");
        }
        try {
            OrderParams p = new OrderParams();
            p.tradingsymbol   = instrument;
            p.exchange        = Constants.EXCHANGE_NFO;
            p.transactionType = Constants.TRANSACTION_TYPE_BUY;
            p.orderType       = Constants.ORDER_TYPE_MARKET;
            p.quantity        = qty;
            p.product         = Constants.PRODUCT_MIS;
            p.validity        = Constants.VALIDITY_DAY;
            OrderResponse order = kiteConnect.placeOrder(p, Constants.VARIETY_REGULAR);
            log.info("[{}][LIVE] BUY {} x{} → orderId={}", username, instrument, qty, order.orderId);
            return getLtp(instrument);
        } catch (Exception | KiteException e) {
            throw new RuntimeException("[" + username + "] BUY order failed: " + e.getMessage(), e);
        }
    }

    private double placeSellOrder(String instrument, int qty) {
        if (session.getConfig().getTradeMode() == TradeMode.PAPER) {
            double ltp = getLtp(instrument);
            log.info("[{}][PAPER] SELL {} x{} @ {}", username, instrument, qty, ltp);
            return ltp;
        }
        try {
            OrderParams p = new OrderParams();
            p.tradingsymbol   = instrument;
            p.exchange        = Constants.EXCHANGE_NFO;
            p.transactionType = Constants.TRANSACTION_TYPE_SELL;
            p.orderType       = Constants.ORDER_TYPE_MARKET;
            p.quantity        = qty;
            p.product         = Constants.PRODUCT_MIS;
            p.validity        = Constants.VALIDITY_DAY;
            OrderResponse order = kiteConnect.placeOrder(p, Constants.VARIETY_REGULAR);
            log.info("[{}][LIVE] SELL {} x{} → orderId={}", username, instrument, qty, order.orderId);
            return getLtp(instrument);
        } catch (Exception | KiteException e) {
            throw new RuntimeException("[" + username + "] SELL order failed: " + e.getMessage(), e);
        }
    }

    private boolean hasOpenPosition(String instrument) {
        if (session.getConfig().getTradeMode() == TradeMode.PAPER) return true;
        if (kiteConnect == null) return false;
        try {
            Map<String, List<Position>> positions = kiteConnect.getPositions();
            List<Position> net = positions.get("net");
            if (net == null) return false;
            return net.stream().anyMatch(p ->
                    instrument.equalsIgnoreCase(p.tradingSymbol) && p.netQuantity > 0);
        } catch (Exception | KiteException e) {
            log.warn("[{}] getPositions failed: {}", username, e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SESSION CONTROL
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start a new trading session.
     *
     * @param config      pre-built TradingConfig (validated by AlgoController)
     * @param instruments token→symbol map; already subscribed for LIVE, queued for PAPER
     * @param startedBy   JWT username of the requester (for audit)
     */
    public synchronized TradeSession startSession(TradingConfig config,
                                                   Map<Long, String> instruments,
                                                   String startedBy) {
        // Reset candle state
        formingCandles.clear();

        session = TradeSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .tradeDate(LocalDate.now())
                .config(config)
                .state(StrategyState.WAITING_FOR_CANDLES)
                .reversalCount(0)
                .currentLegNumber(0)
                .cumulativePnL(0.0)
                .openPnL(0.0)
                .startedBy(startedBy)
                .startTime(LocalDateTime.now())
                .tradeLegs(new ArrayList<>())
                .build();

        // Register instruments so candle aggregation filters them
        if (instruments != null) {
            tokenToSymbol.putAll(instruments);
            subscribedTokens.addAll(instruments.keySet());
        }

        sessionPersistence.saveForUser(username, session);
        log.info("[{}] Session {} started (mode={}, lots={}, qty={})",
                username, session.getSessionId(), config.getTradeMode(),
                config.getLotQuantity(), config.getTotalQuantity());
        return session;
    }

    /** Manual stop — exits open position and marks session STOPPED. */
    public synchronized TradeSession stopSession() {
        if (session == null) throw new IllegalStateException("No active session for user: " + username);
        if (session.getState() == StrategyState.IN_POSITION) {
            exitCurrentPosition("MANUAL_STOP");
        }
        internalStopSession("Manual stop");
        sessionPersistence.clearForUser(username);   // intentional stop — no recovery needed
        log.info("[{}] Session stopped manually", username);
        return session;
    }

    /** Restore a previously persisted session (crash recovery). */
    public synchronized void restoreSession(TradeSession restored) {
        this.session = restored;
        // Re-register instruments from config for candle routing
        TradingConfig cfg = restored.getConfig();
        if (cfg.getFuturesInstrument() != null) {
            kiteInstrumentService.findBySymbol(cfg.getFuturesInstrument())
                    .ifPresent(i -> tokenToSymbol.put(i.getInstrumentToken(), i.getTradingsymbol()));
        }
        if (restored.getLockedCeInstrument() != null) {
            kiteInstrumentService.findBySymbol(restored.getLockedCeInstrument())
                    .ifPresent(i -> tokenToSymbol.put(i.getInstrumentToken(), i.getTradingsymbol()));
        }
        if (restored.getLockedPeInstrument() != null) {
            kiteInstrumentService.findBySymbol(restored.getLockedPeInstrument())
                    .ifPresent(i -> tokenToSymbol.put(i.getInstrumentToken(), i.getTradingsymbol()));
        }
        subscribedTokens.addAll(tokenToSymbol.keySet());
        log.info("[{}] Session {} restored (state={})", username,
                restored.getSessionId(), restored.getState());
    }

    /** Called by TradingEngineRegistry's @Scheduled EOD job. */
    public synchronized void squareOffEod() {
        if (session == null) return;
        if (session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;
        TradingConfig cfg = session.getConfig();
        if (!cfg.isSquareOffEod()) {
            log.info("[{}] EOD reached but squareOffEod=false — no auto exit", username);
            return;
        }
        log.info("[{}] EOD 15:29 square-off triggered", username);
        if (session.getState() == StrategyState.IN_POSITION) {
            exitCurrentPosition("EOD");
        }
        internalStopSession("End of day square-off at 15:29");
        sessionPersistence.clearForUser(username);
        broadcastUpdate();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  STRATEGY LOGIC
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Real-time SL / Target check on every option tick.
     * Exits immediately when threshold is breached — does NOT wait for candle close.
     */
    synchronized void checkSLTargetOnTick(String instrument, double ltp) {
        if (session == null || session.getState() != StrategyState.IN_POSITION) return;

        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null || !instrument.equals(openLeg.getInstrument())) return;

        double liveLegPnL = (ltp - openLeg.getEntryPrice()) * openLeg.getQuantity();
        double totalPnL   = session.getTotalRealizedPnL() + liveLegPnL;
        TradingConfig cfg = session.getConfig();

        // Always update openPnL for live UI
        session.setOpenPnL(liveLegPnL);

        // ── Stop Loss ──────────────────────────────────────────────────────
        if (totalPnL <= -cfg.getStopLoss()) {
            log.info("[{}] ⚡ SL hit on tick: totalPnL={} <= -{} | ltp={}", username, totalPnL, cfg.getStopLoss(), ltp);
            exitCurrentPosition("STOPLOSS");
            internalStopSession("Stop loss hit: " + totalPnL);
            sessionPersistence.saveForUser(username, session);
            broadcastUpdate();
            return;
        }

        double trailing = cfg.getTrailingProfit();

        // ── Plain Target ───────────────────────────────────────────────────
        if (trailing <= 0 && totalPnL >= cfg.getTargetProfit()) {
            log.info("[{}] ⚡ Target hit on tick: totalPnL={} >= {} | ltp={}", username, totalPnL, cfg.getTargetProfit(), ltp);
            exitCurrentPosition("TARGET");
            internalStopSession("Target profit reached: " + totalPnL);
            sessionPersistence.saveForUser(username, session);
            broadcastUpdate();
            return;
        }

        // ── Trailing Target ────────────────────────────────────────────────
        if (trailing > 0) {
            if (!session.isTrailingActive() && totalPnL >= cfg.getTargetProfit()) {
                session.setTrailingActive(true);
                session.setTrailingHighWatermark(totalPnL);
                log.info("[{}] ⚡ Trailing activated on tick: pnl={}, step={}", username, totalPnL, trailing);
            }
            if (session.isTrailingActive()) {
                if (totalPnL > session.getTrailingHighWatermark()) {
                    session.setTrailingHighWatermark(totalPnL);
                }
                double exitLevel = session.getTrailingHighWatermark() - trailing;
                if (totalPnL <= exitLevel) {
                    log.info("[{}] ⚡ Trailing stop on tick: pnl={} <= watermark({}) - step({})",
                            username, totalPnL, session.getTrailingHighWatermark(), trailing);
                    exitCurrentPosition("TRAILING_STOP");
                    internalStopSession(String.format(
                            "Trailing stop (real-time): watermark=%.0f step=%.0f pnl=%.0f",
                            session.getTrailingHighWatermark(), trailing, totalPnL));
                    sessionPersistence.saveForUser(username, session);
                    broadcastUpdate();
                    return;
                }
            }
        }
    }

    public synchronized void onCandleClose(Candle candle) {
        if (session == null || session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;

        TradingConfig cfg = session.getConfig();
        if (!candle.getInstrument().equals(cfg.getFuturesInstrument())) return;

        log.debug("[{}] Candle: time={}, close={}", username, candle.getOpenTime(), candle.getClose());

        LocalTime candleTime = candle.getOpenTime().toLocalTime();
        LocalTime startTime  = cfg.getStartCandleTime();

        // BUG FIX: update openPnL BEFORE strategy checks so candle-close P&L is fresh
        updateOpenPnL();

        if (session.getState() == StrategyState.WAITING_FOR_CANDLES) {
            handleWaitingForCandles(candle, candleTime, startTime);
        } else if (session.getState() == StrategyState.IN_POSITION) {
            handleInPosition(candle);
        }

        sessionPersistence.saveForUser(username, session);
        broadcastUpdate();
    }

    private void handleWaitingForCandles(Candle candle, LocalTime candleTime, LocalTime startTime) {
        if (session.getFirstCandle() == null) {
            if (!candleTime.isBefore(startTime)) {
                candle.setCandleIndex(1);
                session.setFirstCandle(candle);
                log.info("[{}] 1st candle: time={}, close={}", username, candleTime, candle.getClose());
            }
        } else if (session.getSecondCandle() == null) {
            candle.setCandleIndex(2);
            session.setSecondCandle(candle);
            log.info("[{}] 2nd candle: time={}, close={} → entering at 3rd candle open",
                    username, candleTime, candle.getClose());
            enterInitialPosition();
        }
    }

    private void enterInitialPosition() {
        TradingConfig cfg   = session.getConfig();
        double close1       = session.getFirstCandle().getClose();
        double close2       = session.getSecondCandle().getClose();
        double niftyPrice   = close2 > 0 ? close2 : getLtp(cfg.getFuturesInstrument());
        if (niftyPrice <= 0) niftyPrice = close1;

        optionInstrumentService.resolveAndLockInstruments(cfg, session, niftyPrice);

        if (cfg.getStrikeMode() == StrikeMode.AUTO_ATM) {
            subscribeAndSeedAutoAtmOptions();
        }

        OptionType direction = close1 > close2 ? OptionType.PE : OptionType.CE;
        log.info("[{}] Entry direction: {} (1st={}, 2nd={})", username, direction, close1, close2);

        enterPosition(direction, "INITIAL");
        session.setState(StrategyState.IN_POSITION);
    }

    private void subscribeAndSeedAutoAtmOptions() {
        String ceSymbol = session.getLockedCeInstrument();
        String peSymbol = session.getLockedPeInstrument();

        Map<Long, String> toSub = new HashMap<>();
        kiteInstrumentService.findBySymbol(ceSymbol)
                .ifPresent(i -> toSub.put(i.getInstrumentToken(), ceSymbol));
        kiteInstrumentService.findBySymbol(peSymbol)
                .ifPresent(i -> toSub.put(i.getInstrumentToken(), peSymbol));

        if (!toSub.isEmpty()) {
            subscribeInstruments(toSub);
            log.info("[{}] AUTO_ATM: subscribed CE/PE — {}", username, toSub.values());
        } else {
            log.warn("[{}] AUTO_ATM: instrument tokens not found for CE={} PE={}", username, ceSymbol, peSymbol);
        }

        // Seed LTP for immediate entry (before first tick arrives)
        seedLtp(ceSymbol);
        seedLtp(peSymbol);
    }

    /** Fetch LTP and seed the per-user price cache. */
    private void seedLtp(String instrument) {
        if (priceCache.getOrDefault(instrument, 0.0) > 0) return;  // already warm

        // Try per-user kiteConnect REST
        if (kiteConnect != null && platformUser.getKiteAccessToken() != null) {
            try {
                Map<String, LTPQuote> map = kiteConnect.getLTP(new String[]{"NFO:" + instrument});
                LTPQuote q = map != null ? map.get("NFO:" + instrument) : null;
                if (q != null && q.lastPrice > 0) {
                    priceCache.put(instrument, q.lastPrice);
                    log.info("[{}] Seeded LTP for {} via user kiteConnect: {}", username, instrument, q.lastPrice);
                    return;
                }
            } catch (Exception | KiteException e) {
                log.warn("[{}] REST LTP seed failed for {}: {}", username, instrument, e.getMessage());
            }
        }

        // Fallback: global kiteInstrumentService (admin's Kite)
        double ltp = kiteInstrumentService.fetchCurrentLtp(instrument);
        if (ltp > 0) {
            priceCache.put(instrument, ltp);
            log.info("[{}] Seeded LTP for {} via global Kite: {}", username, instrument, ltp);
        } else {
            log.warn("[{}] LTP seed returned 0 for {} — entry may abort if still 0", username, instrument);
        }
    }

    private void handleInPosition(Candle candle) {
        session.setLastClosedCandle(candle);
        TradingConfig cfg   = session.getConfig();
        TradeEntry openLeg  = session.getCurrentOpenLeg();
        if (openLeg == null) return;

        double currentClose = candle.getClose();
        double firstClose   = session.getFirstCandle().getClose();

        boolean shouldReverse = false;
        OptionType newDir     = null;

        if (openLeg.getOptionType() == OptionType.CE && currentClose < firstClose) {
            shouldReverse = true;
            newDir = OptionType.PE;
            log.info("[{}] Reversal signal CE→PE (close={} < first={})", username, currentClose, firstClose);
        } else if (openLeg.getOptionType() == OptionType.PE && currentClose > firstClose) {
            shouldReverse = true;
            newDir = OptionType.CE;
            log.info("[{}] Reversal signal PE→CE (close={} > first={})", username, currentClose, firstClose);
        }

        if (shouldReverse) {
            if (session.getReversalCount() >= cfg.getMaxReversals()) {
                log.info("[{}] Max reversals ({}) exhausted → stopping", username, cfg.getMaxReversals());
                exitCurrentPosition("MAX_REVERSALS");
                internalStopSession("Max reversals exhausted");
                return;
            }
            exitCurrentPosition("REVERSAL");
            session.setReversalCount(session.getReversalCount() + 1);

            if (isPnLExitTriggered()) return;

            enterPosition(newDir, "REVERSAL_" + session.getReversalCount());
        }

        checkExitConditions();
    }

    private void enterPosition(OptionType optionType, String reason) {
        TradingConfig cfg  = session.getConfig();
        String instrument  = optionType == OptionType.CE
                ? session.getLockedCeInstrument() : session.getLockedPeInstrument();
        int strikePrice    = optionType == OptionType.CE
                ? session.getLockedCeStrike() : session.getLockedPeStrike();

        double ltp = getLtp(instrument);
        if (ltp <= 0) {
            log.error("[{}] No LTP for {} (LTP=0). Aborting entry [{}].", username, instrument, reason);
            internalStopSession("No LTP data for " + instrument + " — position entry aborted");
            return;
        }

        double entryPrice = placeBuyOrder(instrument, cfg.getTotalQuantity());

        TradeEntry leg = TradeEntry.builder()
                .legNumber(session.getCurrentLegNumber() + 1)
                .optionType(optionType)
                .instrument(instrument)
                .strikePrice(strikePrice)
                .expiryType(cfg.getExpiryType())
                .quantity(cfg.getTotalQuantity())
                .entryPrice(entryPrice)
                .entryTime(LocalDateTime.now())
                .closed(false)
                .build();

        session.setCurrentLegNumber(leg.getLegNumber());
        session.getTradeLegs().add(leg);
        log.info("[{}] Entered leg={} type={} instrument={} entry={} reason={}",
                username, leg.getLegNumber(), optionType, instrument, entryPrice, reason);
    }

    private void exitCurrentPosition(String reason) {
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null || openLeg.isClosed()) return;

        double exitPrice;
        if (!hasOpenPosition(openLeg.getInstrument())) {
            log.warn("[{}] No open position for {} in broker (manually squared off?)", username, openLeg.getInstrument());
            exitPrice = getLtp(openLeg.getInstrument());
        } else {
            exitPrice = placeSellOrder(openLeg.getInstrument(), openLeg.getQuantity());
        }

        openLeg.setExitPrice(exitPrice);
        openLeg.setExitTime(LocalDateTime.now());
        openLeg.setExitReason(reason);
        openLeg.setClosed(true);

        double legPnl = (exitPrice - openLeg.getEntryPrice()) * openLeg.getQuantity();
        openLeg.setPnl(legPnl);
        session.setCumulativePnL(session.getCumulativePnL() + legPnl);

        log.info("[{}] Exited leg={} type={} exit={} legPnL={} cumPnL={} reason={}",
                username, openLeg.getLegNumber(), openLeg.getOptionType(),
                exitPrice, legPnl, session.getCumulativePnL(), reason);
    }

    private void updateOpenPnL() {
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null) { session.setOpenPnL(0); return; }
        double ltp = getLtp(openLeg.getInstrument());
        if (ltp > 0) {
            session.setOpenPnL((ltp - openLeg.getEntryPrice()) * openLeg.getQuantity());
        }
    }

    private void checkExitConditions() {
        TradingConfig cfg    = session.getConfig();
        double totalPnl      = session.getTotalPnL();
        double trailingStep  = cfg.getTrailingProfit();
        boolean useTrailing  = trailingStep > 0;

        if (totalPnl <= -cfg.getStopLoss()) {
            log.info("[{}] Stop loss hit: {} <= -{}", username, totalPnl, cfg.getStopLoss());
            exitCurrentPosition("STOPLOSS");
            internalStopSession("Stop loss hit: " + totalPnl);
            return;
        }

        if (!useTrailing) {
            if (totalPnl >= cfg.getTargetProfit()) {
                log.info("[{}] Target profit reached: {} >= {}", username, totalPnl, cfg.getTargetProfit());
                exitCurrentPosition("TARGET");
                internalStopSession("Target profit reached: " + totalPnl);
            }
            return;
        }

        if (!session.isTrailingActive()) {
            if (totalPnl >= cfg.getTargetProfit()) {
                session.setTrailingActive(true);
                session.setTrailingHighWatermark(totalPnl);
                log.info("[{}] Trailing activated at pnl={}, step={}", username, totalPnl, trailingStep);
            }
        } else {
            if (totalPnl > session.getTrailingHighWatermark()) {
                log.info("[{}] Trailing watermark raised: {} → {}", username, session.getTrailingHighWatermark(), totalPnl);
                session.setTrailingHighWatermark(totalPnl);
            }
            double exitLevel = session.getTrailingHighWatermark() - trailingStep;
            if (totalPnl <= exitLevel) {
                log.info("[{}] Trailing stop triggered: pnl={} < watermark({}) - step({})",
                        username, totalPnl, session.getTrailingHighWatermark(), trailingStep);
                exitCurrentPosition("TRAILING_STOP");
                internalStopSession(String.format("Trailing stop: watermark=%.0f step=%.0f pnl=%.0f",
                        session.getTrailingHighWatermark(), trailingStep, totalPnl));
            }
        }
    }

    private boolean isPnLExitTriggered() {
        TradingConfig cfg    = session.getConfig();
        double totalPnl      = session.getTotalPnL();
        double trailingStep  = cfg.getTrailingProfit();
        boolean useTrailing  = trailingStep > 0;

        if (totalPnl <= -cfg.getStopLoss()) {
            internalStopSession("Stop loss hit after reversal: " + totalPnl);
            return true;
        }
        if (!useTrailing && totalPnl >= cfg.getTargetProfit()) {
            internalStopSession("Target profit reached after reversal: " + totalPnl);
            return true;
        }
        if (useTrailing && session.isTrailingActive()) {
            double exitLevel = session.getTrailingHighWatermark() - trailingStep;
            if (totalPnl <= exitLevel) {
                internalStopSession("Trailing stop after reversal: pnl=" + totalPnl);
                return true;
            }
        }
        if (useTrailing && !session.isTrailingActive() && totalPnl >= cfg.getTargetProfit()) {
            session.setTrailingActive(true);
            session.setTrailingHighWatermark(totalPnl);
        }
        return false;
    }

    private void internalStopSession(String reason) {
        session.setState(StrategyState.STOPPED);
        session.setEndTime(LocalDateTime.now());
        session.setStopReason(reason);
        log.info("[{}] Strategy stopped: {}", username, reason);
        sessionPersistence.saveForUser(username, session);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  STATUS
    // ═══════════════════════════════════════════════════════════════════════

    public AlgoStatusResponse buildStatusResponse() {
        if (session == null) return null;

        TradingConfig cfg   = session.getConfig();
        TradeEntry openLeg  = session.getCurrentOpenLeg();

        String status = switch (session.getState()) {
            case WAITING_FOR_CANDLES -> "WAITING";
            case IN_POSITION         -> "RUNNING";
            case STOPPED             -> resolveStopStatus(session.getStopReason());
            default                  -> "STOPPED";
        };

        double liveOpenPnL = 0;
        Double currentPrice = null;
        if (openLeg != null) {
            double ltp = getLtp(openLeg.getInstrument());
            if (ltp > 0) {
                currentPrice = ltp;
                liveOpenPnL  = (ltp - openLeg.getEntryPrice()) * openLeg.getQuantity();
            }
        }
        double liveTotalPnL = session.getTotalRealizedPnL() + liveOpenPnL;

        List<AlgoStatusResponse.HistoryRow> history = session.getTradeLegs().stream()
                .map(leg -> AlgoStatusResponse.HistoryRow.builder()
                        .legNumber(leg.getLegNumber())
                        .position(leg.getOptionType().name())
                        .symbol(leg.getInstrument())
                        .entryPrice(leg.getEntryPrice())
                        .exitPrice(leg.getExitPrice())
                        .pnlPoints(leg.isClosed() ? leg.getExitPrice() - leg.getEntryPrice() : 0)
                        .pnlAmount(leg.getPnl())
                        .entryTime(leg.getEntryTime())
                        .exitTime(leg.getExitTime())
                        .exitReason(leg.isClosed() ? leg.getExitReason() : "OPEN")
                        .build())
                .collect(Collectors.toList());

        return AlgoStatusResponse.builder()
                .active(session.getState() == StrategyState.WAITING_FOR_CANDLES
                        || session.getState() == StrategyState.IN_POSITION)
                .status(status)
                .startedBy(session.getStartedBy())
                .currentPosition(openLeg != null ? openLeg.getOptionType().name() : null)
                .currentEntryPrice(openLeg != null ? openLeg.getEntryPrice() : null)
                .currentOptionPrice(currentPrice)
                .currentLegUnrealizedPnL(liveOpenPnL)
                .cumulativePnL(session.getCumulativePnL())
                .totalPnL(liveTotalPnL)
                .currentSymbol(openLeg != null ? openLeg.getInstrument() : null)
                .futureSymbol(cfg.getFuturesInstrument())
                .reversalCount(session.getReversalCount())
                .maxReversals(cfg.getMaxReversals())
                .targetPnL(cfg.getTargetProfit())
                .stopLossPoints(cfg.getStopLoss())
                .trailingProfit(cfg.getTrailingProfit())
                .trailingActive(session.isTrailingActive())
                .trailingHighWatermark(session.getTrailingHighWatermark())
                .squareOffEod(cfg.isSquareOffEod())
                .paperTrade(cfg.getTradeMode() == TradeMode.PAPER)
                // Active config summary — shown in UI header so user knows what's running
                .entryStartTime(cfg.getStartCandleTime() != null ? cfg.getStartCandleTime().toString() : null)
                .strikeMode(cfg.getStrikeMode() != null ? cfg.getStrikeMode().name() : null)
                .lotQuantity(cfg.getLotQuantity())
                .totalQuantity(cfg.getTotalQuantity())
                .startTime(session.getStartTime())
                .endTime(session.getEndTime())
                .stopReason(session.getStopReason())
                .firstCandle(toCandleInfo(session.getFirstCandle()))
                .secondCandle(toCandleInfo(session.getSecondCandle()))
                .thirdCandle(toCandleInfo(session.getThirdCandle()))
                .history(history)
                .build();
    }

    private AlgoStatusResponse.CandleInfo toCandleInfo(Candle c) {
        if (c == null) return null;
        String t = c.getOpenTime() != null
                ? c.getOpenTime().toLocalTime().toString().substring(0, 5) : null;
        return AlgoStatusResponse.CandleInfo.builder().close(c.getClose()).time(t).build();
    }

    private String resolveStopStatus(String reason) {
        if (reason == null) return "STOPPED";
        String r = reason.toLowerCase();
        if (r.contains("trailing stop"))  return "TRAILING_STOP";
        if (r.contains("target"))         return "TARGET_HIT";
        if (r.contains("stop loss") || r.contains("sl")) return "SL_HIT";
        if (r.contains("max reversal"))   return "MAX_REVERSALS";
        if (r.contains("end of day") || r.contains("eod")) return "EOD";
        if (r.contains("recovered"))      return "RECOVERED";
        return "STOPPED";
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    /** Broadcast to the user-specific WebSocket topic. UI subscribes to /topic/trade-updates/{username}. */
    private void broadcastUpdate() {
        if (session == null) return;
        try {
            messagingTemplate.convertAndSend("/topic/trade-updates/" + username, buildStatusResponse());
            // Also broadcast to the shared topic for backward compat (single-tab UIs)
            messagingTemplate.convertAndSend("/topic/trade-updates", buildStatusResponse());
        } catch (Exception e) {
            log.warn("[{}] WebSocket broadcast failed: {}", username, e.getMessage());
        }
    }

    /** Update the user's Kite access token at runtime (e.g. after daily OAuth login). */
    public void updateKiteAccessToken(String newToken) {
        if (kiteConnect == null && platformUser.getKiteApiKey() != null) {
            kiteConnect = new KiteConnect(platformUser.getKiteApiKey());
        }
        if (kiteConnect != null) {
            kiteConnect.setAccessToken(newToken);
            log.info("[{}] KiteConnect access token updated", username);
        }
    }

    public boolean isActive() {
        return session != null
                && (session.getState() == StrategyState.WAITING_FOR_CANDLES
                 || session.getState() == StrategyState.IN_POSITION);
    }

    public boolean isTickerConnected() { return tickerConnected; }
}
