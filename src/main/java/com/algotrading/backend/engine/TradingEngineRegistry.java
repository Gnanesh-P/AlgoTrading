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

    private final Map<String, UserTradingEngine> engines = new ConcurrentHashMap<>();

    private final MarketDataCache         globalCache;
    private final OptionInstrumentService optionInstrumentService;
    private final KiteInstrumentService   kiteInstrumentService;
    private final KiteTickerService       kiteTickerService;
    private final SimpMessagingTemplate   messagingTemplate;
    private final SessionPersistenceService sessionPersistence;
    private final UserRegistryService     userRegistry;
    private final TelegramService         telegramService;
    private final KiteConnect kiteConnect;

    @PostConstruct
    public void recoverAllSessions() {
        List<String> usernames = sessionPersistence.findAllPersistedUsernames();
        if (usernames.isEmpty()) {
            log.info("EngineRegistry: no persisted sessions found — clean start");
            return;
        }

        for (String username : usernames) {
            sessionPersistence.loadForUser(username).ifPresent(session -> {
                StrategyState state = session.getState();
                if (state == StrategyState.IN_POSITION || state == StrategyState.WAITING_FOR_CANDLES) {
                    log.warn("CRASH RECOVERY [{}]: restoring session {} (state={})",
                            username, session.getSessionId(), state);

                    userRegistry.findByUsername(username).ifPresentOrElse(
                            user -> {
                                UserTradingEngine engine = buildEngine(user);
                                engine.restoreSession(session);
                                engines.put(username, engine);
                                log.info("Recovery [{}]: engine restored", username);
                            },
                            () -> log.warn("Recovery [{}]: user no longer exists — skipping", username)
                    );
                } else {
                    log.info("Recovery [{}]: session state={} (terminal) — clearing file", username, state);
                    sessionPersistence.clearForUser(username);
                }
            });
        }
    }

    public UserTradingEngine startEngine(PlatformUser user,
                                          TradingConfig config,
                                          Map<Long, String> instruments,
                                          String startedBy) {
        String username = user.getUsername();

        UserTradingEngine stale = engines.get(username);
        if (stale != null && !stale.isActive()) {
            stale.disconnectKiteTicker();
            engines.remove(username);
        }

        UserTradingEngine engine = buildEngine(user);

        engine.subscribeInstruments(instruments);
        engine.startSession(config, instruments, startedBy);
        engines.put(username, engine);

        log.info("Engine started for [{}] (mode={}, lots={})", username, config.getTradeMode(), config.getLotQuantity());
        return engine;
    }

    public Optional<TradeSession> stopEngine(String username) {
        UserTradingEngine engine = engines.get(username);
        if (engine == null) return Optional.empty();

        TradeSession stopped = engine.stopSession();
        engine.disconnectKiteTicker();
        log.info("Engine stopped for [{}] — kept in registry for P&L display", username);
        return Optional.of(stopped);
    }

    public void clearStoppedEngine(String username) {
        UserTradingEngine engine = engines.get(username);
        if (engine != null && !engine.isActive()) {
            engines.remove(username);
            log.info("Cleared stopped engine for [{}]", username);
        }
    }

    public Optional<UserTradingEngine> getEngine(String username) {
        return Optional.ofNullable(engines.get(username));
    }

    public boolean hasActiveEngine(String username) {
        UserTradingEngine engine = engines.get(username);
        return engine != null && engine.isActive();
    }

    public Map<String, UserTradingEngine> getAllEngines() {
        return Collections.unmodifiableMap(engines);
    }

    private UserTradingEngine buildEngine(PlatformUser user) {
        return new UserTradingEngine(
                telegramService,
                user,
                kiteConnect,
                globalCache,
                optionInstrumentService,
                kiteInstrumentService,
                kiteTickerService,
                messagingTemplate,
                sessionPersistence);
    }

    public void routeTickToActiveEngines(MarketTick tick) {
        engines.values().forEach(engine -> {
            TradeSession s = engine.getSession();
            if (s == null || s.getConfig() == null) return;
            if (s.getState() == StrategyState.STOPPED || s.getState() == StrategyState.IDLE) return;
            engine.processTick(tick);
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

    public void forceStopEngine(String username) {
        stopEngine(username);
        log.info("Admin force-stopped engine for [{}]", username);
    }
}
