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

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Runs the 1-minute scalping strategy (3rd-candle entry, price-action reversal) for a single
 * (user, strategy) pair. Reused unchanged for both NIFTY_SCALP and BANKNIFTY_SCALP — the only
 * difference between the two is config values (instrument, lot size, strike step), not code paths.
 *
 * Direction (BUY/SELL): the price-action SIGNAL (bullish→CE, bearish→PE) is always computed the
 * same way and tracked as session.currentSignalType. In BUY mode the traded leg matches the
 * signal. In SELL mode the traded leg is the OPPOSITE of the signal (we write/short the other
 * leg) — see enterInitialPosition/handleInPosition. Reversal transitions always follow the
 * signal, not the traded leg, so the state machine is identical in both directions.
 */
public class ScalpingStrategyEngine implements TradingEngine {

    private static final Logger log = LoggerFactory.getLogger(ScalpingStrategyEngine.class);
    private static final DateTimeFormatter IST_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");

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
    private final Map<Long, String> tokenToSymbol = new ConcurrentHashMap<>();
    private final Set<Long> subscribedTokens = ConcurrentHashMap.newKeySet();

    private final MarketDataCache globalCache;
    private final OptionInstrumentService optionInstrumentService;
    private final KiteInstrumentService kiteInstrumentService;
    private final KiteTickerService kiteTickerService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SessionPersistenceService sessionPersistence;
    private final RiskExitEvaluator riskExitEvaluator;

