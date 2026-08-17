package com.algotrading.backend.engine;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.dto.AlgoStatusResponse;
import com.algotrading.backend.model.*;
import com.algotrading.backend.service.KiteErrorUtil;
import com.algotrading.backend.service.KiteInstrumentService;
import com.algotrading.backend.service.KiteTickerService;
import com.algotrading.backend.service.OptionInstrumentService;
import com.algotrading.backend.service.RiskExitEvaluator;
import com.algotrading.backend.service.SessionPersistenceService;
import com.algotrading.backend.service.TelegramService;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Strategy — NIFTY Breakout V2.
 *
 * Strikes are auto-selected once at session start: walking strikes ITM from ATM on both sides,
 * the first CE and first PE strike whose live premium is greater than {@value #MIN_PREMIUM} are
 * locked for the day (no MANUAL mode, no user-chosen strike). The first 5-min candle after the
 * configured start time (e.g. 09:20 → the 09:20-09:24:59 candle) becomes each leg's reference —
 * its High/Low are the breakout levels, reusing the same candle-bucketing machinery as the
 * original Breakout engine.
 *
 * Entry is NOT immediate-on-breakout like the original engine. Once a leg's price crosses its
 * reference High ("breakout triggered"), the engine waits for a retest: the entry fires only when
 * price pulls back to (referenceHigh - breakoutPoints). If instead price runs up to
 * (referenceHigh + maxChasePoints) without ever retesting, the run is judged a runaway and the
 * whole session is stopped ("stop the algo").
 *
 * Reversal: while in a CE/PE position, if price falls back below that leg's reference Low, the
 * leg is exited and the points lost on that leg are carried forward as an addition to the OTHER
 * leg's abandon threshold (maxChasePoints + carriedLossPoints) — so a reversal chase is judged a
 * runaway sooner than a fresh initial entry would be. Bounded by the existing maxReversals/
 * reversalCount mechanism for consistency with every other strategy in this codebase.
 *
 * BUY-only (the spec never mentions writing/selling options) — order placement and P&L are
 * simplified accordingly versus the original Breakout engine's BUY/SELL branching.
 */
public class BreakoutV2StrategyEngine implements TradingEngine {

    private static final Logger log = LoggerFactory.getLogger(BreakoutV2StrategyEngine.class);
    private static final DateTimeFormatter IST_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");
    private static final int CANDLE_MINUTES = 5;

    private static final double MIN_PREMIUM = 200.0;
    private static final int STRIKE_GAP = 50;
    private static final int STRIKE_SCAN_STEPS = 20; // up to 1000 pts ITM each side of ATM

    @Getter
    private final String username;
    @Getter
    private final StrategyKey strategyKey;
    private final PlatformUser platformUser;
    private final TelegramService telegramService;

    private final KiteConnect kiteConnect;
    private KiteTicker kiteTicker;
    private volatile boolean tickerConnected = false;

    @Getter
    private volatile TradeSession session;

    private final Map<String, Double> priceCache = new ConcurrentHashMap<>();
    private final Map<String, Candle> formingCandles = new ConcurrentHashMap<>();
    private final Map<String, Integer> legCandleCount = new ConcurrentHashMap<>();
    private final Map<Long, String> tokenToSymbol = new ConcurrentHashMap<>();
    private final Set<Long> subscribedTokens = ConcurrentHashMap.newKeySet();

    // Per-leg "price has crossed reference high, now watching for retest" flag, and points lost
    // on the leg most recently stopped-out via reversal — added to the NEXT leg's abandon
    // threshold. Both are transient engine state (not persisted): on crash-recovery restart they
    // reset to their defaults, meaning a leg mid-retest-wait must cross its reference high again
    // before entry re-arms — a conservative, safe default rather than an aggressive one.
    private final Map<String, Boolean> breakoutTriggered = new ConcurrentHashMap<>();
    private volatile double carriedLossPoints = 0;

    private final MarketDataCache globalCache;
    private final OptionInstrumentService optionInstrumentService;
    private final KiteInstrumentService kiteInstrumentService;
    private final KiteTickerService kiteTickerService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SessionPersistenceService sessionPersistence;
    private final RiskExitEvaluator riskExitEvaluator;

    public BreakoutV2StrategyEngine(TelegramService telegramService,
                                     PlatformUser platformUser,
                                     StrategyKey strategyKey,
                                     KiteConnect kiteConnect,
                                     MarketDataCache globalCache,
                                     OptionInstrumentService optionInstrumentService,
                                     KiteInstrumentService kiteInstrumentService,
                                     KiteTickerService kiteTickerService,
                                     SimpMessagingTemplate messagingTemplate,
                                     SessionPersistenceService sessionPersistence,
                                     RiskExitEvaluator riskExitEvaluator) {
        this.telegramService = telegramService;
        this.platformUser = platformUser;
        this.username = platformUser.getUsername();
        this.strategyKey = strategyKey;
        this.kiteConnect = kiteConnect;
        this.globalCache = globalCache;
        this.optionInstrumentService = optionInstrumentService;
        this.kiteInstrumentService = kiteInstrumentService;
        this.kiteTickerService = kiteTickerService;
        this.messagingTemplate = messagingTemplate;
        this.sessionPersistence = sessionPersistence;
        this.riskExitEvaluator = riskExitEvaluator;
    }

    // ── Ticker plumbing (identical pattern to BreakoutStrategyEngine) ─────────

    public void disconnectKiteTicker() {
        if (kiteTicker != null) {
            try {
                kiteTicker.disconnect();
            } catch (Exception ignore) {
            }
            kiteTicker = null;
        }
        tickerConnected = false;
        subscribedTokens.clear();
        tokenToSymbol.clear();
        log.info("[{}][BREAKOUT_V2] KiteTicker disconnected and cleared", username);
    }

    public void subscribeInstruments(Map<Long, String> instruments) {
        if (instruments == null || instruments.isEmpty()) return;
        tokenToSymbol.putAll(instruments);
        subscribedTokens.addAll(instruments.keySet());
        log.info("[{}][BREAKOUT_V2] Registered {} instruments: {}", username, instruments.size(), instruments.values());
    }

    public void processTick(MarketTick tick) {
        if (tick == null) return;
        priceCache.put(tick.getInstrument(), tick.getLastPrice());
        checkSLTargetOnTick(tick.getInstrument(), tick.getLastPrice());
        checkBreakoutOnTick(tick.getInstrument(), tick.getLastPrice());
        processTickForCandle(tick.getInstrument(), tick.getLastPrice());
    }

    /**
     * Breakout + limit-retest + runaway-abandon state machine. Runs on every tick once a leg's
     * reference candle (its 1st 5-min candle) has closed.
     */
    private synchronized void checkBreakoutOnTick(String instrument, double price) {
        if (session == null || session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;

        String ceInstr = session.getLockedCeInstrument();
        String peInstr = session.getLockedPeInstrument();
        if (!instrument.equals(ceInstr) && !instrument.equals(peInstr)) return;

        OptionType leg = instrument.equals(ceInstr) ? OptionType.CE : OptionType.PE;
        Integer count = legCandleCount.get(leg.name());
        if (count == null || count < 1) return; // reference candle not yet closed for this leg

        Candle ref = session.getLegReferenceCandles().get(leg.name());
        if (ref == null) return;

        if (session.getState() == StrategyState.IN_POSITION) {
            TradeEntry openLeg = session.getCurrentOpenLeg();
            if (openLeg != null && openLeg.getOptionType() == leg && price < ref.getLow()) {
                handleLowBreachExit(leg, ref, price);
            }
            return;
        }

        if (session.getState() != StrategyState.WAITING_FOR_CANDLES) return;

        // A leg that has already been traded once this session only re-enters via the explicit
        // reversal path in handleLowBreachExit — not via this generic per-tick evaluation.
        boolean alreadyTradedThisLeg = session.getTradeLegs().stream()
                .anyMatch(t -> t.getOptionType() == leg);
        if (alreadyTradedThisLeg) return;

        evaluateEntryForLeg(leg, ref, price, "INITIAL");
    }

    /**
     * Applies the breakout-then-retest-then-abandon rule for a single leg. Example from spec:
     * reference high=220, breakoutPoints=5 → once price first exceeds 220, entry fires when price
     * retraces to 215; if price instead runs up to 220+15=235 without ever retesting 215, the
     * session is stopped as a runaway.
     */
    private void evaluateEntryForLeg(OptionType leg, Candle ref, double price, String reasonPrefix) {
        TradingConfig cfg = session.getConfig();
        boolean triggered = Boolean.TRUE.equals(breakoutTriggered.get(leg.name()));
        if (!triggered) {
            if (price > ref.getHigh()) {
                breakoutTriggered.put(leg.name(), true);
                log.info("[{}][BREAKOUT_V2] {} broke out above high={} @ price={} — waiting for retest to {}",
                        username, leg, ref.getHigh(), price, ref.getHigh() - cfg.getBreakoutPoints());
            }
            return;
        }

        double entryTarget = ref.getHigh() - cfg.getBreakoutPoints();
        double abandonLevel = ref.getHigh() + cfg.getMaxChasePoints() + carriedLossPoints;

        if (price <= entryTarget) {
            enterBreakoutV2(leg, entryTarget, reasonPrefix);
            checkExitConditions();
            persistAndBroadcast();
        } else if (price >= abandonLevel) {
            String reason = String.format(
                    "Runaway: %s reached %.2f without retesting entry %.2f (abandon level %.2f)",
                    leg, price, entryTarget, abandonLevel);
            log.info("[{}][BREAKOUT_V2] {}", username, reason);
            internalStopSession(reason);
            persistAndBroadcast();
        }
    }

    /**
     * A leg breaches its reference Low while open: exit it, carry the points lost forward onto
     * the other leg's abandon threshold, and — if reversals remain available — start watching the
     * other leg for its own breakout/retest entry (immediately, if it's already broken out).
     */
    private void handleLowBreachExit(OptionType leg, Candle ref, double price) {
        log.info("[{}][BREAKOUT_V2] {} broke below reference low={} @ price={} — exiting",
                username, leg, ref.getLow(), price);
        exitCurrentPosition("LOW_BREACH");

        TradeEntry justClosed = session.getTradeLegs().get(session.getTradeLegs().size() - 1);
        double lostPoints = Math.max(0, justClosed.getEntryPrice() - justClosed.getExitPrice());
        carriedLossPoints += lostPoints;

        TradingConfig cfg = session.getConfig();
        boolean reversalAllowed = cfg.getMaxReversals() < 0 || session.getReversalCount() < cfg.getMaxReversals();
        if (!reversalAllowed) {
            internalStopSession("Max reversals reached after " + leg + " low-breach exit");
            persistAndBroadcast();
            return;
        }

        OptionType otherLeg = leg == OptionType.CE ? OptionType.PE : OptionType.CE;
        Candle otherRef = session.getLegReferenceCandles().get(otherLeg.name());
        session.setReversalCount(session.getReversalCount() + 1);
        session.setState(StrategyState.WAITING_FOR_CANDLES);

        if (otherRef != null) {
            String otherInstrument = otherLeg == OptionType.CE
                    ? session.getLockedCeInstrument() : session.getLockedPeInstrument();
            Double otherPrice = priceCache.get(otherInstrument);
            if (otherPrice != null && otherPrice > otherRef.getHigh()) {
                breakoutTriggered.put(otherLeg.name(), true);
                evaluateEntryForLeg(otherLeg, otherRef, otherPrice, "REVERSAL_" + session.getReversalCount());
            }
        }
        persistAndBroadcast();
    }

    private void processTickForCandle(String instrument, double price) {
        if (session == null || session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;

        String ceInstr = session.getLockedCeInstrument();
        String peInstr = session.getLockedPeInstrument();
        if (!instrument.equals(ceInstr) && !instrument.equals(peInstr)) return;

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));

        TradingConfig cfg = session.getConfig();
        LocalTime startTime = cfg != null ? cfg.getStartCandleTime() : null;
        if (startTime != null && now.toLocalTime().isBefore(startTime)) {
            return;
        }

        LocalDateTime bucketStart = startTime != null
                ? floorToBucket(now, startTime, session.getTradeDate())
                : floorToBucket(now);

        Candle forming = formingCandles.get(instrument);
        if (forming == null) {
            formingCandles.put(instrument, newCandle(instrument, price, bucketStart));
            return;
        }

        if (bucketStart.isAfter(forming.getOpenTime())) {
            forming.setCloseTime(forming.getOpenTime().plusMinutes(CANDLE_MINUTES).minusNanos(1));
            log.debug("[{}][BREAKOUT_V2] Candle closed: {} close={}", username, instrument, forming.getClose());
            onLegCandleClose(instrument, forming);
            formingCandles.put(instrument, newCandle(instrument, price, bucketStart));
        } else {
            if (price > forming.getHigh()) forming.setHigh(price);
            if (price < forming.getLow()) forming.setLow(price);
            forming.setClose(price);
        }
    }

    private LocalDateTime floorToBucket(LocalDateTime now, LocalTime startTime, LocalDate tradeDate) {
        LocalDateTime start = LocalDateTime.of(tradeDate, startTime);
        long minutesSinceStart = java.time.Duration.between(start, now).toMinutes();
        long bucketIndex = Math.floorDiv(minutesSinceStart, CANDLE_MINUTES);
        return start.plusMinutes(bucketIndex * CANDLE_MINUTES);
    }

    private LocalDateTime floorToBucket(LocalDateTime t) {
        int minute = t.getMinute();
        int flooredMinute = (minute / CANDLE_MINUTES) * CANDLE_MINUTES;
        return t.withMinute(flooredMinute).truncatedTo(ChronoUnit.MINUTES);
    }

    private Candle newCandle(String instrument, double price, LocalDateTime bucketStart) {
        return Candle.builder()
                .instrument(instrument)
                .openTime(bucketStart)
                .open(price).high(price).low(price).close(price)
                .build();
    }

    // ── Session lifecycle ──────────────────────────────────────────────────────

    public synchronized TradeSession startSession(TradingConfig config, Map<Long, String> instruments, String startedBy) {
        formingCandles.clear();
        legCandleCount.clear();
        breakoutTriggered.clear();
        carriedLossPoints = 0;
        config.setStrategyKey(strategyKey);
        config.setTradeDirection(TradeDirection.BUY); // V2 is buy-only

        session = TradeSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .tradeDate(LocalDate.now(ZoneId.of("Asia/Kolkata")))
                .config(config)
                .state(StrategyState.WAITING_FOR_CANDLES)
                .reversalCount(0)
                .currentLegNumber(0)
                .cumulativePnL(0.0)
                .openPnL(0.0)
                .startedBy(startedBy)
                .startTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .tradeLegs(new ArrayList<>())
                .build();

        if (instruments != null) {
            tokenToSymbol.putAll(instruments);
            subscribedTokens.addAll(instruments.keySet());
        }

        if (!selectStrikesByPremium()) {
            sessionPersistence.saveForUser(EngineKeys.of(username, strategyKey), session);
            return session;
        }

        sessionPersistence.saveForUser(EngineKeys.of(username, strategyKey), session);

        String startMsg = String.format(
                "🚀 <b>NIFTY Breakout V2 Started</b>\nUser: <code>%s</code>\nTrade Mode: <code>%s</code>\n" +
                        "CE: <code>%s</code>  PE: <code>%s</code>\nBreakout pts: <code>%.1f</code>  Max chase: <code>%.1f</code>\n" +
                        "Lots: <code>%d</code>  Qty: <code>%d</code>\nSL: <code>%s</code>  Target: <code>%.2f</code>\nTrailing: <code>%s</code>",
                username, config.getTradeMode(), session.getLockedCeInstrument(), session.getLockedPeInstrument(),
                config.getBreakoutPoints(), config.getMaxChasePoints(),
                config.getLotQuantity(), config.getTotalQuantity(),
                config.getStopLoss() > 0 ? String.format("%.2f", config.getStopLoss()) : "OFF",
                config.getTargetProfit(),
                config.getTrailingProfit() > 0 ? String.format("%.2f", config.getTrailingProfit()) : "OFF");
        sendTelegram(startMsg);

        log.info("[{}][BREAKOUT_V2] Session {} started (mode={})", username, session.getSessionId(), config.getTradeMode());
        return session;
    }

    /**
     * Auto-selects CE/PE strikes for the day: walks strikes ITM from ATM on both sides in
     * {@value #STRIKE_GAP}-pt steps, batch-fetches live premiums, and locks the FIRST strike per
     * side whose premium is greater than {@value #MIN_PREMIUM}. Requires a live Kite connection
     * (both for the spot reference price and the batch quote call) — there's no historical/paper
     * substitute for real premium data.
     */
    private boolean selectStrikesByPremium() {
        String futuresInstrument = session.getConfig().getFuturesInstrument();
        double spot = getLtp(futuresInstrument);
        if (spot <= 0) spot = globalCache.getLastPrice(futuresInstrument);
        if (spot <= 0) {
            internalStopSession("No reference price for " + futuresInstrument
                    + " — ensure Kite ticker is subscribed and connected before starting NIFTY Breakout V2");
            return false;
        }

        LocalDate expiry = optionInstrumentService.getAutoExpiryDate();
        int atm = optionInstrumentService.computeAtmStrike(spot, STRIKE_GAP);

        LinkedHashMap<Integer, KiteInstrument> ceCandidates = new LinkedHashMap<>();
        LinkedHashMap<Integer, KiteInstrument> peCandidates = new LinkedHashMap<>();
        for (int i = 0; i <= STRIKE_SCAN_STEPS; i++) {
            int ceStrike = atm - i * STRIKE_GAP; // walk ITM for CE (lower strikes = higher premium)
            int peStrike = atm + i * STRIKE_GAP; // walk ITM for PE (higher strikes = higher premium)
            kiteInstrumentService.findOption("NIFTY", expiry, ceStrike, "CE").ifPresent(inst -> ceCandidates.put(ceStrike, inst));
            kiteInstrumentService.findOption("NIFTY", expiry, peStrike, "PE").ifPresent(inst -> peCandidates.put(peStrike, inst));
        }

        if (ceCandidates.isEmpty() || peCandidates.isEmpty()) {
            internalStopSession("Could not resolve CE/PE strikes near ATM " + atm + " for expiry " + expiry
                    + " — instrument cache may not be loaded (connect Kite first)");
            return false;
        }

        List<String> quoteKeys = new ArrayList<>();
        ceCandidates.values().forEach(inst -> quoteKeys.add("NFO:" + inst.getTradingsymbol()));
        peCandidates.values().forEach(inst -> quoteKeys.add("NFO:" + inst.getTradingsymbol()));

        Map<String, Quote> quotes;
        try {
            quotes = kiteConnect.getQuote(quoteKeys.toArray(new String[0]));
        } catch (Exception | KiteException e) {
            internalStopSession("Failed to fetch option premiums for strike selection: " + e.getMessage());
            return false;
        }

        Map.Entry<Integer, KiteInstrument> ce = pickFirstAbovePremium(ceCandidates, quotes);
        Map.Entry<Integer, KiteInstrument> pe = pickFirstAbovePremium(peCandidates, quotes);

        if (ce == null || pe == null) {
            internalStopSession("No CE/PE strike with premium > " + MIN_PREMIUM
                    + " found within " + STRIKE_SCAN_STEPS + " strikes of ATM " + atm);
            return false;
        }

        session.setLockedCeStrike(ce.getKey());
        session.setLockedPeStrike(pe.getKey());
        session.setLockedCeInstrument(ce.getValue().getTradingsymbol());
        session.setLockedPeInstrument(pe.getValue().getTradingsymbol());
        session.setLockedExpiry(expiry);
        session.setLockedExpiryLabel("Weekly (" + expiry.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)) + ")");

        Map<Long, String> toSub = new HashMap<>();
        toSub.put(ce.getValue().getInstrumentToken(), ce.getValue().getTradingsymbol());
        toSub.put(pe.getValue().getInstrumentToken(), pe.getValue().getTradingsymbol());
        subscribeInstruments(toSub);
        kiteTickerService.subscribe(toSub);

        log.info("[{}][BREAKOUT_V2] Strike selection: spot={} atm={} → CE {}@{} PE {}@{} (premium>{})",
                username, spot, atm, ce.getKey(), ce.getValue().getTradingsymbol(),
                pe.getKey(), pe.getValue().getTradingsymbol(), MIN_PREMIUM);
        return true;
    }

    private Map.Entry<Integer, KiteInstrument> pickFirstAbovePremium(Map<Integer, KiteInstrument> candidates,
                                                                       Map<String, Quote> quotes) {
        for (Map.Entry<Integer, KiteInstrument> e : candidates.entrySet()) {
            Quote q = quotes.get("NFO:" + e.getValue().getTradingsymbol());
            if (q != null && q.lastPrice > MIN_PREMIUM) return e;
        }
        return null;
    }

    public synchronized TradeSession stopSession() {
        if (session == null) throw new IllegalStateException("No active session for user: " + username);
        if (session.getState() == StrategyState.IN_POSITION) {
            exitCurrentPosition("MANUAL_STOP");
        }
        internalStopSession("Manual stop");
        sessionPersistence.clearForUser(EngineKeys.of(username, strategyKey));
        log.info("[{}][BREAKOUT_V2] Session stopped manually", username);
        return session;
    }

    public synchronized void restoreSession(TradeSession restored) {
        this.session = restored;
        if (restored.getLockedCeInstrument() != null) {
            kiteInstrumentService.findBySymbol(restored.getLockedCeInstrument())
                    .ifPresent(i -> tokenToSymbol.put(i.getInstrumentToken(), i.getTradingsymbol()));
        }
        if (restored.getLockedPeInstrument() != null) {
            kiteInstrumentService.findBySymbol(restored.getLockedPeInstrument())
                    .ifPresent(i -> tokenToSymbol.put(i.getInstrumentToken(), i.getTradingsymbol()));
        }
        subscribedTokens.addAll(tokenToSymbol.keySet());
        log.info("[{}][BREAKOUT_V2] Session {} restored (state={})", username, restored.getSessionId(), restored.getState());
    }

    public synchronized void squareOffEod() {
        if (session == null) return;
        if (session.getState() == StrategyState.STOPPED || session.getState() == StrategyState.IDLE) return;
        TradingConfig cfg = session.getConfig();
        if (!cfg.isSquareOffEod()) {
            log.info("[{}][BREAKOUT_V2] EOD reached but squareOffEod=false — no auto exit", username);
            return;
        }
        if (session.getState() == StrategyState.IN_POSITION) {
            exitCurrentPosition("EOD");
        }
        internalStopSession("End of day square-off at 15:29");
        sessionPersistence.clearForUser(EngineKeys.of(username, strategyKey));
        broadcastUpdate();
    }

    public synchronized void updateParams(double targetPrice, double stopLoss, boolean stopLossEnabled, double trailingProfit) {
        if (session == null) throw new IllegalStateException("No active session");
        TradingConfig cfg = session.getConfig();
        cfg.setTargetProfit(targetPrice);
        cfg.setStopLoss(stopLossEnabled ? stopLoss : 0);
        cfg.setTrailingProfit(trailingProfit);
        log.info("[{}][BREAKOUT_V2] Params updated: target={}, sl={} ({}), trailing={}",
                username, targetPrice, stopLoss, stopLossEnabled ? "ON" : "OFF", trailingProfit);
    }

    // ── Candle-close handling ──────────────────────────────────────────────────

    private synchronized void onLegCandleClose(String instrument, Candle candle) {
        if (session == null || session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;

        OptionType leg = instrument.equals(session.getLockedCeInstrument()) ? OptionType.CE : OptionType.PE;
        updateOpenPnL();

        int count = legCandleCount.merge(leg.name(), 1, Integer::sum);
        updateCandleDisplaySlots(count, candle);

        if (count == 1) {
            // The FIRST 5-min candle starting exactly at the configured start time (e.g. 09:20 →
            // 09:20-09:24:59) is the breakout reference window — its High/Low drive the
            // breakout/retest/abandon and low-breach-reversal state machine from here on.
            session.getLegReferenceCandles().put(leg.name(), candle);
            log.info("[{}][BREAKOUT_V2] {} 1st candle (reference window {}–{}): high={} low={}",
                    username, leg, candle.getOpenTime(), candle.getCloseTime(), candle.getHigh(), candle.getLow());
            persistAndBroadcast();
            return;
        }

        log.debug("[{}][BREAKOUT_V2] {} candle #{} closed: high={} low={} close={}",
                username, leg, count, candle.getHigh(), candle.getLow(), candle.getClose());
        checkExitConditions();
        persistAndBroadcast();
    }

    private void updateCandleDisplaySlots(int count, Candle candle) {
        if (count == 1) {
            session.setFirstCandle(candle);
        } else if (count == 2) {
            session.setSecondCandle(candle);
        } else {
            session.setThirdCandle(candle);
        }
        session.setLastClosedCandle(candle);
    }

    private void persistAndBroadcast() {
        sessionPersistence.saveForUser(EngineKeys.of(username, strategyKey), session);
        broadcastUpdate();
    }

    // ── Order placement (BUY-only — no SELL/write mode for this strategy) ─────

    double getLtp(String instrument) {
        if (instrument == null) return 0;
        Double cached = priceCache.get(instrument);
        if (cached != null && cached > 0) return cached;

        if (session != null && session.getConfig() != null
                && session.getConfig().getTradeMode() == TradeMode.LIVE && kiteConnect != null) {
            try {
                String key = session.getConfig().exchangeSegment() + ":" + instrument;
                Map<String, LTPQuote> map = kiteConnect.getLTP(new String[]{key});
                LTPQuote q = map != null ? map.get(key) : null;
                if (q != null && q.lastPrice > 0) {
                    priceCache.put(instrument, q.lastPrice);
                    return q.lastPrice;
                }
            } catch (Exception | KiteException e) {
                log.warn("[{}][BREAKOUT_V2] LTP REST failed for {}: {}", username, instrument, e.getMessage());
            }
        }

        double globalLtp = globalCache.getLastPrice(instrument);
        if (globalLtp > 0) {
            priceCache.put(instrument, globalLtp);
            return globalLtp;
        }
        return 0;
    }

    private static final int MAX_ORDER_ATTEMPTS = 3;

    private double placeBuyOrder(String instrument, int qty) {
        if (session.getConfig().getTradeMode() == TradeMode.PAPER) {
            double ltp = getLtp(instrument);
            log.info("[{}][BREAKOUT_V2][PAPER] BUY {} x{} @ {}", username, instrument, qty, ltp);
            return ltp;
        }
        if (kiteConnect == null) {
            throw new IllegalStateException("[" + username + "] KiteConnect not initialized for live trading");
        }
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                double ltp = getLtp(instrument);
                double limitPrice = roundToTick(ltp * 1.02);
                OrderParams p = new OrderParams();
                p.tradingsymbol = instrument;
                p.exchange = session.getConfig().exchangeSegment();
                p.transactionType = Constants.TRANSACTION_TYPE_BUY;
                p.orderType = Constants.ORDER_TYPE_LIMIT;
                p.price = limitPrice;
                p.quantity = qty;
                p.product = Constants.PRODUCT_MIS;
                p.validity = Constants.VALIDITY_DAY;
                OrderResponse order = kiteConnect.placeOrder(p, Constants.VARIETY_REGULAR);
                double fillPrice = pollActualFillPrice(order.orderId, instrument, ltp);
                log.info("[{}][BREAKOUT_V2][LIVE] BUY {} actual fill price: {}", username, instrument, fillPrice);
                return fillPrice;
            } catch (Exception | KiteException e) {
                if (KiteErrorUtil.isSessionExpired(e)) {
                    throw new IllegalStateException(KiteErrorUtil.sessionExpiredMessage());
                }
                if (KiteErrorUtil.shouldRetry(e, attempt, MAX_ORDER_ATTEMPTS, "[" + username + "][BREAKOUT_V2] BUY " + instrument, log)) {
                    continue;
                }
                throw new RuntimeException("[" + username + "] BUY order failed: " + e.getMessage(), e);
            }
        }
    }

    private double placeSellOrder(String instrument, int qty) {
        if (session.getConfig().getTradeMode() == TradeMode.PAPER) {
            double ltp = getLtp(instrument);
            log.info("[{}][BREAKOUT_V2][PAPER] SELL {} x{} @ {}", username, instrument, qty, ltp);
            return ltp;
        }
        if (kiteConnect == null) {
            throw new IllegalStateException("[" + username + "] KiteConnect not initialized for live trading");
        }
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                double ltp = getLtp(instrument);
                double limitPrice = roundToTick(ltp * 0.98);
                OrderParams p = new OrderParams();
                p.tradingsymbol = instrument;
                p.exchange = session.getConfig().exchangeSegment();
                p.transactionType = Constants.TRANSACTION_TYPE_SELL;
                p.orderType = Constants.ORDER_TYPE_LIMIT;
                p.price = limitPrice;
                p.quantity = qty;
                p.product = Constants.PRODUCT_MIS;
                p.validity = Constants.VALIDITY_DAY;
                OrderResponse order = kiteConnect.placeOrder(p, Constants.VARIETY_REGULAR);
                double fillPrice = pollActualFillPrice(order.orderId, instrument, ltp);
                log.info("[{}][BREAKOUT_V2][LIVE] SELL {} actual fill price: {}", username, instrument, fillPrice);
                return fillPrice;
            } catch (Exception | KiteException e) {
                if (KiteErrorUtil.isSessionExpired(e)) {
                    throw new IllegalStateException(KiteErrorUtil.sessionExpiredMessage());
                }
                if (KiteErrorUtil.shouldRetry(e, attempt, MAX_ORDER_ATTEMPTS, "[" + username + "][BREAKOUT_V2] SELL " + instrument, log)) {
                    continue;
                }
                throw new RuntimeException("[" + username + "] SELL order failed: " + e.getMessage(), e);
            }
        }
    }

    private double pollActualFillPrice(String orderId, String instrument, double fallback) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<Order> history = kiteConnect.getOrderHistory(orderId);
                if (history != null && !history.isEmpty()) {
                    Order latest = history.get(history.size() - 1);
                    if ("COMPLETE".equalsIgnoreCase(latest.status)
                            && latest.averagePrice != null && !latest.averagePrice.isEmpty()) {
                        double avgPrice = Double.parseDouble(latest.averagePrice);
                        if (avgPrice > 0) {
                            priceCache.put(instrument, avgPrice);
                            return avgPrice;
                        }
                    }
                    if ("REJECTED".equalsIgnoreCase(latest.status) || "CANCELLED".equalsIgnoreCase(latest.status)) {
                        break;
                    }
                }
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception | KiteException e) {
                break;
            }
        }
        try {
            String key = session.getConfig().exchangeSegment() + ":" + instrument;
            Map<String, LTPQuote> map = kiteConnect.getLTP(new String[]{key});
            LTPQuote q = map != null ? map.get(key) : null;
            if (q != null && q.lastPrice > 0) {
                priceCache.put(instrument, q.lastPrice);
                return q.lastPrice;
            }
        } catch (Exception | KiteException e) {
            log.warn("[{}][BREAKOUT_V2] Fresh LTP fetch failed for {}: {}", username, instrument, e.getMessage());
        }
        return fallback;
    }

    private double roundToTick(double price) {
        return Math.round(price / 0.05) * 0.05;
    }

    private boolean hasOpenPosition(String instrument) {
        if (session.getConfig().getTradeMode() == TradeMode.PAPER) return true;
        if (kiteConnect == null) return false;
        try {
            Map<String, List<Position>> positions = kiteConnect.getPositions();
            List<Position> net = positions.get("net");
            if (net == null) return false;
            return net.stream().anyMatch(p -> instrument.equalsIgnoreCase(p.tradingSymbol) && p.netQuantity != 0);
        } catch (Exception | KiteException e) {
            log.warn("[{}][BREAKOUT_V2] getPositions failed: {}", username, e.getMessage());
            return false;
        }
    }

    private double computeLegPnL(double entryPrice, double currentPrice, int qty) {
        return (currentPrice - entryPrice) * qty;
    }

    private void enterBreakoutV2(OptionType leg, double entryTargetPrice, String reason) {
        TradingConfig cfg = session.getConfig();
        String instrument = leg == OptionType.CE ? session.getLockedCeInstrument() : session.getLockedPeInstrument();
        int strikePrice = leg == OptionType.CE ? session.getLockedCeStrike() : session.getLockedPeStrike();

        double ltp = getLtp(instrument);
        if (ltp <= 0) {
            log.error("[{}][BREAKOUT_V2] No tick data for {} — aborting entry [{}]", username, instrument, reason);
            internalStopSession("No tick data for " + instrument + " — ensure Kite ticker is subscribed and connected");
            return;
        }

        double entryPrice = placeBuyOrder(instrument, cfg.getTotalQuantity());

        TradeEntry entry = TradeEntry.builder()
                .legNumber(session.getCurrentLegNumber() + 1)
                .optionType(leg)
                .instrument(instrument)
                .strikePrice(strikePrice)
                .expiryType(cfg.getExpiryType())
                .quantity(cfg.getTotalQuantity())
                .entryPrice(entryPrice)
                .entryTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .closed(false)
                .build();

        session.setCurrentLegNumber(entry.getLegNumber());
        session.getTradeLegs().add(entry);
        session.setState(StrategyState.IN_POSITION);

        if (isAdmin()) {
            String msg = String.format(
                    "✅ <b>Breakout V2 Position Entered</b>\nType: <code>BUY %s</code>\nInstrument: <code>%s</code>\n" +
                            "Target Entry: <code>%.2f</code>\nActual Entry: <code>%.2f</code>\nQty: <code>%d</code>\nReason: <code>%s</code>",
                    leg, instrument, entryTargetPrice, entryPrice, entry.getQuantity(), reason);
            sendTelegram(msg);
        }

        log.info("[{}][BREAKOUT_V2] Entered leg={} type={} instrument={} targetEntry={} actualEntry={} reason={}",
                username, entry.getLegNumber(), leg, instrument, entryTargetPrice, entryPrice, reason);
    }

    private void exitCurrentPosition(String reason) {
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null || openLeg.isClosed()) return;

        double exitPrice;
        if (!hasOpenPosition(openLeg.getInstrument())) {
            log.warn("[{}][BREAKOUT_V2] No open position for {} in broker (manually squared off?)", username, openLeg.getInstrument());
            exitPrice = getLtp(openLeg.getInstrument());
        } else {
            exitPrice = placeSellOrder(openLeg.getInstrument(), openLeg.getQuantity());
        }

        openLeg.setExitPrice(exitPrice);
        openLeg.setExitTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        openLeg.setExitReason(reason);
        openLeg.setClosed(true);

        double legPnl = computeLegPnL(openLeg.getEntryPrice(), exitPrice, openLeg.getQuantity());
        openLeg.setPnl(legPnl);
        session.setCumulativePnL(session.getCumulativePnL() + legPnl);
        session.setOpenPnL(0);

        log.info("[{}][BREAKOUT_V2] Exited leg={} type={} exit={} legPnL={} cumPnL={} reason={}",
                username, openLeg.getLegNumber(), openLeg.getOptionType(), exitPrice, legPnl, session.getCumulativePnL(), reason);

        if (isAdmin()) {
            String msg = String.format(
                    "🔚 <b>Breakout V2 Position Exited</b>\nType: <code>%s</code>\nInstrument: <code>%s</code>\nEntry: <code>%.2f</code>\nExit: <code>%.2f</code>\nP/L: <code>%.2f</code>\nReason: <code>%s</code>",
                    openLeg.getOptionType(), openLeg.getInstrument(), openLeg.getEntryPrice(), exitPrice, legPnl, reason);
            sendTelegram(msg);
        }
    }

    private void updateOpenPnL() {
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null) {
            session.setOpenPnL(0);
            return;
        }
        double ltp = getLtp(openLeg.getInstrument());
        if (ltp > 0) {
            session.setOpenPnL(computeLegPnL(openLeg.getEntryPrice(), ltp, openLeg.getQuantity()));
        }
    }

    synchronized void checkSLTargetOnTick(String instrument, double ltp) {
        if (session == null || session.getState() != StrategyState.IN_POSITION) return;
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null || !instrument.equals(openLeg.getInstrument())) return;

        double liveLegPnL = computeLegPnL(openLeg.getEntryPrice(), ltp, openLeg.getQuantity());
        RiskExitEvaluator.ExitDecision decision = riskExitEvaluator.evaluate(session, liveLegPnL);
        if (decision == RiskExitEvaluator.ExitDecision.NONE) return;

        double totalPnL = session.getTotalRealizedPnL() + liveLegPnL;
        String reason = switch (decision) {
            case STOPLOSS -> "STOPLOSS";
            case TARGET -> "TARGET";
            case TRAILING_STOP -> "TRAILING_STOP";
            default -> "STOPLOSS";
        };
        log.info("[{}][BREAKOUT_V2] {} hit on tick: totalPnL={} | ltp={}", username, reason, totalPnL, ltp);
        exitCurrentPosition(reason);
        internalStopSession(reason + " hit: " + totalPnL);
        persistAndBroadcast();
    }

    private void checkExitConditions() {
        if (session.getState() != StrategyState.IN_POSITION) return;
        RiskExitEvaluator.ExitDecision decision = riskExitEvaluator.evaluate(session, session.getOpenPnL());
        if (decision == RiskExitEvaluator.ExitDecision.NONE) return;

        double totalPnl = session.getTotalPnL();
        String reason = switch (decision) {
            case STOPLOSS -> "STOPLOSS";
            case TARGET -> "TARGET";
            case TRAILING_STOP -> "TRAILING_STOP";
            default -> "STOPLOSS";
        };
        log.info("[{}][BREAKOUT_V2] {} hit: {}", username, reason, totalPnl);
        exitCurrentPosition(reason);
        internalStopSession(reason + " hit: " + totalPnl);
    }

    private void internalStopSession(String reason) {
        session.setState(StrategyState.STOPPED);
        session.setEndTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        session.setStopReason(reason);
        log.info("[{}][BREAKOUT_V2] Strategy stopped: {}", username, reason);
        sessionPersistence.saveForUser(EngineKeys.of(username, strategyKey), session);
        sendTelegramSummary(reason);
    }

    private void sendTelegramSummary(String stopReason) {
        try {
            String endT = session.getEndTime() != null
                    ? session.getEndTime().format(IST_FMT) + " IST"
                    : LocalDateTime.now(ZoneId.of("Asia/Kolkata")).format(IST_FMT) + " IST";
            double totalPnl = session.getCumulativePnL();
            String simple = String.format(
                    "🔔 <b>Breakout V2 Strategy Stopped</b>\nUser: <code>%s</code>\nStop Reason: <code>%s</code>\nTime: <code>%s</code>\n\n💰 <b>P&amp;L: %.0f</b>",
                    username, stopReason, endT, totalPnl);
            sendTelegram(simple);
        } catch (Exception e) {
            log.warn("[{}][BREAKOUT_V2] Failed to send Telegram summary: {}", username, e.getMessage());
        }
    }

    private boolean isAdmin() {
        return platformUser != null && "GNANESH".equalsIgnoreCase(platformUser.getRole());
    }

    private void sendTelegram(String msg) {
        telegramService.sendStrategyMessage("📊 <b>" + strategyKey.displayName() + "</b>\n" + msg);
    }

    // ── Status / lifecycle plumbing ────────────────────────────────────────────

    public AlgoStatusResponse buildStatusResponse() {
        if (session == null) return null;
        TradingConfig cfg = session.getConfig();
        TradeEntry openLeg = session.getCurrentOpenLeg();

        String status = switch (session.getState()) {
            case WAITING_FOR_CANDLES -> "WAITING";
            case IN_POSITION -> "RUNNING";
            case STOPPED -> resolveStopStatus(session.getStopReason());
            default -> "STOPPED";
        };

        double liveOpenPnL = 0;
        Double currentPrice = null;
        if (openLeg != null) {
            double ltp = getLtp(openLeg.getInstrument());
            if (ltp > 0) {
                currentPrice = ltp;
                liveOpenPnL = computeLegPnL(openLeg.getEntryPrice(), ltp, openLeg.getQuantity());
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
                .strategyKey(strategyKey.name())
                .tradeDirection("BUY")
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
                .lockedCeInstrument(session.getLockedCeInstrument())
                .lockedPeInstrument(session.getLockedPeInstrument())
                .lockedExpiryLabel(session.getLockedExpiryLabel())
                .lockedCeStrike(session.getLockedCeStrike())
                .lockedPeStrike(session.getLockedPeStrike())
                .ceReferenceCandle(toCandleInfo(session.getLegReferenceCandles().get(OptionType.CE.name())))
                .peReferenceCandle(toCandleInfo(session.getLegReferenceCandles().get(OptionType.PE.name())))
                .build();
    }

    private AlgoStatusResponse.CandleInfo toCandleInfo(Candle c) {
        if (c == null) return null;
        String t = c.getOpenTime() != null
                ? c.getOpenTime().toLocalTime().toString().substring(0, 5) : null;
        return AlgoStatusResponse.CandleInfo.builder()
                .close(c.getClose())
                .time(t)
                .high(c.getHigh())
                .low(c.getLow())
                .build();
    }

    private String resolveStopStatus(String reason) {
        if (reason == null) return "STOPPED";
        String r = reason.toLowerCase();
        if (r.contains("trailing_stop") || r.contains("trailing stop")) return "TRAILING_STOP";
        if (r.contains("target")) return "TARGET_HIT";
        if (r.contains("stoploss") || r.contains("stop loss")) return "SL_HIT";
        if (r.contains("end of day") || r.contains("eod")) return "EOD";
        if (r.contains("runaway")) return "RUNAWAY_STOPPED";
        return "STOPPED";
    }

    private void broadcastUpdate() {
        if (session == null) return;
        try {
            messagingTemplate.convertAndSend("/topic/trade-updates/" + username, buildStatusResponse());
            messagingTemplate.convertAndSend("/topic/trade-updates", buildStatusResponse());
        } catch (Exception e) {
            log.warn("[{}][BREAKOUT_V2] WebSocket broadcast failed: {}", username, e.getMessage());
        }
    }

    public void updateKiteAccessToken(String newToken) {
        // Global KiteConnect bean is updated by KiteAuthService — nothing to do here
    }

    public boolean isActive() {
        return session != null
                && (session.getState() == StrategyState.WAITING_FOR_CANDLES
                || session.getState() == StrategyState.IN_POSITION);
    }

    public boolean isTickerConnected() {
        return tickerConnected;
    }
}
