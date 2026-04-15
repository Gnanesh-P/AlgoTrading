package com.algotrading.backend.engine;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.dto.AlgoStatusResponse;
import com.algotrading.backend.model.*;
import com.algotrading.backend.service.KiteInstrumentService;
import com.algotrading.backend.service.KiteTickerService;
import com.algotrading.backend.service.OptionInstrumentService;
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

public class UserTradingEngine {

    private static final Logger log = LoggerFactory.getLogger(UserTradingEngine.class);
    private static final DateTimeFormatter IST_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");

    @Getter
    private final String username;
    private final PlatformUser platformUser;
    private final TelegramService telegramService;

    private KiteConnect kiteConnect;
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

    public UserTradingEngine(TelegramService telegramService,
                             PlatformUser platformUser,
                             MarketDataCache globalCache,
                             OptionInstrumentService optionInstrumentService,
                             KiteInstrumentService kiteInstrumentService,
                             KiteTickerService kiteTickerService,
                             SimpMessagingTemplate messagingTemplate,
                             SessionPersistenceService sessionPersistence) {
        this.telegramService = telegramService;
        this.platformUser = platformUser;
        this.username = platformUser.getUsername();
        this.globalCache = globalCache;
        this.optionInstrumentService = optionInstrumentService;
        this.kiteInstrumentService = kiteInstrumentService;
        this.kiteTickerService = kiteTickerService;
        this.messagingTemplate = messagingTemplate;
        this.sessionPersistence = sessionPersistence;

        if (platformUser.getKiteApiKey() != null && !platformUser.getKiteApiKey().isBlank()) {
            this.kiteConnect = new KiteConnect(platformUser.getKiteApiKey());
            if (platformUser.getKiteAccessToken() != null) {
                this.kiteConnect.setAccessToken(platformUser.getKiteAccessToken());
            }
        }
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
        Double cached = priceCache.get(instrument);
        if (cached != null && cached > 0) return cached;

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
        if (kiteConnect == null || platformUser.getKiteAccessToken() == null) {
            throw new IllegalStateException("[" + username + "] KiteConnect not initialized for live trading");
        }
        try {
            OrderParams p = new OrderParams();
            p.tradingsymbol = instrument;
            p.exchange = Constants.EXCHANGE_NFO;
            p.transactionType = Constants.TRANSACTION_TYPE_BUY;
            p.orderType = Constants.ORDER_TYPE_MARKET;
            p.quantity = qty;
            p.product = Constants.PRODUCT_MIS;
            p.validity = Constants.VALIDITY_DAY;
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
            p.tradingsymbol = instrument;
            p.exchange = Constants.EXCHANGE_NFO;
            p.transactionType = Constants.TRANSACTION_TYPE_SELL;
            p.orderType = Constants.ORDER_TYPE_MARKET;
            p.quantity = qty;
            p.product = Constants.PRODUCT_MIS;
            p.validity = Constants.VALIDITY_DAY;
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

    public synchronized TradeSession startSession(TradingConfig config,
                                                  Map<Long, String> instruments,
                                                  String startedBy) {
        formingCandles.clear();

        String startMsg = String.format(
                "🚀 <b>Strategy Started</b>\n" +
                        "User: <code>%s</code>\n" +
                        "Mode: <code>%s</code>\n" +
                        "Futures: <code>%s</code>\n" +
                        "Expiry: <code>%s</code>\n" +
                        "Lots: <code>%d</code>\n" +
                        "Qty: <code>%d</code>\n" +
                        "SL: <code>%.2f</code>\n" +
                        "Target: <code>%.2f</code>",
                username,
                config.getTradeMode(),
                config.getFuturesInstrument(),
                config.getExpiryType(),
                config.getLotQuantity(),
                config.getTotalQuantity(),
                config.getStopLoss(),
                config.getTargetProfit()
        );
        telegramService.sendStrategyMessage(startMsg);

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

    public synchronized TradeSession stopSession() {
        if (session == null) throw new IllegalStateException("No active session for user: " + username);
        if (session.getState() == StrategyState.IN_POSITION) {
            exitCurrentPosition("MANUAL_STOP");
        }
        internalStopSession("Manual stop");
        sessionPersistence.clearForUser(username);
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
        sessionPersistence.clearForUser(username);
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

        double liveLegPnL = (ltp - openLeg.getEntryPrice()) * openLeg.getQuantity();
        double totalPnL = session.getTotalRealizedPnL() + liveLegPnL;
        TradingConfig cfg = session.getConfig();

        session.setOpenPnL(liveLegPnL);

        if (cfg.getStopLoss() > 0 && totalPnL <= -cfg.getStopLoss()) {
            log.info("[{}] SL hit on tick: totalPnL={} <= -{} | ltp={}", username, totalPnL, cfg.getStopLoss(), ltp);
            exitCurrentPosition("STOPLOSS");
            internalStopSession("Stop loss hit: " + totalPnL);
            sessionPersistence.saveForUser(username, session);
            broadcastUpdate();
            return;
        }

        double trailing = cfg.getTrailingProfit();

        if (trailing <= 0 && totalPnL >= cfg.getTargetProfit()) {
            log.info("[{}] Target hit on tick: totalPnL={} >= {} | ltp={}", username, totalPnL, cfg.getTargetProfit(), ltp);
            exitCurrentPosition("TARGET");
            internalStopSession("Target profit reached: " + totalPnL);
            sessionPersistence.saveForUser(username, session);
            broadcastUpdate();
            return;
        }

        if (trailing > 0) {
            if (!session.isTrailingActive() && totalPnL >= cfg.getTargetProfit()) {
                session.setTrailingActive(true);
                session.setTrailingHighWatermark(totalPnL);
                log.info("[{}] Trailing activated on tick: pnl={}, step={}", username, totalPnL, trailing);
            }
            if (session.isTrailingActive()) {
                if (totalPnL > session.getTrailingHighWatermark()) {
                    session.setTrailingHighWatermark(totalPnL);
                }
                double exitLevel = session.getTrailingHighWatermark() - trailing;
                if (totalPnL <= exitLevel) {
                    log.info("[{}] Trailing stop on tick: pnl={} <= watermark({}) - step({})",
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
        LocalTime startTime = cfg.getStartCandleTime();

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
        log.debug("Session Data : {}", session);
        if (session.getFirstCandle() == null) {
            log.debug("StartTime : {}, candleTime : {}", startTime, candleTime);
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
        TradingConfig cfg = session.getConfig();
        double close1 = session.getFirstCandle().getClose();
        double close2 = session.getSecondCandle().getClose();
        double niftyPrice = close2 > 0 ? close2 : getLtp(cfg.getFuturesInstrument());
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
            kiteTickerService.subscribe(toSub);
            log.info("[{}] AUTO_ATM: subscribed CE/PE — {}", username, toSub.values());
        } else {
            log.warn("[{}] AUTO_ATM: instrument tokens not found for CE={} PE={}", username, ceSymbol, peSymbol);
        }

        seedLtp(ceSymbol);
        seedLtp(peSymbol);
    }

    private void seedLtp(String instrument) {
        if (priceCache.getOrDefault(instrument, 0.0) > 0) return;

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
        TradingConfig cfg = session.getConfig();
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null) return;

        double currentClose = candle.getClose();
        double firstClose = session.getFirstCandle().getClose();

        boolean shouldReverse = false;
        OptionType newDir = null;

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

            String msg = String.format(
                    "🔄 <b>Position Reversed</b>\nFrom: <code>%s</code>\nTo: <code>%s</code>\nClose: <code>%.2f</code>\nFirst Close: <code>%.2f</code>",
                    openLeg.getOptionType(), newDir, currentClose, firstClose);
            telegramService.sendStrategyMessage(msg);
            enterPosition(newDir, "REVERSAL_" + session.getReversalCount());
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

        String msg = String.format(
                "✅ <b>Position Entered</b>\nType: <code>%s</code>\nInstrument: <code>%s</code>\nStrike: <code>%d</code>\nPrice: <code>%.2f</code>\nQty: <code>%d</code>\nReason: <code>%s</code>",
                optionType, instrument, strikePrice, entryPrice, leg.getQuantity(), reason);
        telegramService.sendStrategyMessage(msg);

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
        openLeg.setExitTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        openLeg.setExitReason(reason);
        openLeg.setClosed(true);

        double legPnl = (exitPrice - openLeg.getEntryPrice()) * openLeg.getQuantity();
        openLeg.setPnl(legPnl);
        session.setCumulativePnL(session.getCumulativePnL() + legPnl);
        session.setOpenPnL(0);

        log.info("[{}] Exited leg={} type={} exit={} legPnL={} cumPnL={} reason={}",
                username, openLeg.getLegNumber(), openLeg.getOptionType(),
                exitPrice, legPnl, session.getCumulativePnL(), reason);

        String msg = String.format(
                "🔚 <b>Position Exited</b>\nType: <code>%s</code>\nInstrument: <code>%s</code>\nEntry: <code>%.2f</code>\nExit: <code>%.2f</code>\nP/L: <code>%.2f</code>\nReason: <code>%s</code>",
                openLeg.getOptionType(), openLeg.getInstrument(),
                openLeg.getEntryPrice(), exitPrice, legPnl, reason);
        telegramService.sendStrategyMessage(msg);
    }

    private void updateOpenPnL() {
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null) {
            session.setOpenPnL(0);
            return;
        }
        double ltp = getLtp(openLeg.getInstrument());
        if (ltp > 0) {
            session.setOpenPnL((ltp - openLeg.getEntryPrice()) * openLeg.getQuantity());
        }
    }

    private void checkExitConditions() {
        TradingConfig cfg = session.getConfig();
        double totalPnl = session.getTotalPnL();
        double trailingStep = cfg.getTrailingProfit();
        boolean useTrailing = trailingStep > 0;

        if (cfg.getStopLoss() > 0 && totalPnl <= -cfg.getStopLoss()) {
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
        TradingConfig cfg = session.getConfig();
        double totalPnl = session.getTotalPnL();
        double trailingStep = cfg.getTrailingProfit();
        boolean useTrailing = trailingStep > 0;

        if (cfg.getStopLoss() > 0 && totalPnl <= -cfg.getStopLoss()) {
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
        session.setEndTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        session.setStopReason(reason);
        log.info("[{}] Strategy stopped: {}", username, reason);
        sessionPersistence.saveForUser(username, session);
        sendTelegramSummary(reason);
    }

    private void sendTelegramSummary(String stopReason) {
        try {
            TradingConfig cfg = session.getConfig();
            ZoneId ist = ZoneId.of("Asia/Kolkata");

            String firstCandleInfo = "—";
            if (session.getFirstCandle() != null) {
                Candle c = session.getFirstCandle();
                String t = c.getOpenTime() != null
                        ? c.getOpenTime().atZone(ZoneId.systemDefault()).withZoneSameInstant(ist)
                              .toLocalTime().toString().substring(0, 5)
                        : "—";
                firstCandleInfo = String.format("Time: %s | Close: %.2f", t, c.getClose());
            }

            String lastCandleInfo = "—";
            Candle lastC = session.getLastClosedCandle() != null
                    ? session.getLastClosedCandle() : session.getSecondCandle();
            if (lastC != null) {
                String t = lastC.getOpenTime() != null
                        ? lastC.getOpenTime().atZone(ZoneId.systemDefault()).withZoneSameInstant(ist)
                              .toLocalTime().toString().substring(0, 5)
                        : "—";
                lastCandleInfo = String.format("Time: %s | Close: %.2f", t, lastC.getClose());
            }

            String strategyDirection = "—";
            if (!session.getTradeLegs().isEmpty()) {
                strategyDirection = session.getTradeLegs().get(0).getOptionType().name();
            }

            StringBuilder tableRows = new StringBuilder();
            for (TradeEntry leg : session.getTradeLegs()) {
                String entryT = leg.getEntryTime() != null
                        ? leg.getEntryTime().atZone(ZoneId.systemDefault()).withZoneSameInstant(ist)
                              .toLocalTime().toString().substring(0, 8)
                        : "—";
                String exitT = leg.getExitTime() != null
                        ? leg.getExitTime().toLocalTime().toString().substring(0, 8)
                        : "OPEN";
                tableRows.append(String.format(
                        "<tr><td>%d</td><td>%s</td><td>%.2f</td><td>%s</td><td>%.2f</td><td>%s</td><td>%.0f</td><td>%s</td></tr>",
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

            String startT = session.getStartTime() != null
                    ? session.getStartTime().atZone(ZoneId.systemDefault()).withZoneSameInstant(ist)
                          .format(IST_FMT)
                    : "—";
            String endT = session.getEndTime() != null
                    ? session.getEndTime().format(IST_FMT)
                    : LocalDateTime.now(ist).format(IST_FMT);

            double totalPnl = session.getCumulativePnL();

            String html = String.format(
                    "📊 <b>Strategy Summary — %s</b>\n\n" +
                    "User: <code>%s</code>\n" +
                    "Strategy: <code>%s</code>\n" +
                    "Futures: <code>%s</code>  |  Expiry: <code>%s</code>\n" +
                    "Start: <code>%s</code>  |  End: <code>%s</code>\n" +
                    "Stop Reason: <code>%s</code>\n\n" +
                    "📌 First Candle: <code>%s</code>\n" +
                    "📌 Last Candle: <code>%s</code>\n\n" +
                    "<b>Trade Log:</b>\n" +
                    "<pre>" +
                    "%-3s %-4s %-8s %-8s %-8s %-8s %-8s %-12s\n" +
                    "%s" +
                    "</pre>\n\n" +
                    "💰 <b>Total P&amp;L: %.0f</b>",
                    stopReason,
                    username,
                    strategyDirection,
                    cfg.getFuturesInstrument(),
                    cfg.getExpiryType(),
                    startT,
                    endT,
                    stopReason,
                    firstCandleInfo,
                    lastCandleInfo,
                    "#", "TYPE", "ENTRY", "ENTRY-T", "EXIT", "EXIT-T", "P&L", "REASON",
                    buildTextTable(),
                    totalPnl
            );

            telegramService.sendStrategyMessage(html);
        } catch (Exception e) {
            log.warn("[{}] Failed to send Telegram summary: {}", username, e.getMessage());
        }
    }

    private String buildTextTable() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        StringBuilder sb = new StringBuilder();
        for (TradeEntry leg : session.getTradeLegs()) {
            String entryT = leg.getEntryTime() != null
                    ? leg.getEntryTime().atZone(ZoneId.systemDefault()).withZoneSameInstant(ist)
                          .toLocalTime().toString().substring(0, 8)
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
                liveOpenPnL = (ltp - openLeg.getEntryPrice()) * openLeg.getQuantity();
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

    public boolean isTickerConnected() {
        return tickerConnected;
    }
}
