package com.algotrading.backend.model;

public enum StrategyKey {
    NIFTY_SCALP,
    BANKNIFTY_SCALP,
    NIFTY_BREAKOUT,
    BANKNIFTY_BREAKOUT,
    SENSEX_SCALP,
    SENSEX_BREAKOUT;

    /** Friendly name shown at the top of every Telegram alert so concurrent algos are distinguishable. */
    public String displayName() {
        return switch (this) {
            case NIFTY_SCALP -> "NIFTY Scalping";
            case BANKNIFTY_SCALP -> "Bank Nifty Scalping";
            case NIFTY_BREAKOUT -> "NIFTY Breakout";
            case BANKNIFTY_BREAKOUT -> "Bank Nifty Breakout";
            case SENSEX_SCALP -> "Sensex Scalping";
            case SENSEX_BREAKOUT -> "Sensex Breakout";
        };
    }
}
