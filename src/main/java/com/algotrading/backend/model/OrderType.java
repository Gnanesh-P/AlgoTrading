package com.algotrading.backend.model;

// Breakout/Breakout V2 only (see BreakoutStrategyEngine/BreakoutV2StrategyEngine) — Scalping
// always places LIMIT orders and ignores this field entirely.
public enum OrderType {
    MARKET,
    LIMIT
}
