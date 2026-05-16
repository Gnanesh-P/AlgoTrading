package com.algotrading.backend.service;

import com.algotrading.backend.model.TradeSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Optional;

/**
 * Persists the active TradeSession to a JSON file after every state change.
 *
 * Why this matters:
 *   The trading session lives in memory. If the server crashes (power cut, OOM,
 *   Render restart, network blip) while a position is open in Zerodha, the in-memory
 *   state is gone but the REAL position in Zerodha is still there — exposed to market
 *   risk with no monitoring.
 *
 *   This service writes session.json on every state change. On restart, the application
 *   loads the file and resumes monitoring the open position as if nothing happened.
 *
 * File path:
 *   Default: ./data/session.json
 *   Override: SESSION_FILE env var   (e.g. /data/session.json on Render persistent disk)
 *   Or:       app.session-file in application.yml
 *
 * Recovery flow:
 *   1. App starts → TradingStrategyService.recoverSession() is called (@PostConstruct).
 *   2. If session.json exists AND state was IN_POSITION:
 *      a. Session is restored into MarketDataCache.
 *      b. KiteTicker subscriptions are re-initiated for the locked instruments.
 *      c. Strategy continues monitoring — the next candle close will trigger exit checks.
 *   3. UI shows state = RUNNING with "(RECOVERED)" in the stop reason field until normal exit.
 *   4. When user clicks Stop or the session ends normally, session.json is deleted.
 */
@Service
@Slf4j
public class SessionPersistenceService {

    @Value("${app.session-file:/app/data/session.json}")
    private String sessionFilePath;

    @Value("${app.user-session-file:./data/session/}")
    private String userSessionPath;

    private final ObjectMapper mapper;

    public SessionPersistenceService() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.findAndRegisterModules();
        this.mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Save session to disk. Called after every state change in TradingStrategyService.
     * Fire-and-forget: errors are logged but never thrown (must not interrupt trading).
     */
    public void save(TradeSession session) {
        if (session == null) return;
        try {
            File file = new File(sessionFilePath);
            file.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, session);
            log.debug("Session persisted: state={} sessionId={}", session.getState(), session.getSessionId());
        } catch (Exception e) {
            // Never let persistence failure kill the trading loop
            log.error("Failed to persist session (trading continues): {}", e.getMessage());
        }
    }

    /**
     * Load a previously saved session from disk.
     * Returns empty if no file exists or the session was in a terminal state.
     */
    public Optional<TradeSession> load() {
        File file = new File(sessionFilePath);
        if (!file.exists()) return Optional.empty();
        try {
            TradeSession session = mapper.readValue(file, TradeSession.class);
            if (session == null) return Optional.empty();
            log.info("Loaded persisted session: id={} state={} startedBy={}",
                    session.getSessionId(), session.getState(), session.getStartedBy());
            return Optional.of(session);
        } catch (Exception e) {
            log.error("Failed to load session.json (starting fresh): {}", e.getMessage());
            clear(); // corrupt file — delete it
            return Optional.empty();
        }
    }

    /**
     * Delete the persisted session file after clean stop/completion.
     */
    public void clear() {
        File file = new File(sessionFilePath);
        if (file.exists() && file.delete()) {
            log.info("Persisted session file cleared: {}", sessionFilePath);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Per-user persistence (multi-tenant engine: ./data/sessions/{username}.json)
    // ────────────────────────────────────────────────────────────────────────

    private File userSessionFile(String username) {
        return new File(userSessionPath + sanitize(username) + ".json");
    }

    /** Sanitise username so it is safe to use as a file-name component. */
    private String sanitize(String username) {
        return username.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /** Persist a user's session to ./data/sessions/{username}.json. */
    public void saveForUser(String username, TradeSession session) {
        if (session == null) return;
        try {
            File file = userSessionFile(username);
            file.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, session);
            log.debug("Per-user session persisted: user={} state={}", username, session.getState());
        } catch (Exception e) {
            log.error("Failed to persist session for user {} (trading continues): {}", username, e.getMessage());
        }
    }

    /** Load a user's previously saved session from disk. */
    public java.util.Optional<TradeSession> loadForUser(String username) {
        File file = userSessionFile(username);
        if (!file.exists()) return java.util.Optional.empty();
        try {
            TradeSession session = mapper.readValue(file, TradeSession.class);
            if (session == null) return java.util.Optional.empty();
            log.info("Loaded per-user session: user={} id={} state={}", username, session.getSessionId(), session.getState());
            return java.util.Optional.of(session);
        } catch (Exception e) {
            log.error("Failed to load session for user {} (starting fresh): {}", username, e.getMessage());
            clearForUser(username);
            return java.util.Optional.empty();
        }
    }

    /** Delete a user's persisted session file after intentional stop. */
    public void clearForUser(String username) {
        File file = userSessionFile(username);
        if (file.exists() && file.delete()) {
            log.info("Per-user session file cleared: {}", file.getPath());
        }
    }

    /**
     * Scan ./data/sessions/ and return the usernames of all files found.
     * Used by TradingEngineRegistry.recoverAllSessions() on startup.
     */
    public java.util.List<String> findAllPersistedUsernames() {
        File dir = new File(userSessionPath);
        if (!dir.exists() || !dir.isDirectory()) return java.util.List.of();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return java.util.List.of();
        return java.util.Arrays.stream(files)
                .map(f -> f.getName().replace(".json", ""))
                .toList();
    }
}
