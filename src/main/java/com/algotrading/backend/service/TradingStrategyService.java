package com.algotrading.backend.service;

import com.algotrading.backend.broker.BrokerService;
import com.algotrading.backend.broker.BrokerServiceFactory;
import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core trading strategy engine.
 *
 * Strategy:
 * 1. Collect 1st, 2nd, 3rd 1-min candles starting from configured start time.
 * 2. At close of 3rd candle: if close(1st) > close(2nd) → enter PE; else → enter CE.
 * 3. Monitor for reversals on each subsequent 1-min candle close:
 *    - In CE: if current close < close(1st) → exit CE, enter PE
 *    - In PE: if current close > close(1st) → exit PE, enter CE
 * 4. Exit conditions: target profit hit OR stop loss hit OR max reversals exhausted.
 *
 * P&L is tracked cumulatively across all legs (options entry/exit prices only).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradingStrategyService {

    private final MarketDataCache cache;
    private final BrokerServiceFactory brokerFactory;
    private final OptionInstrumentService optionInstrumentService;
    private final KiteInstrumentService kiteInstrumentService;
    private final KiteTickerService kiteTickerService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Called on every new 1-minute candle close for the configured NIFTY Futures instrument.
     * This is the main strategy event handler.
     */
    public synchronized void onCandleClose(Candle candle) {
        TradeSession session = cache.getSession();
        if (session == null || session.getState() == StrategyState.STOPPED
                || session.getState() == StrategyState.IDLE) {
            return;
        }

        TradingConfig config = session.getConfig();
        if (!candle.getInstrument().equals(config.getFuturesInstrument())) {
            return;
        }

        log.debug("Strategy processing candle: time={}, close={}", candle.getOpenTime(), candle.getClose());

        LocalTime candleTime = candle.getOpenTime().toLocalTime();
        LocalTime startTime = config.getStartCandleTime();

        if (session.getState() == StrategyState.WAITING_FOR_CANDLES) {
            handleWaitingForCandles(session, candle, candleTime, startTime);
        } else if (session.getState() == StrategyState.IN_POSITION) {
            handleInPosition(session, candle);
        }

        // Update open P&L
        updateOpenPnL(session);
        cache.setSession(session);
        broadcastUpdate(session);
    }

    private void handleWaitingForCandles(TradeSession session, Candle candle,
                                         LocalTime candleTime, LocalTime startTime) {
        if (session.getFirstCandle() == null) {
            if (!candleTime.isBefore(startTime)) {
                session.setFirstCandle(candle);
                candle.setCandleIndex(1);
                log.info("1st candle captured: time={}, close={}", candleTime, candle.getClose());
            }
        } else if (session.getSecondCandle() == null) {
            session.setSecondCandle(candle);
            candle.setCandleIndex(2);
            log.info("2nd candle captured: time={}, close={} → resolving strike & entering at 3rd candle open",
                    candleTime, candle.getClose());
            // Enter at 3rd candle START (= 2nd candle close). Strike resolved using 2nd candle close price.
            enterInitialPosition(session);
        }
    }

    private void enterInitialPosition(TradeSession session) {
        TradingConfig config = session.getConfig();
        double close1 = session.getFirstCandle().getClose();
        double close2 = session.getSecondCandle().getClose();

        // For AUTO_ATM: compute ATM from 2nd candle close price (deterministic, not live LTP).
        // Falls back to live NIFTY futures LTP, then to close1 if nothing is available.
        double niftyPrice = close2 > 0 ? close2 : cache.getLastPrice(config.getFuturesInstrument());
        if (niftyPrice <= 0) niftyPrice = close1;
        optionInstrumentService.resolveAndLockInstruments(config, session, niftyPrice);

        // For AUTO_ATM: subscribe the resolved CE/PE option instruments to KiteTicker
        // and seed the cache so the broker has valid LTP data at the moment of entry.
        if (config.getStrikeMode() == StrikeMode.AUTO_ATM) {
            subscribeAndSeedAutoAtmOptions(session);
        }

        OptionType direction = close1 > close2 ? OptionType.PE : OptionType.CE;
        log.info("Entry direction: {} (1st close={}, 2nd close={})", direction, close1, close2);

        enterPosition(session, direction, "INITIAL");
        session.setState(StrategyState.IN_POSITION);
    }

    /**
     * After ATM strike is resolved for AUTO_ATM mode:
     * 1. Look up instrument tokens from the local Kite instrument cache.
     * 2. Subscribe those tokens to KiteTicker so real-time ticks start flowing.
     * 3. Seed the MarketDataCache with the instrument's lastPrice (Kite dump prev-close)
     *    so the paper broker has a non-zero price for the entry order right away,
     *    before the first live tick arrives.
     */
    private void subscribeAndSeedAutoAtmOptions(TradeSession session) {
        Map<Long, String> toSubscribe = new HashMap<>();

        Optional<KiteInstrument> ceOpt = kiteInstrumentService.findBySymbol(session.getLockedCeInstrument());
        Optional<KiteInstrument> peOpt = kiteInstrumentService.findBySymbol(session.getLockedPeInstrument());

        ceOpt.ifPresent(inst -> {
            toSubscribe.put(inst.getInstrumentToken(), inst.getTradingsymbol());
            seedCacheIfCold(inst.getTradingsymbol(), inst.getLastPrice());
        });
        peOpt.ifPresent(inst -> {
            toSubscribe.put(inst.getInstrumentToken(), inst.getTradingsymbol());
            seedCacheIfCold(inst.getTradingsymbol(), inst.getLastPrice());
        });

        if (!toSubscribe.isEmpty()) {
            kiteTickerService.subscribe(toSubscribe);
            log.info("AUTO_ATM: subscribed CE/PE to KiteTicker — {}", toSubscribe.values());
        } else {
            log.warn("AUTO_ATM: could not find instrument tokens for CE={} PE={} — " +
                     "LTP data may be unavailable (Kite instrument cache empty?)",
                    session.getLockedCeInstrument(), session.getLockedPeInstrument());
        }
    }

    /**
     * Seeds the MarketDataCache with a price only when it has no existing entry
     * (cache is "cold"). This provides an initial approximation (Kite prev-close)
     * until a real live tick arrives and overwrites it.
     */
    private void seedCacheIfCold(String instrument, double seedPrice) {
        if (cache.getLastPrice(instrument) <= 0) {
            if (seedPrice > 0) {
                MarketTick seed = MarketTick.builder()
                        .instrument(instrument)
                        .lastPrice(seedPrice)
                        .timestamp(LocalDateTime.now())
                        .build();
                cache.updateTick(seed);
                log.info("AUTO_ATM: seeded cache for {} with prev-close={}", instrument, seedPrice);
            } else {
                log.warn("AUTO_ATM: no seed price available for {} (instrument dump lastPrice=0)", instrument);
            }
        }
    }

    private void handleInPosition(TradeSession session, Candle candle) {
        session.setLastClosedCandle(candle);
        TradingConfig config = session.getConfig();
        TradeEntry openLeg = session.getCurrentOpenLeg();

        if (openLeg == null) return;

        double currentClose = candle.getClose();
        double firstClose = session.getFirstCandle().getClose();

        // Check reversal condition
        boolean shouldReverse = false;
        OptionType newDirection = null;

        if (openLeg.getOptionType() == OptionType.CE && currentClose < firstClose) {
            shouldReverse = true;
            newDirection = OptionType.PE;
            log.info("Reversal signal: CE->PE (current close={} < 1st close={})", currentClose, firstClose);
        } else if (openLeg.getOptionType() == OptionType.PE && currentClose > firstClose) {
            shouldReverse = true;
            newDirection = OptionType.CE;
            log.info("Reversal signal: PE->CE (current close={} > 1st close={})", currentClose, firstClose);
        }

        if (shouldReverse) {
            if (session.getReversalCount() >= config.getMaxReversals()) {
                log.info("Max reversals ({}) exhausted. Stopping strategy.", config.getMaxReversals());
                exitCurrentPosition(session, "MAX_REVERSALS");
                stopSession(session, "Max reversals exhausted");
                return;
            }
            // Exit current and enter opposite
            exitCurrentPosition(session, "REVERSAL");
            session.setReversalCount(session.getReversalCount() + 1);

            // Check P&L after exit before entering new position
            if (isPnLExitTriggered(session, config)) return;

            enterPosition(session, newDirection, "REVERSAL_" + session.getReversalCount());
        }

        // Check exit conditions after any position update
        checkExitConditions(session, config);
    }

    private void enterPosition(TradeSession session, OptionType optionType, String reason) {
        TradingConfig config = session.getConfig();
        BrokerService broker = brokerFactory.getBrokerService(config.getTradeMode());

        String instrument = optionType == OptionType.CE
                ? session.getLockedCeInstrument()
                : session.getLockedPeInstrument();
        int strikePrice = optionType == OptionType.CE
                ? session.getLockedCeStrike()
                : session.getLockedPeStrike();

        // Guard: verify LTP is available before placing any order.
        // If the option has no tick data (not subscribed / no Kite connection / illiquid),
        // entering at ₹0 would corrupt all P&L tracking — abort instead.
        double currentLtp = broker.getLtp(instrument);
        if (currentLtp <= 0) {
            log.error("No tick data for {} (LTP=0). Aborting position entry [{}]. " +
                      "Check KiteTicker subscription or Kite connection.", instrument, reason);
            stopSession(session, "No LTP data for " + instrument + " — position entry aborted");
            return;
        }

        double entryPrice = broker.placeBuyOrder(instrument, config.getTotalQuantity());

        TradeEntry leg = TradeEntry.builder()
                .legNumber(session.getCurrentLegNumber() + 1)
                .optionType(optionType)
                .instrument(instrument)
                .strikePrice(strikePrice)
                .expiryType(config.getExpiryType())
                .quantity(config.getTotalQuantity())
                .entryPrice(entryPrice)
                .entryTime(LocalDateTime.now())
                .closed(false)
                .build();

        session.setCurrentLegNumber(leg.getLegNumber());
        session.getTradeLegs().add(leg);

        log.info("Entered position: leg={}, type={}, instrument={}, entry={}, reason={}",
                leg.getLegNumber(), optionType, instrument, entryPrice, reason);
    }

    private void exitCurrentPosition(TradeSession session, String reason) {
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null || openLeg.isClosed()) return;

        TradingConfig config = session.getConfig();
        BrokerService broker = brokerFactory.getBrokerService(config.getTradeMode());

        double exitPrice;
        if (!broker.hasOpenPosition(openLeg.getInstrument())) {
            // Position was already closed manually in Kite — record at current LTP, skip sell order.
            log.warn("No open position for {} in broker (manually squared off?). Marking closed without new sell order.",
                    openLeg.getInstrument());
            exitPrice = broker.getLtp(openLeg.getInstrument());
        } else {
            exitPrice = broker.placeSellOrder(openLeg.getInstrument(), openLeg.getQuantity());
        }

        openLeg.setExitPrice(exitPrice);
        openLeg.setExitTime(LocalDateTime.now());
        openLeg.setExitReason(reason);
        openLeg.setClosed(true);

        // P&L for options: we buy at entry, sell at exit
        double legPnl = (exitPrice - openLeg.getEntryPrice()) * openLeg.getQuantity();
        openLeg.setPnl(legPnl);
        session.setCumulativePnL(session.getCumulativePnL() + legPnl);

        log.info("Exited position: leg={}, type={}, exit={}, legPnL={}, cumulativePnL={}, reason={}",
                openLeg.getLegNumber(), openLeg.getOptionType(), exitPrice,
                legPnl, session.getCumulativePnL(), reason);
    }

    private void updateOpenPnL(TradeSession session) {
        TradeEntry openLeg = session.getCurrentOpenLeg();
        if (openLeg == null) {
            session.setOpenPnL(0);
            return;
        }
        TradingConfig config = session.getConfig();
        BrokerService broker = brokerFactory.getBrokerService(config.getTradeMode());
        double currentPrice = broker.getLtp(openLeg.getInstrument());
        if (currentPrice > 0) {
            session.setOpenPnL((currentPrice - openLeg.getEntryPrice()) * openLeg.getQuantity());
        }
    }

    /**
     * Checks exit conditions after every candle / reversal.
     *
     * Trailing profit logic (when config.trailingProfit > 0):
     *   Phase 1 — normal: wait until totalPnL >= targetProfit for the first time.
     *   Phase 2 — trailing: once targetProfit is hit, keep updating a high-watermark.
     *     • If totalPnL > highWatermark  → raise highWatermark to totalPnL (lock in gains).
     *     • If totalPnL < (highWatermark - trailingStep) → exit (trailing stop triggered).
     *   Regular exit: no trailing configured → exit as soon as targetProfit is reached.
     *   Stop-loss check applies in both phases.
     */
    private void checkExitConditions(TradeSession session, TradingConfig config) {
        double totalPnl      = session.getTotalPnL();
        double trailingStep  = config.getTrailingProfit();
        boolean useTrailing  = trailingStep > 0;

        // --- Stop loss (always applies) ---
        if (totalPnl <= -config.getStopLoss()) {
            log.info("Stop loss hit: {} <= -{}", totalPnl, config.getStopLoss());
            exitCurrentPosition(session, "STOPLOSS");
            stopSession(session, "Stop loss hit: " + totalPnl);
            return;
        }

        if (!useTrailing) {
            // Plain target: exit immediately when reached
            if (totalPnl >= config.getTargetProfit()) {
                log.info("Target profit reached: {} >= {}", totalPnl, config.getTargetProfit());
                exitCurrentPosition(session, "TARGET");
                stopSession(session, "Target profit reached: " + totalPnl);
            }
            return;
        }

        // --- Trailing profit ---
        if (!session.isTrailingActive()) {
            // Phase 1: waiting for target to be hit the first time
            if (totalPnl >= config.getTargetProfit()) {
                session.setTrailingActive(true);
                session.setTrailingHighWatermark(totalPnl);
                log.info("Trailing activated: targetProfit={} hit at pnl={}, step={}",
                        config.getTargetProfit(), totalPnl, trailingStep);
            }
        } else {
            // Phase 2: trailing active — update watermark and check exit
            if (totalPnl > session.getTrailingHighWatermark()) {
                log.info("Trailing watermark raised: {} → {}", session.getTrailingHighWatermark(), totalPnl);
                session.setTrailingHighWatermark(totalPnl);
            }
            double trailingExitLevel = session.getTrailingHighWatermark() - trailingStep;
            if (totalPnl <= trailingExitLevel) {
                log.info("Trailing stop triggered: pnl={} dropped below watermark({}) - step({}) = {}",
                        totalPnl, session.getTrailingHighWatermark(), trailingStep, trailingExitLevel);
                exitCurrentPosition(session, "TRAILING_STOP");
                stopSession(session, String.format("Trailing stop: watermark=%.0f step=%.0f pnl=%.0f",
                        session.getTrailingHighWatermark(), trailingStep, totalPnl));
            }
        }
    }

    /** Same logic used immediately after a reversal exit before opening the new leg. */
    private boolean isPnLExitTriggered(TradeSession session, TradingConfig config) {
        double totalPnl     = session.getTotalPnL();
        double trailingStep = config.getTrailingProfit();
        boolean useTrailing = trailingStep > 0;

        if (totalPnl <= -config.getStopLoss()) {
            stopSession(session, "Stop loss hit after reversal: " + totalPnl);
            return true;
        }
        if (!useTrailing && totalPnl >= config.getTargetProfit()) {
            stopSession(session, "Target profit reached after reversal: " + totalPnl);
            return true;
        }
        if (useTrailing && session.isTrailingActive()) {
            double trailingExitLevel = session.getTrailingHighWatermark() - trailingStep;
            if (totalPnl <= trailingExitLevel) {
                stopSession(session, "Trailing stop after reversal: pnl=" + totalPnl);
                return true;
            }
        }
        // Re-evaluate trailing activation after reversal exit
        if (useTrailing && !session.isTrailingActive() && totalPnl >= config.getTargetProfit()) {
            session.setTrailingActive(true);
            session.setTrailingHighWatermark(totalPnl);
        }
        return false;
    }

    /**
     * End-of-day automatic square-off at 15:29.
     * Exits open positions and stops the session when squareOffEod is enabled.
     */
    @Scheduled(cron = "0 29 15 * * MON-FRI")
    public void squareOffEodPositions() {
        TradeSession session = cache.getSession();
        if (session == null) return;
        if (session.getState() == StrategyState.STOPPED || session.getState() == StrategyState.IDLE) return;

        TradingConfig config = session.getConfig();
        if (!config.isSquareOffEod()) {
            log.info("EOD reached but squareOffEod is disabled — no auto exit.");
            return;
        }

        log.info("EOD 15:29 square-off triggered for session {}", session.getSessionId());
        if (session.getState() == StrategyState.IN_POSITION) {
            exitCurrentPosition(session, "EOD");
        }
        stopSession(session, "End of day square-off at 15:29");
        cache.setSession(session);
        broadcastUpdate(session);
    }

    private void stopSession(TradeSession session, String reason) {
        session.setState(StrategyState.STOPPED);
        session.setEndTime(LocalDateTime.now());
        session.setStopReason(reason);
        log.info("Strategy stopped: {}", reason);
    }

    /**
     * Start the trading strategy with the current configuration.
     */
    public TradeSession startStrategy() {
        TradingConfig config = cache.getConfig();
        if (config == null) {
            throw new IllegalStateException("Trading configuration not set. Configure before starting.");
        }

        TradeSession session = TradeSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .tradeDate(java.time.LocalDate.now())
                .config(config)
                .state(StrategyState.WAITING_FOR_CANDLES)
                .reversalCount(0)
                .currentLegNumber(0)
                .cumulativePnL(0.0)
                .openPnL(0.0)
                .startTime(LocalDateTime.now())
                .build();

        cache.setSession(session);
        log.info("Strategy started. Session: {}", session.getSessionId());
        return session;
    }

    /**
     * Manually stop the strategy.
     */
    public TradeSession stopStrategy() {
        TradeSession session = cache.getSession();
        if (session == null) throw new IllegalStateException("No active session.");
        if (session.getState() == StrategyState.IN_POSITION) {
            exitCurrentPosition(session, "MANUAL_STOP");
        }
        stopSession(session, "Manual stop");
        cache.setSession(session);
        return session;
    }

    private void broadcastUpdate(TradeSession session) {
        try {
            messagingTemplate.convertAndSend("/topic/trade-updates", session);
        } catch (Exception e) {
            log.warn("WebSocket broadcast failed: {}", e.getMessage());
        }
    }
}
