package com.algotrading.backend.broker;

import com.algotrading.backend.cache.MarketDataCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Paper trading implementation — uses live market tick prices but does not
 * place real orders. Fills at the current LTP.
 */
@Service("paperBrokerService")
@RequiredArgsConstructor
@Slf4j
public class PaperBrokerService implements BrokerService {

    private final MarketDataCache cache;

    @Override
    public double placeBuyOrder(String instrument, int quantity) {
        double ltp = getLtp(instrument);
        log.info("[PAPER] BUY {} x{} @ {}", instrument, quantity, ltp);
        return ltp;
    }

    @Override
    public double placeSellOrder(String instrument, int quantity) {
        double ltp = getLtp(instrument);
        log.info("[PAPER] SELL {} x{} @ {}", instrument, quantity, ltp);
        return ltp;
    }

    @Override
    public double getLtp(String instrument) {
        double price = cache.getLastPrice(instrument);
        if (price <= 0) {
            log.warn("No tick data for {}. Using 0.", instrument);
        }
        return price;
    }

    @Override
    public boolean isPaper() {
        return true;
    }

    @Override
    public boolean hasOpenPosition(String instrument) {
        // Paper trading tracks positions internally — always treat as open when the leg is not yet closed.
        return true;
    }
}
