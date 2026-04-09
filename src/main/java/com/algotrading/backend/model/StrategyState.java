package com.algotrading.backend.model;

public enum StrategyState {
    IDLE,                    // Strategy not started
    WAITING_FOR_CANDLES,     // Collecting 1st, 2nd, 3rd candles
    IN_POSITION,             // Active trade open
    STOPPED                  // Exited: target/SL/max reversals exhausted
}
