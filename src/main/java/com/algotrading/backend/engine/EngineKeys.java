package com.algotrading.backend.engine;

import com.algotrading.backend.model.StrategyKey;

/**
 * Builds the composite (username, strategy) key used to key engines in
 * TradingEngineRegistry and to namespace per-user persistence files in
 * SessionPersistenceService (which sanitizes any string into a safe filename).
 */
public final class EngineKeys {

    private static final String SEPARATOR = "::";

    private EngineKeys() {
    }

    public static String of(String username, StrategyKey strategyKey) {
        return username + SEPARATOR + strategyKey.name();
    }

    public static String usernameOf(String compositeKey) {
        int idx = compositeKey.indexOf(SEPARATOR);
        return idx >= 0 ? compositeKey.substring(0, idx) : compositeKey;
    }

    public static StrategyKey strategyKeyOf(String compositeKey) {
        int idx = compositeKey.indexOf(SEPARATOR);
        if (idx < 0) return null;
        try {
            return StrategyKey.valueOf(compositeKey.substring(idx + SEPARATOR.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
