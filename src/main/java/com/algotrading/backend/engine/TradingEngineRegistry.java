package com.algotrading.backend.engine;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.model.*;
import com.algotrading.backend.service.*;
import com.zerodhatech.kiteconnect.KiteConnect;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradingEngineRegistry {

    private final Map<String, TradingEngine> engines = new ConcurrentHashMap<>();

    private final MarketDataCache         globalCache;
    private final OptionInstrumentService optionInstrumentService;
    private final KiteInstrumentService   kiteInstrumentService;
    private final KiteTickerService       kiteTickerService;
    private final SimpMessagingTemplate   messagingTemplate;
    private final SessionPersistenceService sessionPersistence;
    private final UserRegistryService     userRegistry;
    private final TelegramService         telegramService;
    private final KiteConnect kiteConnect;
    private final RiskExitEvaluator       riskExitEvaluator;

    @PostConstruct
    public void recoverAllSessions() {
        List<String> fileKeys = sessionPersistence.findAllPersistedUsernames();
        if (fileKeys.isEmpty()) {
            log.info("EngineRegistry: no persisted sessions found — clean start");
            return;
        }

        for (String fileKey : fileKeys) {
            sessionPersistence.loadForUser(fileKey).ifPresent(session -> {
                StrategyState state = session.getState();
                String username = session.getStartedBy();
                StrategyKey strategyKey = session.getConfig() != null
                        ? session.getConfig().getStrategyKey() : null;

                if (username == null || strategyKey == null) {
                    log.warn("Recovery: persisted file [{}] missing username/strategyKey — clearing", fileKey);
                    sessionPersistence.clearForUser(fileKey);
                    return;
                }

                if (state == StrategyState.IN_POSITION || state == StrategyState.WAITING_FOR_CANDLES) {
                    log.warn("CRASH RECOVERY [{}/{}]: restoring session {} (state={})",
                            username, strategyKey, session.getSessionId(), state);

                    userRegistry.findByUsername(username).ifPresentOrElse(
                            user -> {
                                TradingEngine engine = buildEngine(user, strategyKey);
                                engine.restoreSession(session);
                                engines.put(EngineKeys.of(username, strategyKey), engine);
                                log.info("Recovery [{}/{}]: engine restored", username, strategyKey);
                            },
                            () -> log.warn("Recovery [{}/{}]: user no longer exists — skipping", username, strategyKey)
                    );
                } else {
                    log.info("Recovery [{}/{}]: session state={} (terminal) — clearing file",
                            username, strategyKey, state);
                    sessionPersistence.clearForUser(fileKey);
                }
            });
        }
    }

    public TradingEngine startEngine(PlatformUser user,
                                      StrategyKey strategyKey,
                                      TradingConfig config,
                                      Map<Long, String> instruments,
                                      String startedBy) {
        String username = user.getUsername();
        String key = EngineKeys.of(username, strategyKey);
        config.setStrategyKey(strategyKey);

        TradingEngine stale = engines.get(key);
        if (stale != null && !stale.isActive()) {
            stale.disconnectKiteTicker();
            engines.remove(key);
        }

        TradingEngine engine = buildEngine(user, strategyKey);

        engine.subscribeInstruments(instruments);
        engine.startSession(config, instruments, startedBy);
        engines.put(key, engine);

        log.info("Engine started for [{}/{}] (mode={}, lots={})", username, strategyKey,
                config.getTradeMode(), config.getLotQuantity());
        return engine;
    }

    public Optional<TradeSession> stopEngine(String username, StrategyKey strategyKey) {
        TradingEngine engine = engines.get(EngineKeys.of(username, strategyKey));
        if (engine == null) return Optional.empty();

        TradeSession stopped = engine.stopSession();
        engine.disconnectKiteTicker();
        log.info("Engine stopped for [{}/{}] — kept in registry for P&L display", username, strategyKey);
        return Optional.of(stopped);
    }

    public void clearStoppedEngine(String username, StrategyKey strategyKey) {
        String key = EngineKeys.of(username, strategyKey);
        TradingEngine engine = engines.get(key);
        if (engine != null && !engine.isActive()) {
            engines.remove(key);
            log.info("Cleared stopped engine for [{}/{}]", username, strategyKey);
        }
    }

    public Optional<TradingEngine> getEngine(String username, StrategyKey strategyKey) {
        return Optional.ofNullable(engines.get(EngineKeys.of(username, strategyKey)));
    }

    public List<TradingEngine> getAllForUser(String username) {
        return engines.entrySet().stream()
                .filter(e -> username.equals(EngineKeys.usernameOf(e.getKey())))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    public boolean hasActiveEngine(String username, StrategyKey strategyKey) {
        TradingEngine engine = engines.get(EngineKeys.of(username, strategyKey));
        return engine != null && engine.isActive();
    }

    public Map<String, TradingEngine> getAllEngines() {
        return Collections.unmodifiableMap(engines);
    }

    private TradingEngine buildEngine(PlatformUser user, StrategyKey strategyKey) {
        if (strategyKey == StrategyKey.NIFTY_BREAKOUT_V2) {
            return new BreakoutV2StrategyEngine(
                    telegramService,
                    user,
                    strategyKey,
                    kiteConnect,
                    globalCache,
                    optionInstrumentService,
                    kiteInstrumentService,
                    kiteTickerService,
                    messagingTemplate,
                    sessionPersistence,
                    riskExitEvaluator);
        }
        if (strategyKey == StrategyKey.NIFTY_BREAKOUT || strategyKey == StrategyKey.BANKNIFTY_BREAKOUT
                || strategyKey == StrategyKey.SENSEX_BREAKOUT) {
            return new BreakoutStrategyEngine(
                    telegramService,
                    user,
                    strategyKey,
                    kiteConnect,
                    globalCache,
                    optionInstrumentService,
                    kiteInstrumentService,
                    kiteTickerService,
                    messagingTemplate,
                    sessionPersistence,
                    riskExitEvaluator);
        }
        return new ScalpingStrategyEngine(
                telegramService,
                user,
                strategyKey,
                kiteConnect,
                globalCache,
                optionInstrumentService,
                kiteInstrumentService,
                kiteTickerService,
                messagingTemplate,
                sessionPersistence,
                riskExitEvaluator);
    }

    public void routeTickToActiveEngines(MarketTick tick) {
        engines.values().forEach(engine -> {
            try {
                TradeSession s = engine.getSession();
                if (s == null || s.getConfig() == null) return;
                if (s.getState() == StrategyState.STOPPED || s.getState() == StrategyState.IDLE) return;
                engine.processTick(tick);
            } catch (Exception e) {
                // Isolate failures: one engine's exception must never block tick delivery
                // to the other concurrently-running engines for this same tick.
                log.error("[{}/{}] Uncaught error processing tick for {}: {}",
                        engine.getUsername(), engine.getStrategyKey(), tick.getInstrument(), e.getMessage(), e);
            }
        });
    }

    @Scheduled(cron = "0 29 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void squareOffAllEnginesEod() {
        log.info("EOD 15:29 IST — running square-off for {} engines", engines.size());
        engines.values().forEach(engine -> {
            try {
                engine.squareOffEod();
            } catch (Exception e) {
                log.error("EOD square-off error for [{}]: {}", engine.getUsername(), e.getMessage());
            }
        });
    }

    public List<Map<String, Object>> getAllSessionSummaries() {
        return engines.values().stream()
                .map(engine -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username", engine.getUsername());
                    m.put("strategyKey", engine.getStrategyKey());
                    TradeSession s = engine.getSession();
                    if (s != null) {
                        m.put("sessionId",  s.getSessionId());
                        m.put("state",      s.getState());
                        m.put("tradeMode",  s.getConfig() != null ? s.getConfig().getTradeMode() : null);
                        m.put("totalPnL",   s.getTotalPnL());
                        m.put("startTime",  s.getStartTime());
                        m.put("active",     engine.isActive());
                    }
                    return m;
                })
                .collect(Collectors.toList());
    }

    public void forceStopEngine(String username, StrategyKey strategyKey) {
        stopEngine(username, strategyKey);
        log.info("Admin force-stopped engine for [{}/{}]", username, strategyKey);
    }

    public void forceStopAllForUser(String username) {
        for (StrategyKey key : StrategyKey.values()) {
            if (hasActiveEngine(username, key)) {
                forceStopEngine(username, key);
            }
        }
    }
}
