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
    // NSE/BSE revise F&O lot sizes periodically (quarterly review) — verify this against your
    // broker/the current NSE circular before running SENSEX live. Best known value at time of writing.
    public static final int SENSEX_LOT_SIZE = 20;

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

    // NIFTY Breakout V2 only: points below the reference-candle high/low to wait for on
    // retest before entering (the "215 = High(220) - 5" retest offset), and the extra run-up
    // beyond the reference level before the whole session is abandoned as a runaway ("15 points").
    @Builder.Default
    private double breakoutPoints = 5.0;
    @Builder.Default
    private double maxChasePoints = 15.0;

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

    /** SENSEX trades on the BSE (BFO segment), unlike NIFTY/BANKNIFTY which are NSE (NFO). */
    public boolean isSensex() {
        if (strategyKey != null) {
            return strategyKey == StrategyKey.SENSEX_SCALP || strategyKey == StrategyKey.SENSEX_BREAKOUT;
        }
        return futuresInstrument != null && futuresInstrument.toUpperCase().contains("SENSEX");
    }

    public int lotSizeForInstrument() {
        if (isSensex()) return SENSEX_LOT_SIZE;
        return isBankNifty() ? BANKNIFTY_LOT_SIZE : NIFTY_LOT_SIZE;
    }

    /**
     * Kite exchange segment for this config's instruments — "BFO" for SENSEX (BSE F&O),
     * "NFO" for everything else (NSE F&O). Used for both order placement (OrderParams.exchange)
     * and REST quote-key prefixes ("NFO:SYMBOL" / "BFO:SYMBOL"), since the string value happens
     * to match com.zerodhatech.kiteconnect.utils.Constants.EXCHANGE_NFO/EXCHANGE_BFO exactly.
     */
    public String exchangeSegment() {
        return isSensex() ? "BFO" : "NFO";
    }

    public int getTotalQuantity() {
        return lotQuantity * lotSizeForInstrument();
    }
}
