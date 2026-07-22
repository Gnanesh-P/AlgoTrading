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
 * Strategy 3 — NIFTY 5-Min Breakout.
 *
 * ATM CE+PE are locked at session start (no MANUAL mode). Each leg's option-premium candle
 * series is tracked independently on 5-minute boundaries. The 2nd candle's High (BUY) / Low
 * (SELL) becomes that leg's breakout reference — the reference is always the candle's High/Low,
 * never its Close. From the moment that reference is set (i.e. as soon as the 2nd candle
 * closes) every subsequent tick is checked intrabar: the instant a leg's live price crosses its
 * own reference High (BUY) or Low (SELL), the trade is taken immediately — entry/reversal does
 * NOT wait for the current 5-min candle to close. (BUY: price breaks above reference high → buy;
 * SELL: price breaks below reference low → sell/write, same leg — unlike the scalping engine,
 * breakout does NOT flip to the opposite leg for direction). Only one leg is ever open at a
 * time; once in position, an intrabar breakout on the OTHER leg is a reversal candidate, gated
 * by reversalEnabled/maxReversals (maxReversals == -1 means unlimited). Target/SL/Trailing reuse
 * RiskExitEvaluator exactly as the scalping engine does.
 */
public class BreakoutStrategyEngine implements TradingEngine {

    private static final Logger log = LoggerFactory.getLogger(BreakoutStrategyEngine.class);
    private static final DateTimeFormatter IST_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");
    private static final int CANDLE_MINUTES = 5;

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

    private final MarketDataCache globalCache;
    private final OptionInstrumentService optionInstrumentService;
    private final KiteInstrumentService kiteInstrumentService;
    private final KiteTickerService kiteTickerService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SessionPersistenceService sessionPersistence;
    private final RiskExitEvaluator riskExitEvaluator;

    public BreakoutStrategyEngine(TelegramService telegramService,
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

    // ── Ticker plumbing (identical pattern to ScalpingStrategyEngine) ─────────

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
        log.info("[{}][BREAKOUT] KiteTicker disconnected and cleared", username);
    }

    public void subscribeInstruments(Map<Long, String> instruments) {
        if (instruments == null || instruments.isEmpty()) return;
        tokenToSymbol.putAll(instruments);
        subscribedTokens.addAll(instruments.keySet());
        log.info("[{}][BREAKOUT] Registered {} instruments: {}", username, instruments.size(), instruments.values());
    }

    public void processTick(MarketTick tick) {
        if (tick == null) return;
        priceCache.put(tick.getInstrument(), tick.getLastPrice());
        checkSLTargetOnTick(tick.getInstrument(), tick.getLastPrice());
        checkBreakoutOnTick(tick.getInstrument(), tick.getLastPrice());
        processTickForCandle(tick.getInstrument(), tick.getLastPrice());
    }

