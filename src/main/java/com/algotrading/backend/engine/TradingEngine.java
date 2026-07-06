package com.algotrading.backend.engine;

import com.algotrading.backend.dto.AlgoStatusResponse;
import com.algotrading.backend.model.MarketTick;
import com.algotrading.backend.model.StrategyKey;
import com.algotrading.backend.model.TradeSession;
import com.algotrading.backend.model.TradingConfig;

import java.util.Map;

public interface TradingEngine {
    String getUsername();
    StrategyKey getStrategyKey();
    boolean isActive();
    boolean isTickerConnected();
    TradeSession startSession(TradingConfig config, Map<Long, String> instruments, String startedBy);
    void processTick(MarketTick tick);
    void squareOffEod();
    TradeSession stopSession();
    void disconnectKiteTicker();
    void subscribeInstruments(Map<Long, String> instruments);
    void restoreSession(TradeSession restored);
    void updateParams(double targetPrice, double stopLoss, boolean stopLossEnabled, double trailingProfit);
    void updateKiteAccessToken(String newToken);
    TradeSession getSession();
    AlgoStatusResponse buildStatusResponse();
}
