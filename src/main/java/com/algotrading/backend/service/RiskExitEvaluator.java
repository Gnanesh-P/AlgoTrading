package com.algotrading.backend.service;

import com.algotrading.backend.model.TradeSession;
import com.algotrading.backend.model.TradingConfig;
import org.springframework.stereotype.Service;

/**
 * Shared SL/Target/Trailing evaluation, used by every strategy engine so the
 * risk-exit rules behave identically regardless of which strategy is running.
 */
@Service
public class RiskExitEvaluator {

    public enum ExitDecision { NONE, TARGET, STOPLOSS, TRAILING_STOP }

    /**
     * Evaluates SL/Target/Trailing against the given live (open) leg P&L.
     * Mutates session.openPnL / trailingActive / trailingHighWatermark as a side effect,
     * mirroring the original inline logic this replaces.
     */
    public ExitDecision evaluate(TradeSession session, double liveLegPnL) {
        TradingConfig cfg = session.getConfig();
        double totalPnL = session.getTotalRealizedPnL() + liveLegPnL;
        session.setOpenPnL(liveLegPnL);

        if (cfg.getStopLoss() > 0 && totalPnL <= -cfg.getStopLoss()) {
            return ExitDecision.STOPLOSS;
        }

        double trailing = cfg.getTrailingProfit();

        if (trailing <= 0) {
            return totalPnL >= cfg.getTargetProfit() ? ExitDecision.TARGET : ExitDecision.NONE;
        }

        if (!session.isTrailingActive() && totalPnL >= cfg.getTargetProfit()) {
            session.setTrailingActive(true);
            session.setTrailingHighWatermark(totalPnL);
        }

        if (session.isTrailingActive()) {
            if (totalPnL > session.getTrailingHighWatermark()) {
                session.setTrailingHighWatermark(totalPnL);
            }
            double exitLevel = session.getTrailingHighWatermark() - trailing;
            if (totalPnL <= exitLevel) {
                return ExitDecision.TRAILING_STOP;
            }
        }

        return ExitDecision.NONE;
    }
}