    public ScalpingStrategyEngine(TelegramService telegramService,
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

    public void connectKiteTicker() {
        if (platformUser.getKiteApiKey() == null || platformUser.getKiteAccessToken() == null) {
            log.warn("[{}] Cannot connect KiteTicker: credentials missing", username);
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
        log.info("[{}] KiteTicker disconnected and cleared", username);
    }

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

    private void onKiteTicksArrived(ArrayList<Tick> ticks) {
        if (ticks == null) return;
        for (Tick t : ticks) {
            String symbol = tokenToSymbol.get(t.getInstrumentToken());
            if (symbol == null) continue;
            double ltp = t.getLastTradedPrice();
            priceCache.put(symbol, ltp);
            checkSLTargetOnTick(symbol, ltp);
            processTickForCandle(symbol, ltp);
        }
    }

    public void processTick(MarketTick tick) {
        if (tick == null) return;
        priceCache.put(tick.getInstrument(), tick.getLastPrice());
        checkSLTargetOnTick(tick.getInstrument(), tick.getLastPrice());
        processTickForCandle(tick.getInstrument(), tick.getLastPrice());
    }

    private void processTickForCandle(String instrument, double price) {
        if (session == null || session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime candleMinute = now.truncatedTo(ChronoUnit.MINUTES);

        Candle forming = formingCandles.get(instrument);

        if (forming == null) {
            formingCandles.put(instrument, newCandle(instrument, price, candleMinute));
            return;
        }

        if (candleMinute.isAfter(forming.getOpenTime())) {
            forming.setCloseTime(forming.getOpenTime().plusMinutes(1).minusNanos(1));
            log.debug("[{}] Candle closed: {} close={}", username, instrument, forming.getClose());

            TradingConfig cfg = session.getConfig();
            if (cfg != null && instrument.equals(cfg.getFuturesInstrument())) {
                onCandleClose(forming);
            }

            formingCandles.put(instrument, newCandle(instrument, price, candleMinute));
        } else {
            if (price > forming.getHigh()) forming.setHigh(price);
            if (price < forming.getLow()) forming.setLow(price);
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

    double getLtp(String instrument) {
        // 1. Per-engine tick cache (populated by per-user KiteTicker for LIVE, or routed ticks for PAPER)
        Double cached = priceCache.get(instrument);
        if (cached != null && cached > 0) return cached;

        if (session != null && session.getConfig().getTradeMode() == TradeMode.LIVE
                && kiteConnect != null) {
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
            log.info("[{}][PAPER] BUY {} x{} @ {}", username, instrument, qty, ltp);
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
                double limitPrice = roundToTick(ltp * 1.02);  // 1% above LTP — fills immediately, satisfies Zerodha market protection
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
                log.info("[{}][LIVE] BUY {} x{} @ limit={} (ltp={}) → orderId={}", username, instrument, qty, limitPrice, ltp, order.orderId);
                // Poll actual fill price so trade logs match what Zerodha executed
                double fillPrice = pollActualFillPrice(order.orderId, instrument, ltp);
                log.info("[{}][LIVE] BUY {} actual fill price: {} (cached ltp was: {})", username, instrument, fillPrice, ltp);
                return fillPrice;
            } catch (Exception | KiteException e) {
                if (KiteErrorUtil.isSessionExpired(e)) {
                    throw new IllegalStateException(KiteErrorUtil.sessionExpiredMessage());
                }
                if (KiteErrorUtil.shouldRetry(e, attempt, MAX_ORDER_ATTEMPTS, "[" + username + "] BUY " + instrument, log)) {
                    continue;
                }
                throw new RuntimeException("[" + username + "] BUY order failed: " + e.getMessage(), e);
            }
        }
    }

    private double placeSellOrder(String instrument, int qty) {
        if (session.getConfig().getTradeMode() == TradeMode.PAPER) {
            double ltp = getLtp(instrument);
            log.info("[{}][PAPER] SELL {} x{} @ {}", username, instrument, qty, ltp);
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
                double limitPrice = roundToTick(ltp * 0.98);  // 1% below LTP — fills immediately, satisfies Zerodha market protection
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
                log.info("[{}][LIVE] SELL {} x{} @ limit={} (ltp={}) → orderId={}", username, instrument, qty, limitPrice, ltp, order.orderId);
                // Poll actual fill price so trade logs and P&L match what Zerodha executed
                double fillPrice = pollActualFillPrice(order.orderId, instrument, ltp);
                log.info("[{}][LIVE] SELL {} actual fill price: {} (cached ltp was: {})", username, instrument, fillPrice, ltp);
                return fillPrice;
            } catch (Exception | KiteException e) {
                if (KiteErrorUtil.isSessionExpired(e)) {
                    throw new IllegalStateException(KiteErrorUtil.sessionExpiredMessage());
                }
                if (KiteErrorUtil.shouldRetry(e, attempt, MAX_ORDER_ATTEMPTS, "[" + username + "] SELL " + instrument, log)) {
                    continue;
                }
                throw new RuntimeException("[" + username + "] SELL order failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Polls Zerodha order history until the order is COMPLETE and returns the actual average fill price.
     * Falls back to a fresh REST LTP call if the order doesn't fill within the timeout.
     * This ensures entryPrice/exitPrice in trade logs always matches what Zerodha actually executed,
     * not the potentially stale priceCache value at the moment the order was placed.
     */
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
                            log.info("[{}] Order {} filled @ avgPrice={}", username, orderId, avgPrice);
                            priceCache.put(instrument, avgPrice);
                            return avgPrice;
                        }
                    }
                    if ("REJECTED".equalsIgnoreCase(latest.status) || "CANCELLED".equalsIgnoreCase(latest.status)) {
                        log.warn("[{}] Order {} status={} — using fallback price", username, orderId, latest.status);
                        break;
                    }
                }
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception | KiteException e) {
                log.warn("[{}] Order history poll failed for {}: {}", username, orderId, e.getMessage());
                break;
            }
        }
        // Fallback: fresh LTP from Kite REST (much more accurate than stale priceCache)
        try {
            Map<String, LTPQuote> map = kiteConnect.getLTP(new String[]{"NFO:" + instrument});
            LTPQuote q = map != null ? map.get("NFO:" + instrument) : null;
            if (q != null && q.lastPrice > 0) {
                log.info("[{}] Using fresh LTP {} for {} as fill price (order poll timed out)", username, q.lastPrice, instrument);
                priceCache.put(instrument, q.lastPrice);
                return q.lastPrice;
            }
        } catch (Exception | KiteException e) {
            log.warn("[{}] Fresh LTP fetch failed for {}: {}", username, instrument, e.getMessage());
        }
        log.warn("[{}] Could not determine fill price for {} — using stale ltp {}", username, instrument, fallback);
        return fallback;
    }

    // Rounds price to nearest 0.05 (NFO options tick size)
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
            // != 0 (not > 0) so short (written) legs — which carry negative netQuantity — are
            // also detected as an open position, not just long legs.
            return net.stream().anyMatch(p ->
                    instrument.equalsIgnoreCase(p.tradingSymbol) && p.netQuantity != 0);
        } catch (Exception | KiteException e) {
            log.warn("[{}] getPositions failed: {}", username, e.getMessage());
            return false;
        }
    }

    /** P&L for a leg, direction-aware: BUY = (exit-entry)*qty, SELL (short) = (entry-exit)*qty. */
    private double computeLegPnL(double entryPrice, double currentPrice, int qty) {
        TradeDirection direction = session.getConfig().getTradeDirection();
        return direction == TradeDirection.SELL
                ? (entryPrice - currentPrice) * qty
                : (currentPrice - entryPrice) * qty;
    }

    private String indexPrefix() {
        String futures = session.getConfig().getFuturesInstrument();
        return (futures != null && futures.toUpperCase().contains("BANKNIFTY")) ? "BANKNIFTY" : "NIFTY";
    }

    public synchronized TradeSession startSession(TradingConfig config,
                                                  Map<Long, String> instruments,
                                                  String startedBy) {
        formingCandles.clear();

        String strikeDetail = config.getStrikeMode() == StrikeMode.AUTO_ATM
                ? "AUTO ATM"
                : String.format("MANUAL | CE: %s | PE: %s",
                        config.getCeInstrument() != null ? config.getCeInstrument() : "—",
                        config.getPeInstrument() != null ? config.getPeInstrument() : "—");

        String startMsg = String.format(
                "🚀 <b>Strategy Started</b>\n" +
                        "User: <code>%s</code>\n" +
                        "Strategy: <code>%s</code>\n" +
                        "Direction: <code>%s</code>\n" +
                        "Trade Mode: <code>%s</code>\n" +
                        "Strike Mode: <code>%s</code>\n" +
                        "Futures: <code>%s</code>\n" +
                        "Expiry: <code>%s</code>\n" +
                        "Start Time: <code>%s</code>\n" +
                        "Lots: <code>%d</code>  Qty: <code>%d</code>\n" +
                        "SL: <code>%s</code>  Target: <code>%.2f</code>\n" +
                        "Trailing: <code>%s</code>",
                username,
                strategyKey,
                config.getTradeDirection(),
                config.getTradeMode(),
                strikeDetail,
                config.getFuturesInstrument(),
                config.getExpiryType(),
                config.getStartCandleTime() != null ? config.getStartCandleTime().toString() : "—",
                config.getLotQuantity(),
                config.getTotalQuantity(),
                config.getStopLoss() > 0 ? String.format("%.2f", config.getStopLoss()) : "OFF",
                config.getTargetProfit(),
                config.getTrailingProfit() > 0 ? String.format("%.2f", config.getTrailingProfit()) : "OFF"
        );
        telegramService.sendStrategyMessage(startMsg);

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

        // For MANUAL mode: instruments are known upfront — lock them immediately
        // so the UI can show CE/PE strike from the moment the strategy starts.
        // For AUTO_ATM: instruments depend on first-candle close price — resolved in handleWaitingForCandles().
        if (config.getStrikeMode() == StrikeMode.MANUAL) {
            optionInstrumentService.resolveAndLockInstruments(config, session, 0);
        }

        String persistenceKey = EngineKeys.of(username, strategyKey);
        sessionPersistence.saveForUser(persistenceKey, session);
        log.info("[{}][{}] Session {} started (mode={}, direction={}, lots={}, qty={})",
                username, strategyKey, session.getSessionId(), config.getTradeMode(),
                config.getTradeDirection(), config.getLotQuantity(), config.getTotalQuantity());
        return session;
    }

    public synchronized TradeSession stopSession() {
        if (session == null) throw new IllegalStateException("No active session for user: " + username);
        if (session.getState() == StrategyState.IN_POSITION) {
            exitCurrentPosition("MANUAL_STOP");
        }
        internalStopSession("Manual stop");
        sessionPersistence.clearForUser(EngineKeys.of(username, strategyKey));
        log.info("[{}] Session stopped manually", username);
        return session;
    }

    public synchronized void restoreSession(TradeSession restored) {
        this.session = restored;
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

    public synchronized void squareOffEod() {
        if (session == null) return;
        if (session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;
        TradingConfig cfg = session.getConfig();
        if (!cfg.isSquareOffEod()) {
            log.info("[{}] EOD reached but squareOffEod=false — no auto exit", username);
            return;
        }
        log.info("[{}] EOD 15:29 IST square-off triggered", username);
        if (session.getState() == StrategyState.IN_POSITION) {
            exitCurrentPosition("EOD");
        }
        internalStopSession("End of day square-off at 15:29");
        sessionPersistence.clearForUser(EngineKeys.of(username, strategyKey));
        broadcastUpdate();
    }

    public synchronized void updateParams(double targetPrice, double stopLoss,
                                          boolean stopLossEnabled, double trailingProfit) {
        if (session == null) throw new IllegalStateException("No active session");
        TradingConfig cfg = session.getConfig();
        cfg.setTargetProfit(targetPrice);
        cfg.setStopLoss(stopLossEnabled ? stopLoss : 0);
        cfg.setTrailingProfit(trailingProfit);
        log.info("[{}] Params updated: target={}, sl={} ({}), trailing={}",
                username, targetPrice, stopLoss, stopLossEnabled ? "ON" : "OFF", trailingProfit);
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
        log.info("[{}] {} hit on tick: totalPnL={} | ltp={}", username, reason, totalPnL, ltp);
        exitCurrentPosition(reason);
        internalStopSession(reason + " hit: " + totalPnL);
        sessionPersistence.saveForUser(EngineKeys.of(username, strategyKey), session);
        broadcastUpdate();
    }

    public synchronized void onCandleClose(Candle candle) {
        if (session == null || session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) return;

        TradingConfig cfg = session.getConfig();
        if (!candle.getInstrument().equals(cfg.getFuturesInstrument())) return;

        log.debug("[{}] Candle: time={}, close={}", username, candle.getOpenTime(), candle.getClose());

        LocalTime candleTime = candle.getOpenTime().toLocalTime();
        LocalTime startTime = cfg.getStartCandleTime();

        updateOpenPnL();

        if (session.getState() == StrategyState.WAITING_FOR_CANDLES) {
            handleWaitingForCandles(candle, candleTime, startTime);
        } else if (session.getState() == StrategyState.IN_POSITION) {
            handleInPosition(candle);
        }

        sessionPersistence.saveForUser(EngineKeys.of(username, strategyKey), session);
        broadcastUpdate();
    }

    private void handleWaitingForCandles(Candle candle, LocalTime candleTime, LocalTime startTime) {
        log.debug("Session Data : {}", session);
        if (session.getFirstCandle() == null) {
            log.debug("StartTime : {}, candleTime : {}", startTime, candleTime);
            if (!candleTime.isBefore(startTime)) {
                candle.setCandleIndex(1);
                session.setFirstCandle(candle);
                log.info("[{}] 1st candle: time={}, close={}", username, candleTime, candle.getClose());

                // AUTO_ATM: resolve strikes from first-candle close NOW and subscribe
                // immediately so ticks arrive during the second candle (1 min before entry).
                if (session.getConfig().getStrikeMode() == StrikeMode.AUTO_ATM) {
                    optionInstrumentService.resolveAndLockInstruments(
                            session.getConfig(), session, candle.getClose());
                    subscribeAndSeedAutoAtmOptions();
                    sessionPersistence.saveForUser(EngineKeys.of(username, strategyKey), session);
                    broadcastUpdate();  // push locked strikes to UI immediately
                }
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
        double close1 = session.getFirstCandle().getClose();
        double close2 = session.getSecondCandle().getClose();

        // Instruments already resolved:
        //   MANUAL  → locked at startSession()
        //   AUTO_ATM → locked at first candle close in handleWaitingForCandles()
        // No need to call resolveAndLockInstruments here.

        OptionType signalType = close1 > close2 ? OptionType.PE : OptionType.CE;
        session.setCurrentSignalType(signalType);

        OptionType tradedType = tradedTypeFor(signalType);
        log.info("[{}] Entry signal: {} → traded: {} (direction={}, 1st={}, 2nd={})",
                username, signalType, tradedType, session.getConfig().getTradeDirection(), close1, close2);

        enterPosition(tradedType, "INITIAL");
        session.setState(StrategyState.IN_POSITION);
    }

    /** Maps the price-action signal to the actual instrument leg to trade, per configured direction. */
    private OptionType tradedTypeFor(OptionType signalType) {
        TradeDirection direction = session.getConfig().getTradeDirection();
        if (direction == TradeDirection.SELL) {
            return signalType == OptionType.CE ? OptionType.PE : OptionType.CE;
        }
        return signalType;
    }

    private void subscribeAndSeedAutoAtmOptions() {
        LocalDate expiry   = session.getLockedExpiry();
        int ceStrike       = session.getLockedCeStrike();
        int peStrike       = session.getLockedPeStrike();
        String index       = indexPrefix();

        if (expiry == null) {
            log.error("[{}] AUTO_ATM: lockedExpiry is null — cannot subscribe options", username);
            return;
        }

        // Look up by attributes (expiry date + strike + type) instead of constructed symbol name.
        // This avoids format mismatches between our generated name and what Kite stores.
        Map<Long, String> toSub = new HashMap<>();

        kiteInstrumentService.findOption(index, expiry, ceStrike, "CE").ifPresentOrElse(i -> {
            toSub.put(i.getInstrumentToken(), i.getTradingsymbol());
            session.setLockedCeInstrument(i.getTradingsymbol());  // correct name from Kite
            log.info("[{}] AUTO_ATM CE: {} token={}", username, i.getTradingsymbol(), i.getInstrumentToken());
        }, () -> log.warn("[{}] AUTO_ATM CE not in cache: index={} expiry={} strike={}", username, index, expiry, ceStrike));

        kiteInstrumentService.findOption(index, expiry, peStrike, "PE").ifPresentOrElse(i -> {
            toSub.put(i.getInstrumentToken(), i.getTradingsymbol());
            session.setLockedPeInstrument(i.getTradingsymbol());  // correct name from Kite
            log.info("[{}] AUTO_ATM PE: {} token={}", username, i.getTradingsymbol(), i.getInstrumentToken());
        }, () -> log.warn("[{}] AUTO_ATM PE not in cache: index={} expiry={} strike={}", username, index, expiry, peStrike));

        if (!toSub.isEmpty()) {
            subscribeInstruments(toSub);
            kiteTickerService.subscribe(toSub);
            log.info("[{}] AUTO_ATM: subscribed {} instruments — ticks will arrive before 2nd candle closes",
                    username, toSub.size());
        } else {
            log.error("[{}] AUTO_ATM: no instruments found in cache for index={} expiry={} CE={} PE={} — Kite may not be connected",
                    username, index, expiry, ceStrike, peStrike);
        }
    }

    private void handleInPosition(Candle candle) {
        session.setLastClosedCandle(candle);
        TradingConfig cfg = session.getConfig();
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null) return;

        double currentClose = candle.getClose();
        double firstClose = session.getFirstCandle().getClose();

        boolean shouldReverse = false;
        OptionType newSignalType = null;
        OptionType currentSignalType = session.getCurrentSignalType();

        if (currentSignalType == OptionType.CE && currentClose < firstClose) {
            shouldReverse = true;
            newSignalType = OptionType.PE;
            log.info("[{}] Reversal signal CE→PE (close={} < first={})", username, currentClose, firstClose);
        } else if (currentSignalType == OptionType.PE && currentClose > firstClose) {
            shouldReverse = true;
            newSignalType = OptionType.CE;
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
            session.setCurrentSignalType(newSignalType);

            if (isPnLExitTriggered()) return;

            OptionType newTradedType = tradedTypeFor(newSignalType);
            if (isAdmin()) {
                String msg = String.format(
                        "🔄 <b>Position Reversed</b>\nFrom: <code>%s</code>\nTo: <code>%s</code>\nClose: <code>%.2f</code>\nFirst Close: <code>%.2f</code>",
                        openLeg.getOptionType(), newTradedType, currentClose, firstClose);
                telegramService.sendStrategyMessage(msg);
            }
            enterPosition(newTradedType, "REVERSAL_" + session.getReversalCount());
        }

        checkExitConditions();
    }

    private void enterPosition(OptionType optionType, String reason) {
        TradingConfig cfg = session.getConfig();
        String instrument = optionType == OptionType.CE
                ? session.getLockedCeInstrument() : session.getLockedPeInstrument();
        int strikePrice = optionType == OptionType.CE
                ? session.getLockedCeStrike() : session.getLockedPeStrike();

        double ltp = getLtp(instrument);
        if (ltp <= 0) {
            log.error("[{}] No tick data for {} — ticks have not arrived yet for this instrument. Aborting entry [{}].",
                    username, instrument, reason);
            internalStopSession("No tick data for " + instrument
                    + " — ensure Kite ticker is subscribed and connected");
            return;
        }

        boolean isSell = cfg.getTradeDirection() == TradeDirection.SELL;
        log.info("[{}] Placing {} order (entry)", username, isSell ? "SELL (write)" : "BUY");
        double entryPrice = isSell
                ? placeSellOrder(instrument, cfg.getTotalQuantity())
                : placeBuyOrder(instrument, cfg.getTotalQuantity());

        TradeEntry leg = TradeEntry.builder()
                .legNumber(session.getCurrentLegNumber() + 1)
                .optionType(optionType)
                .instrument(instrument)
                .strikePrice(strikePrice)
                .expiryType(cfg.getExpiryType())
                .quantity(cfg.getTotalQuantity())
                .entryPrice(entryPrice)
                .entryTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .closed(false)
                .build();

        session.setCurrentLegNumber(leg.getLegNumber());
        session.getTradeLegs().add(leg);

        int effectiveStrike = strikePrice > 0 ? strikePrice : parseStrikeFromSymbol(instrument);
        if (isAdmin()) {
            String msg = String.format(
                    "✅ <b>Position Entered</b>\nType: <code>%s %s</code>\nInstrument: <code>%s</code>\nStrike: <code>%s</code>\nPrice: <code>%.2f</code>\nQty: <code>%d</code>\nReason: <code>%s</code>",
                    isSell ? "SELL" : "BUY", optionType, instrument,
                    effectiveStrike > 0 ? String.valueOf(effectiveStrike) : "—",
                    entryPrice, leg.getQuantity(), reason);
            telegramService.sendStrategyMessage(msg);
        }

        log.info("[{}] Entered leg={} type={} instrument={} entry={} reason={}",
                username, leg.getLegNumber(), optionType, instrument, entryPrice, reason);
    }

    private void exitCurrentPosition(String reason) {
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null || openLeg.isClosed()) return;

        boolean isSell = session.getConfig().getTradeDirection() == TradeDirection.SELL;
        double exitPrice;
        if (!hasOpenPosition(openLeg.getInstrument())) {
            log.warn("[{}] No open position for {} in broker (manually squared off?)", username, openLeg.getInstrument());
            exitPrice = getLtp(openLeg.getInstrument());
        } else {
            // SELL leg is closed by buying it back; BUY leg is closed by selling it.
            exitPrice = isSell
                    ? placeBuyOrder(openLeg.getInstrument(), openLeg.getQuantity())
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

        log.info("[{}] Exited leg={} type={} exit={} legPnL={} cumPnL={} reason={}",
                username, openLeg.getLegNumber(), openLeg.getOptionType(),
                exitPrice, legPnl, session.getCumulativePnL(), reason);

        if (isAdmin()) {
            String msg = String.format(
                    "🔚 <b>Position Exited</b>\nType: <code>%s</code>\nInstrument: <code>%s</code>\nEntry: <code>%.2f</code>\nExit: <code>%.2f</code>\nP/L: <code>%.2f</code>\nReason: <code>%s</code>",
                    openLeg.getOptionType(), openLeg.getInstrument(),
                    openLeg.getEntryPrice(), exitPrice, legPnl, reason);
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

    private void checkExitConditions() {
        RiskExitEvaluator.ExitDecision decision = riskExitEvaluator.evaluate(session, session.getOpenPnL());
        if (decision == RiskExitEvaluator.ExitDecision.NONE) return;

        double totalPnl = session.getTotalPnL();
        String reason = switch (decision) {
            case STOPLOSS -> "STOPLOSS";
            case TARGET -> "TARGET";
            case TRAILING_STOP -> "TRAILING_STOP";
            default -> "STOPLOSS";
        };
        log.info("[{}] {} hit: {}", username, reason, totalPnl);
        exitCurrentPosition(reason);
        internalStopSession(reason + " hit: " + totalPnl);
    }

    private boolean isPnLExitTriggered() {
        RiskExitEvaluator.ExitDecision decision = riskExitEvaluator.evaluate(session, 0);
        if (decision == RiskExitEvaluator.ExitDecision.NONE) return false;
        double totalPnl = session.getTotalPnL();
        String reason = switch (decision) {
            case STOPLOSS -> "Stop loss hit after reversal: ";
            case TARGET -> "Target profit reached after reversal: ";
            case TRAILING_STOP -> "Trailing stop after reversal: pnl=";
            default -> "Exit after reversal: ";
        };
        internalStopSession(reason + totalPnl);
        return true;
    }

    private void internalStopSession(String reason) {
        session.setState(StrategyState.STOPPED);
        session.setEndTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        session.setStopReason(reason);
        log.info("[{}] Strategy stopped: {}", username, reason);
        sessionPersistence.saveForUser(EngineKeys.of(username, strategyKey), session);
        sendTelegramSummary(reason);
    }

    private void sendTelegramSummary(String stopReason) {
        try {
            String endT = session.getEndTime() != null
                    ? session.getEndTime().format(IST_FMT) + " IST"
                    : LocalDateTime.now(ZoneId.of("Asia/Kolkata")).format(IST_FMT) + " IST";
            double totalPnl = session.getCumulativePnL();

            if (!isAdmin()) {
                String simple = String.format(
                        "🔔 <b>Strategy Stopped</b>\n" +
                        "User: <code>%s</code>\n" +
                        "Stop Reason: <code>%s</code>\n" +
                        "Time: <code>%s</code>\n\n" +
                        "💰 <b>P&amp;L: %.0f</b>",
                        username, stopReason, endT, totalPnl);
                telegramService.sendStrategyMessage(simple);
                return;
            }

            TradingConfig cfg = session.getConfig();

            String firstCandleInfo = "—";
            if (session.getFirstCandle() != null) {
                Candle c = session.getFirstCandle();
                String t = c.getOpenTime() != null
                        ? c.getOpenTime().toLocalTime().toString().substring(0, 5) : "—";
                firstCandleInfo = String.format("Time: %s IST | Close: %.2f", t, c.getClose());
            }

            String lastCandleInfo = "—";
            Candle lastC = session.getLastClosedCandle() != null
                    ? session.getLastClosedCandle() : session.getSecondCandle();
            if (lastC != null) {
                String t = lastC.getOpenTime() != null
                        ? lastC.getOpenTime().toLocalTime().toString().substring(0, 5) : "—";
                lastCandleInfo = String.format("Time: %s IST | Close: %.2f", t, lastC.getClose());
            }

            String strategyDirection = "—";
            if (!session.getTradeLegs().isEmpty()) {
                strategyDirection = session.getTradeLegs().get(0).getOptionType().name();
            }

            String strikeInfo = cfg.getStrikeMode() == StrikeMode.AUTO_ATM
                    ? String.format("AUTO ATM | CE: %s | PE: %s",
                            session.getLockedCeInstrument() != null ? session.getLockedCeInstrument() : "—",
                            session.getLockedPeInstrument() != null ? session.getLockedPeInstrument() : "—")
                    : String.format("MANUAL | CE: %s | PE: %s",
                            session.getLockedCeInstrument() != null ? session.getLockedCeInstrument() : "—",
                            session.getLockedPeInstrument() != null ? session.getLockedPeInstrument() : "—");

            String startT = session.getStartTime() != null
                    ? session.getStartTime().format(IST_FMT) + " IST" : "—";

            String html = String.format(
                    "📊 <b>Strategy Summary</b>\n\n" +
                    "User: <code>%s</code>\n" +
                    "Strategy: <code>%s</code>\n" +
                    "Direction: <code>%s</code>\n" +
                    "Strike: <code>%s</code>\n" +
                    "Futures: <code>%s</code>  |  Expiry: <code>%s</code>\n" +
                    "Start: <code>%s</code>\n" +
                    "End: <code>%s</code>\n" +
                    "Stop Reason: <code>%s</code>\n\n" +
                    "📌 First Candle: <code>%s</code>\n" +
                    "📌 Last Candle: <code>%s</code>\n\n" +
                    "<b>Trade Log:</b>\n" +
                    "<pre>" +
                    "%-3s %-4s %-8s %-8s %-8s %-8s %-8s %-12s\n" +
                    "%s" +
                    "</pre>\n\n" +
                    "💰 <b>Total P&amp;L: %.0f</b>",
                    username, strategyDirection, cfg.getTradeDirection(), strikeInfo,
                    cfg.getFuturesInstrument(), cfg.getExpiryType(),
                    startT, endT, stopReason,
                    firstCandleInfo, lastCandleInfo,
                    "#", "TYPE", "ENTRY", "ENTRY-T", "EXIT", "EXIT-T", "P&L", "REASON",
                    buildTextTable(), totalPnl);

            telegramService.sendStrategyMessage(html);
        } catch (Exception e) {
            log.warn("[{}] Failed to send Telegram summary: {}", username, e.getMessage());
        }
    }

    private boolean isAdmin() {
        return platformUser != null && "GNANESH".equalsIgnoreCase(platformUser.getRole());
    }

    private int parseStrikeFromSymbol(String instrument) {
        if (instrument == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4,6})(CE|PE)$")
                .matcher(instrument);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String buildTextTable() {
        StringBuilder sb = new StringBuilder();
        for (TradeEntry leg : session.getTradeLegs()) {
            String entryT = leg.getEntryTime() != null
                    ? leg.getEntryTime().toLocalTime().toString().substring(0, 8)
                    : "—";
            String exitT = leg.getExitTime() != null
                    ? leg.getExitTime().toLocalTime().toString().substring(0, 8)
                    : "OPEN";
            sb.append(String.format("%-3d %-4s %-8.2f %-8s %-8.2f %-8s %-8.0f %-12s\n",
                    leg.getLegNumber(),
                    leg.getOptionType().name(),
                    leg.getEntryPrice(),
                    entryT,
                    leg.getExitPrice(),
                    exitT,
                    leg.getPnl(),
                    leg.isClosed() ? leg.getExitReason() : "OPEN"
            ));
        }
        return sb.toString();
    }

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
        if (r.contains("trailing stop")) return "TRAILING_STOP";
        if (r.contains("target")) return "TARGET_HIT";
        if (r.contains("stop loss") || r.contains("sl")) return "SL_HIT";
        if (r.contains("max reversal")) return "MAX_REVERSALS";
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
            log.warn("[{}] WebSocket broadcast failed: {}", username, e.getMessage());
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