    /**
     * Intrabar breakout/reversal check — runs on every tick once a leg's reference (2nd
     * candle's High/Low) has been established. Entry/reversal fires the instant price crosses
     * the reference, instead of waiting for the current forming candle to close.
     */
    private synchronized void checkBreakoutOnTick(String instrument, double price) {
        if (session == null || session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;

        String ceInstr = session.getLockedCeInstrument();
        String peInstr = session.getLockedPeInstrument();
        if (!instrument.equals(ceInstr) && !instrument.equals(peInstr)) return;

        OptionType leg = instrument.equals(ceInstr) ? OptionType.CE : OptionType.PE;
        Integer count = legCandleCount.get(leg.name());
        if (count == null || count < 1) return; // reference (1st candle) not yet closed for this leg

        Candle ref = session.getLegReferenceCandles().get(leg.name());
        if (ref == null) return;

        TradeDirection direction = session.getConfig().getTradeDirection();
        boolean brokeOut = direction == TradeDirection.SELL
                ? price < ref.getLow()
                : price > ref.getHigh();
        if (!brokeOut) return;

        if (session.getState() == StrategyState.WAITING_FOR_CANDLES) {
            log.info("[{}][BREAKOUT] {} intrabar breakout ({}) @ {} → entering", username, leg,
                    direction == TradeDirection.SELL ? "price<ref.low(" + ref.getLow() + ")"
                                                      : "price>ref.high(" + ref.getHigh() + ")",
                    price);
            enterBreakout(leg, "INITIAL");
            checkExitConditions();
            persistAndBroadcast();
        } else if (session.getState() == StrategyState.IN_POSITION) {
            TradeEntry openLeg = session.getCurrentOpenLeg();
            if (openLeg != null && openLeg.getOptionType() != leg) {
                TradingConfig cfg = session.getConfig();
                boolean reversalAllowed = cfg.isReversalEnabled()
                        && (cfg.getMaxReversals() < 0 || session.getReversalCount() < cfg.getMaxReversals());
                if (reversalAllowed) {
                    log.info("[{}][BREAKOUT] Intrabar reversal {} → {} @ {}", username, openLeg.getOptionType(), leg, price);
                    exitCurrentPosition("REVERSAL");
                    session.setReversalCount(session.getReversalCount() + 1);
                    enterBreakout(leg, "REVERSAL_" + session.getReversalCount());
                    checkExitConditions();
                    persistAndBroadcast();
                }
            }
        }
    }

    private void processTickForCandle(String instrument, double price) {
        if (session == null || session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;

        String ceInstr = session.getLockedCeInstrument();
        String peInstr = session.getLockedPeInstrument();
        if (!instrument.equals(ceInstr) && !instrument.equals(peInstr)) return;

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));

        // Gate candle formation on the user-selected start time. E.g. start=13:00 means the
        // first 5-min candle (the breakout REFERENCE window) is exactly [13:00:00, 13:04:59] —
        // ticks before start time are ignored entirely so "candle 1" always begins exactly at the
        // selected start time, regardless of whether that time falls on a wall-clock :00/:05 mark.
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
            log.debug("[{}][BREAKOUT] Candle closed: {} close={}", username, instrument, forming.getClose());
            onLegCandleClose(instrument, forming);
            formingCandles.put(instrument, newCandle(instrument, price, bucketStart));
        } else {
            if (price > forming.getHigh()) forming.setHigh(price);
            if (price < forming.getLow()) forming.setLow(price);
            forming.setClose(price);
        }
    }

    /**
     * Anchors 5-min candle boundaries to the user's configured start time (not absolute wall-clock
     * :00/:05 marks). e.g. start=13:00 → buckets are [13:00,13:05), [13:05,13:10), ...
     * start=13:02 → buckets are [13:02,13:07), [13:07,13:12), ... — always start-time-relative.
     */
    private LocalDateTime floorToBucket(LocalDateTime now, LocalTime startTime, LocalDate tradeDate) {
        LocalDateTime start = LocalDateTime.of(tradeDate, startTime);
        long minutesSinceStart = java.time.Duration.between(start, now).toMinutes();
        long bucketIndex = Math.floorDiv(minutesSinceStart, CANDLE_MINUTES);
        return start.plusMinutes(bucketIndex * CANDLE_MINUTES);
    }

    /** Fallback wall-clock flooring — only used if startCandleTime is somehow unset. */
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
        config.setStrategyKey(strategyKey);

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

        // AUTO_ATM: strike computed from reference (futures/index) price, real tradingsymbol
        //           resolved from Kite cache by (expiry, strike, type) lookup — see
        //           lockAndSubscribeAtmOptions(). A reference price is required only in this mode.
        // MANUAL:   CE/PE tradingsymbols already chosen by the user (via option chain dropdown)
        //           and passed in `config`/`instruments` — resolveAndLockInstruments() locks them
        //           directly (no reference price needed), and AlgoController has already
        //           subscribed their tokens on the ticker.
        double refPrice = 0;
        if (config.getStrikeMode() == StrikeMode.AUTO_ATM) {
            refPrice = getLtp(config.getFuturesInstrument());
            if (refPrice <= 0) refPrice = globalCache.getLastPrice(config.getFuturesInstrument());
            if (refPrice <= 0) {
                internalStopSession("No reference price for " + config.getFuturesInstrument()
                        + " — ensure Kite ticker is subscribed and connected before starting the breakout strategy");
                String key = EngineKeys.of(username, strategyKey);
                sessionPersistence.saveForUser(key, session);
                return session;
            }
        }

        optionInstrumentService.resolveAndLockInstruments(config, session, refPrice);
        if (config.getStrikeMode() == StrikeMode.AUTO_ATM) {
            lockAndSubscribeAtmOptions();
        }

        String persistenceKey = EngineKeys.of(username, strategyKey);
        sessionPersistence.saveForUser(persistenceKey, session);

        String startMsg = String.format(
                "🚀 <b>Breakout Strategy Started</b>\nUser: <code>%s</code>\nDirection: <code>%s</code>\n" +
                        "Trade Mode: <code>%s</code>\nFutures: <code>%s</code>\nExpiry: <code>%s</code>\n" +
                        "Reversal: <code>%s</code> (max=%s)\nLots: <code>%d</code>  Qty: <code>%d</code>\n" +
                        "SL: <code>%s</code>  Target: <code>%.2f</code>\nTrailing: <code>%s</code>",
                username, config.getTradeDirection(), config.getTradeMode(), config.getFuturesInstrument(),
                config.getExpiryType(), config.isReversalEnabled() ? "ON" : "OFF",
                config.getMaxReversals() < 0 ? "unlimited" : String.valueOf(config.getMaxReversals()),
                config.getLotQuantity(), config.getTotalQuantity(),
                config.getStopLoss() > 0 ? String.format("%.2f", config.getStopLoss()) : "OFF",
                config.getTargetProfit(),
                config.getTrailingProfit() > 0 ? String.format("%.2f", config.getTrailingProfit()) : "OFF");
        telegramService.sendStrategyMessage(startMsg);

        log.info("[{}][BREAKOUT] Session {} started (direction={}, mode={})",
                username, session.getSessionId(), config.getTradeDirection(), config.getTradeMode());
        return session;
    }

    /**
     * NIFTY vs BANKNIFTY index prefix for Kite instrument lookups — mirrors
     * ScalpingStrategyEngine's indexPrefix(), driven by the authoritative strategyKey
     * (via config.isBankNifty()) rather than sniffing symbol text.
     */
    private String indexPrefix() {
        return session.getConfig().isBankNifty() ? "BANKNIFTY" : "NIFTY";
    }

    private void lockAndSubscribeAtmOptions() {
        LocalDate expiry = session.getLockedExpiry();
        int ceStrike = session.getLockedCeStrike();
        int peStrike = session.getLockedPeStrike();
        String index = indexPrefix();

        if (expiry == null) {
            log.error("[{}][BREAKOUT] lockedExpiry is null — cannot subscribe options", username);
            return;
        }

        Map<Long, String> toSub = new HashMap<>();
        kiteInstrumentService.findOption(index, expiry, ceStrike, "CE").ifPresentOrElse(i -> {
            toSub.put(i.getInstrumentToken(), i.getTradingsymbol());
            session.setLockedCeInstrument(i.getTradingsymbol());
            log.info("[{}][BREAKOUT] CE: {} token={}", username, i.getTradingsymbol(), i.getInstrumentToken());
        }, () -> log.warn("[{}][BREAKOUT] CE not in cache: expiry={} strike={}", username, expiry, ceStrike));

        kiteInstrumentService.findOption(index, expiry, peStrike, "PE").ifPresentOrElse(i -> {
            toSub.put(i.getInstrumentToken(), i.getTradingsymbol());
            session.setLockedPeInstrument(i.getTradingsymbol());
            log.info("[{}][BREAKOUT] PE: {} token={}", username, i.getTradingsymbol(), i.getInstrumentToken());
        }, () -> log.warn("[{}][BREAKOUT] PE not in cache: expiry={} strike={}", username, expiry, peStrike));

        if (!toSub.isEmpty()) {
            subscribeInstruments(toSub);
            kiteTickerService.subscribe(toSub);
            log.info("[{}][BREAKOUT] subscribed {} instruments", username, toSub.size());
        } else {
            log.error("[{}][BREAKOUT] no instruments found in cache for expiry={} CE={} PE={} — Kite may not be connected",
                    username, expiry, ceStrike, peStrike);
        }
    }

    public synchronized TradeSession stopSession() {
        if (session == null) throw new IllegalStateException("No active session for user: " + username);
        if (session.getState() == StrategyState.IN_POSITION) {
            exitCurrentPosition("MANUAL_STOP");
        }
        internalStopSession("Manual stop");
        sessionPersistence.clearForUser(EngineKeys.of(username, strategyKey));
        log.info("[{}][BREAKOUT] Session stopped manually", username);
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
        log.info("[{}][BREAKOUT] Session {} restored (state={})", username, restored.getSessionId(), restored.getState());
    }

    public synchronized void squareOffEod() {
        if (session == null) return;
        if (session.getState() == StrategyState.STOPPED || session.getState() == StrategyState.IDLE) return;
        TradingConfig cfg = session.getConfig();
        if (!cfg.isSquareOffEod()) {
            log.info("[{}][BREAKOUT] EOD reached but squareOffEod=false — no auto exit", username);
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
        log.info("[{}][BREAKOUT] Params updated: target={}, sl={} ({}), trailing={}",
                username, targetPrice, stopLoss, stopLossEnabled ? "ON" : "OFF", trailingProfit);
    }

    // ── Candle-close breakout logic ────────────────────────────────────────────

    private synchronized void onLegCandleClose(String instrument, Candle candle) {
        if (session == null || session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;

        OptionType leg = instrument.equals(session.getLockedCeInstrument()) ? OptionType.CE : OptionType.PE;
        updateOpenPnL();

        int count = legCandleCount.merge(leg.name(), 1, Integer::sum);
        updateCandleDisplaySlots(count, candle);

        if (count == 1) {
            // The FIRST 5-min candle starting exactly at the configured start time is the
            // breakout reference window (e.g. start=13:00 → reference window = 13:00–13:04:59).
            // Every subsequent candle's High (BUY) / Low (SELL) is checked against THIS window's
            // High/Low — not the 2nd candle. checkBreakoutOnTick() starts evaluating ticks against
            // this reference immediately (gate is count>=1), so the very next forming candle
            // (13:05–13:09:59 onward) can trigger entry the instant price breaks out intrabar.
            session.getLegReferenceCandles().put(leg.name(), candle);
            log.info("[{}][BREAKOUT] {} 1st candle (reference window {}–{}): high={} low={}",
                    username, leg, candle.getOpenTime(), candle.getCloseTime(), candle.getHigh(), candle.getLow());
            persistAndBroadcast();
            return;
        }

        // From the 2nd candle onward, actual breakout entry/reversal detection happens intrabar
        // via checkBreakoutOnTick() on every tick (as soon as price crosses the leg's reference
        // High/Low), not here at candle close — that way a breakout that happens mid-candle
        // (e.g. at 13:07:40) is acted on immediately instead of waiting for the candle to close
        // at 13:10. This handler now just logs the closed candle and re-checks Target/SL/Trailing.
        log.debug("[{}][BREAKOUT] {} candle #{} closed: high={} low={} close={}",
                username, leg, count, candle.getHigh(), candle.getLow(), candle.getClose());
        checkExitConditions();
        persistAndBroadcast();
    }

    /**
     * Mirrors the closed candle into TradeSession's generic first/second/third candle slots so
     * the (single, strategy-agnostic) UI candle display shows live data for the breakout strategy
     * too — previously these were never populated for breakout sessions, so the candle boxes and
     * (via a separate fix) the start-time chip always appeared blank even though ticks/candles
     * were being processed correctly under the hood. CE and PE close on the same wall-clock
     * boundaries, so whichever leg's candle closes is shown; not leg-specific, just a live
     * "is data flowing" signal — legReferenceCandles remains the authoritative per-leg reference.
     */
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

    // ── Order placement (identical mechanics to ScalpingStrategyEngine) ───────

    double getLtp(String instrument) {
        if (instrument == null) return 0;
        Double cached = priceCache.get(instrument);
        if (cached != null && cached > 0) return cached;

        if (session != null && session.getConfig() != null
                && session.getConfig().getTradeMode() == TradeMode.LIVE && kiteConnect != null) {
            try {
                Map<String, LTPQuote> map = kiteConnect.getLTP(new String[]{"NFO:" + instrument});
                LTPQuote q = map != null ? map.get("NFO:" + instrument) : null;
                if (q != null && q.lastPrice > 0) {
                    priceCache.put(instrument, q.lastPrice);
                    return q.lastPrice;
                }
            } catch (Exception | KiteException e) {
                log.warn("[{}][BREAKOUT] LTP REST failed for {}: {}", username, instrument, e.getMessage());
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
            log.info("[{}][BREAKOUT][PAPER] BUY {} x{} @ {}", username, instrument, qty, ltp);
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
                p.exchange = Constants.EXCHANGE_NFO;
                p.transactionType = Constants.TRANSACTION_TYPE_BUY;
                p.orderType = Constants.ORDER_TYPE_LIMIT;
                p.price = limitPrice;
                p.quantity = qty;
                p.product = Constants.PRODUCT_MIS;
                p.validity = Constants.VALIDITY_DAY;
                OrderResponse order = kiteConnect.placeOrder(p, Constants.VARIETY_REGULAR);
                double fillPrice = pollActualFillPrice(order.orderId, instrument, ltp);
                log.info("[{}][BREAKOUT][LIVE] BUY {} actual fill price: {}", username, instrument, fillPrice);
                return fillPrice;
            } catch (Exception | KiteException e) {
                if (KiteErrorUtil.isSessionExpired(e)) {
                    throw new IllegalStateException(KiteErrorUtil.sessionExpiredMessage());
                }
                if (KiteErrorUtil.shouldRetry(e, attempt, MAX_ORDER_ATTEMPTS, "[" + username + "][BREAKOUT] BUY " + instrument, log)) {
                    continue;
                }
                throw new RuntimeException("[" + username + "] BUY order failed: " + e.getMessage(), e);
            }
        }
    }

    private double placeSellOrder(String instrument, int qty) {
        if (session.getConfig().getTradeMode() == TradeMode.PAPER) {
            double ltp = getLtp(instrument);
            log.info("[{}][BREAKOUT][PAPER] SELL {} x{} @ {}", username, instrument, qty, ltp);
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
                p.exchange = Constants.EXCHANGE_NFO;
                p.transactionType = Constants.TRANSACTION_TYPE_SELL;
                p.orderType = Constants.ORDER_TYPE_LIMIT;
                p.price = limitPrice;
                p.quantity = qty;
                p.product = Constants.PRODUCT_MIS;
                p.validity = Constants.VALIDITY_DAY;
                OrderResponse order = kiteConnect.placeOrder(p, Constants.VARIETY_REGULAR);
                double fillPrice = pollActualFillPrice(order.orderId, instrument, ltp);
                log.info("[{}][BREAKOUT][LIVE] SELL {} actual fill price: {}", username, instrument, fillPrice);
                return fillPrice;
            } catch (Exception | KiteException e) {
                if (KiteErrorUtil.isSessionExpired(e)) {
                    throw new IllegalStateException(KiteErrorUtil.sessionExpiredMessage());
                }
                if (KiteErrorUtil.shouldRetry(e, attempt, MAX_ORDER_ATTEMPTS, "[" + username + "][BREAKOUT] SELL " + instrument, log)) {
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
            Map<String, LTPQuote> map = kiteConnect.getLTP(new String[]{"NFO:" + instrument});
            LTPQuote q = map != null ? map.get("NFO:" + instrument) : null;
            if (q != null && q.lastPrice > 0) {
                priceCache.put(instrument, q.lastPrice);
                return q.lastPrice;
            }
        } catch (Exception | KiteException e) {
            log.warn("[{}][BREAKOUT] Fresh LTP fetch failed for {}: {}", username, instrument, e.getMessage());
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
            log.warn("[{}][BREAKOUT] getPositions failed: {}", username, e.getMessage());
            return false;
        }
    }

    private double computeLegPnL(double entryPrice, double currentPrice, int qty) {
        TradeDirection direction = session.getConfig().getTradeDirection();
        return direction == TradeDirection.SELL
                ? (entryPrice - currentPrice) * qty
                : (currentPrice - entryPrice) * qty;
    }

    private void enterBreakout(OptionType leg, String reason) {
        TradingConfig cfg = session.getConfig();
        String instrument = leg == OptionType.CE ? session.getLockedCeInstrument() : session.getLockedPeInstrument();
        int strikePrice = leg == OptionType.CE ? session.getLockedCeStrike() : session.getLockedPeStrike();

        double ltp = getLtp(instrument);
        if (ltp <= 0) {
            log.error("[{}][BREAKOUT] No tick data for {} — aborting entry [{}]", username, instrument, reason);
            internalStopSession("No tick data for " + instrument + " — ensure Kite ticker is subscribed and connected");
            return;
        }

        boolean isSell = cfg.getTradeDirection() == TradeDirection.SELL;
        double entryPrice = isSell ? placeSellOrder(instrument, cfg.getTotalQuantity())
                                    : placeBuyOrder(instrument, cfg.getTotalQuantity());

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
                    "✅ <b>Breakout Position Entered</b>\nType: <code>%s %s</code>\nInstrument: <code>%s</code>\nPrice: <code>%.2f</code>\nQty: <code>%d</code>\nReason: <code>%s</code>",
                    isSell ? "SELL" : "BUY", leg, instrument, entryPrice, entry.getQuantity(), reason);
            telegramService.sendStrategyMessage(msg);
        }

        log.info("[{}][BREAKOUT] Entered leg={} type={} instrument={} entry={} reason={}",
                username, entry.getLegNumber(), leg, instrument, entryPrice, reason);
    }

    private void exitCurrentPosition(String reason) {
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null || openLeg.isClosed()) return;

        boolean isSell = session.getConfig().getTradeDirection() == TradeDirection.SELL;
        double exitPrice;
        if (!hasOpenPosition(openLeg.getInstrument())) {
            log.warn("[{}][BREAKOUT] No open position for {} in broker (manually squared off?)", username, openLeg.getInstrument());
            exitPrice = getLtp(openLeg.getInstrument());
        } else {
            exitPrice = isSell ? placeBuyOrder(openLeg.getInstrument(), openLeg.getQuantity())
                                : placeSellOrder(openLeg.getInstrument(), openLeg.getQuantity());
        }

        openLeg.setExitPrice(exitPrice);
        openLeg.setExitTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        openLeg.setExitReason(reason);
        openLeg.setClosed(true);

        double legPnl = computeLegPnL(openLeg.getEntryPrice(), exitPrice, openLeg.getQuantity());
        openLeg.setPnl(legPnl);
        session.setCumulativePnL(session.getCumulativePnL() + legPnl);
        session.setOpenPnL(0);

        log.info("[{}][BREAKOUT] Exited leg={} type={} exit={} legPnL={} cumPnL={} reason={}",
                username, openLeg.getLegNumber(), openLeg.getOptionType(), exitPrice, legPnl, session.getCumulativePnL(), reason);

        if (isAdmin()) {
            String msg = String.format(
                    "🔚 <b>Breakout Position Exited</b>\nType: <code>%s</code>\nInstrument: <code>%s</code>\nEntry: <code>%.2f</code>\nExit: <code>%.2f</code>\nP/L: <code>%.2f</code>\nReason: <code>%s</code>",
                    openLeg.getOptionType(), openLeg.getInstrument(), openLeg.getEntryPrice(), exitPrice, legPnl, reason);
            telegramService.sendStrategyMessage(msg);
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
        log.info("[{}][BREAKOUT] {} hit on tick: totalPnL={} | ltp={}", username, reason, totalPnL, ltp);
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
        log.info("[{}][BREAKOUT] {} hit: {}", username, reason, totalPnl);
        exitCurrentPosition(reason);
        internalStopSession(reason + " hit: " + totalPnl);
    }

    private void internalStopSession(String reason) {
        session.setState(StrategyState.STOPPED);
        session.setEndTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        session.setStopReason(reason);
        log.info("[{}][BREAKOUT] Strategy stopped: {}", username, reason);
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
                    "🔔 <b>Breakout Strategy Stopped</b>\nUser: <code>%s</code>\nStop Reason: <code>%s</code>\nTime: <code>%s</code>\n\n💰 <b>P&amp;L: %.0f</b>",
                    username, stopReason, endT, totalPnl);
            telegramService.sendStrategyMessage(simple);
        } catch (Exception e) {
            log.warn("[{}][BREAKOUT] Failed to send Telegram summary: {}", username, e.getMessage());
        }
    }

    private boolean isAdmin() {
        return platformUser != null && "GNANESH".equalsIgnoreCase(platformUser.getRole());
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
                .tradeDirection(cfg.getTradeDirection() != null ? cfg.getTradeDirection().name() : "BUY")
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
        if (r.contains("trailing stop")) return "TRAILING_STOP";
        if (r.contains("target")) return "TARGET_HIT";
        if (r.contains("stop loss") || r.contains("sl")) return "SL_HIT";
        if (r.contains("end of day") || r.contains("eod")) return "EOD";
        if (r.contains("recovered")) return "RECOVERED";
        return "STOPPED";
    }

    private void broadcastUpdate() {
        if (session == null) return;
        try {
            messagingTemplate.convertAndSend("/topic/trade-updates/" + username, buildStatusResponse());
            messagingTemplate.convertAndSend("/topic/trade-updates", buildStatusResponse());
        } catch (Exception e) {
            log.warn("[{}][BREAKOUT] WebSocket broadcast failed: {}", username, e.getMessage());
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
