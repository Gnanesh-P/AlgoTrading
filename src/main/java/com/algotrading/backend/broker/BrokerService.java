package com.algotrading.backend.broker;

import com.algotrading.backend.model.TradeEntry;

/**
 * Abstraction for broker order execution.
 * Implementations: PaperBrokerService (default), LiveBrokerService.
 */
public interface BrokerService {

    /**
     * Place a BUY order for options.
     * @return actual fill price
     */
    double placeBuyOrder(String instrument, int quantity);

    /**
     * Place a SELL (exit) order for options.
     * @return actual exit price
     */
    double placeSellOrder(String instrument, int quantity);

    /**
     * Get current LTP of the instrument from broker.
     */
    double getLtp(String instrument);

    /**
     * Whether this is paper trading.
     */
    boolean isPaper();

    /**
     * Returns true if there is an open (net positive) position for the given instrument.
     * Used before placing a sell order to avoid double-selling a position already
     * closed manually via Kite or another terminal.
     */
    boolean hasOpenPosition(String instrument);
}
