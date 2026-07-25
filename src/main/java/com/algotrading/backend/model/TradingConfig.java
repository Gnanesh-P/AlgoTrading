package com.algotrading.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

// Computed getters (getTotalQuantity(), isBankNifty()) are picked up by Jackson as JSON
// properties on serialization but have no backing field/setter — without ignoreUnknown,
// SessionPersistenceService's strict ObjectMapper throws on reload and silently wipes the
// persisted session (crash-recovery for an in-flight strategy would otherwise be lost on
// every server restart).
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingConfig {

    public static final int NIFTY_LOT_SIZE = 65;
    public static final int BANKNIFTY_LOT_SIZE = 30;

    private String futuresInstrument;
    private String ceInstrument;
    private String peInstrument;
    private int ceStrikePrice;
    private int peStrikePrice;
    private ExpiryType expiryType;

    private LocalTime startCandleTime;

    private StrikeMode strikeMode;

    private int lotQuantity;
    private double targetProfit;
    private double stopLoss;
    private int maxReversals;

    private double trailingProfit;

    private boolean squareOffEod;

    private TradeMode tradeMode;

    // Strategy key this config belongs to (NIFTY_SCALP / BANKNIFTY_SCALP / NIFTY_BREAKOUT)
    private StrategyKey strategyKey;

    // BUY (long options) or SELL (write/short options). Default BUY preserves legacy behavior.
    @Builder.Default
    private TradeDirection tradeDirection = TradeDirection.BUY;

    // Strategy 3 only: whether reversal between CE/PE legs is allowed. Ignored by Strategy 1/2
    // (which always use maxReversals directly, unbounded by this flag).
    private boolean reversalEnabled;

    /**
     * Whether this config is a Bank Nifty strategy. Prefers the authoritative {@link #strategyKey}
     * (always reliably set by AlgoController from which card the user started) over sniffing the
     * futuresInstrument text for "BANKNIFTY" — that text-based check silently fell back to NIFTY
     * whenever the futures dropdown hadn't resolved a real tradingsymbol yet (e.g. Kite not yet
     * connected, so the dropdown only offered the synthetic spot-index placeholder "NIFTY BANK",
     * which does NOT contain the substring "BANKNIFTY"). strategyKey has no such race condition.
     */
    public boolean isBankNifty() {
        if (strategyKey != null) {
            return strategyKey == StrategyKey.BANKNIFTY_SCALP || strategyKey == StrategyKey.BANKNIFTY_BREAKOUT;
        }
        return futuresInstrument != null && futuresInstrument.toUpperCase().contains("BANKNIFTY");
    }

    public int lotSizeForInstrument() {
        return isBankNifty() ? BANKNIFTY_LOT_SIZE : NIFTY_LOT_SIZE;
    }

    public int getTotalQuantity() {
        return lotQuantity * lotSizeForInstrument();
    }
}
