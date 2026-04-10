package com.algotrading.backend.engine;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.model.*;
import com.algotrading.backend.service.KiteInstrumentService;
import com.algotrading.backend.service.OptionInstrumentService;
import com.algotrading.backend.service.SessionPersistenceService;
import com.algotrading.backend.service.UserRegistryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry that manages one {@link UserTradingEngine} per active user.
 *
 * Multiple engines run concurrently — each on the same JVM thread pool —
 * each owning its own KiteConnect + KiteTicker + session + candle state.
 *
 * Responsibilities:
 *  • Create / destroy engines on start/stop requests.
 *  • Route global paper-mode ticks to all paper engines.
 *  • Run EOD square-off across all active engines (@Scheduled).
 *  • Crash recovery: restore persisted sessions on app startup (@PostConstruct).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradingEngineRegistry {

    /** username → active engine */
    private final Map<String, UserTradingEngine> engines = new ConcurrentHashMap<>();

    // ── Shared deps (injected into each engine at creation) ───────────────────
    private final MarketDataCache         globalCache;
    private final OptionInstrumentService optionInstrumentService;
    private final KiteInstrumentService   kiteInstrumentService;
    private final SimpMessagingTemplate   messagingTemplate;
    private final SessionPersistenceService sessionPersistence;
    private final UserRegistryService     userRegistry;

    // ═══════════════════════════════════════════════════════════════════════
    //  CRASH RECOVERY
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * On startup, scan for any per-user session files in ./data/sessions/ and
     * restore engines that were IN_POSITION or WAITING_FOR_CANDLES when the
     * server last went down.
     */
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
                    log.warn("⚠️  CRASH RECOVERY [{}]: restoring session {} (state={})",
                            username, session.getSessionId(), state);

                    userRegistry.findByUsername(username).ifPresentOrElse(
                            user -> {
                                UserTradingEngine engine = buildEngine(user);
                                engine.restoreSession(session);

                                // Re-connect live ticker if applicable
                                if (session.getConfig().getTradeMode() == TradeMode.LIVE) {
                                    engine.connectKiteTicker();
                                }
                                engines.put(username, engine);
                                log.info("Recovery [{}]: engine restored", username);
                            },
                            () -> log.warn("Recovery [{}]: user no longer exists in registry — skipping", username)
                    );
                } else {
                    // STOPPED/IDLE — clean up stale file
                    log.info("Recovery [{}]: session state={} (terminal) — clearing file", username, state);
                    sessionPersistence.clearForUser(username);
                }
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ENGINE LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Create and start a new trading engine for the given user.
     *
     * Pre-conditions (validated by AlgoController before calling):
     *  • Plan is active, lot limit not exceeded.
     *  • No existing ACTIVE engine for this user.
     *
     * @param user        the PlatformUser starting the algo
     * @param config      fully-built TradingConfig
     * @param instruments token→symbol map to subscribe (futures + CE/PE for MANUAL)
     * @param startedBy   JWT username (usually == user.getUsername())
     * @return the started engine
     */
    public UserTradingEngine startEngine(PlatformUser user,
                                          TradingConfig config,
                                          Map<Long, String> instruments,
                                          String startedBy) {
        String username = user.getUsername();

        // If there's a stale stopped engine, remove it
        UserTradingEngine stale = engines.get(username);
        if (stale != null && !stale.isActive()) {
            stale.disconnectKiteTicker();
            engines.remove(username);
        }

        UserTradingEngine engine = buildEngine(user);

        // For LIVE mode: connect the user's own KiteTicker BEFORE starting session
        if (config.getTradeMode() == TradeMode.LIVE) {
            engine.connectKiteTicker();
        }

        // Subscribe instruments to the engine (live: queued for ticker; paper: stored for routing)
        engine.subscribeInstruments(instruments);

        engine.startSession(config, instruments, startedBy);
        engines.put(username, engine);

        log.info("Engine started for [{}] (mode={}, lots={})", username, config.getTradeMode(), config.getLotQuantity());
        return engine;
    }

    /**
     * Stop the engine for a user.
     * Exits any open position, disconnects ticker, removes from registry.
     */
    public Optional<TradeSession> stopEngine(String username) {
        UserTradingEngine engine = engines.get(username);
        if (engine == null) return Optional.empty();

        TradeSession stopped = engine.stopSession();
        engine.disconnectKiteTicker();
        engines.remove(username);
        log.info("Engine stopped and removed for [{}]", username);
        return Optional.of(stopped);
    }

    /** @return the engine for a user, if active. */
    public Optional<UserTradingEngine> getEngine(String username) {
        return Optional.ofNullable(engines.get(username));
    }

    /** @return true if the user has an active (WAITING or IN_POSITION) engine. */
    public boolean hasActiveEngine(String username) {
        UserTradingEngine engine = engines.get(username);
        return engine != null && engine.isActive();
    }

    /** All currently registered engines (active or recently stopped). */
    public Map<String, UserTradingEngine> getAllEngines() {
        return Collections.unmodifiableMap(engines);
    }

    /** Build a new engine wired with shared dependencies for the given user. */
    private UserTradingEngine buildEngine(PlatformUser user) {
        return new UserTradingEngine(
                user,
                globalCache,
                optionInstrumentService,
                kiteInstrumentService,
                messagingTemplate,
                sessionPersistence);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TICK ROUTING (paper mode)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called by MarketDataService on every global tick.
     * Routes the tick to all PAPER-mode engines whose session includes this instrument.
     * LIVE engines receive ticks directly from their own KiteTicker.
     */
    public void routeTickToPaperEngines(MarketTick tick) {
        engines.values().forEach(engine -> {
            TradeSession s = engine.getSession();
            if (s == null || s.getConfig() == null) return;
            if (s.getConfig().getTradeMode() != TradeMode.PAPER) return;
            if (s.getState() == StrategyState.STOPPED || s.getState() == StrategyState.IDLE) return;
            engine.processTick(tick);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  EOD SCHEDULED JOB
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * At 15:29 Monday–Friday, trigger EOD square-off for every active engine
     * that has squareOffEod=true in its config.
     */
    @Scheduled(cron = "0 29 15 * * MON-FRI")
    public void squareOffAllEnginesEod() {
        log.info("EOD 15:29 — running square-off for {} active engines", engines.size());
        engines.values().forEach(engine -> {
            try {
                engine.squareOffEod();
            } catch (Exception e) {
                log.error("EOD square-off error for [{}]: {}", engine.getUsername(), e.getMessage());
            }
        });
        // Remove stopped engines
        engines.entrySet().removeIf(e -> !e.getValue().isActive());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ADMIN / STATUS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Summary of all active sessions — used by admin panel.
     */
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

    /**
     * Force-stop any session for a given user — admin override.
     */
    public void forceStopEngine(String username) {
        stopEngine(username);
        log.info("Admin force-stopped engine for [{}]", username);
    }
}
