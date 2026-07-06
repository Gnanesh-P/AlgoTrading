package com.algotrading.backend.broker;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.config.KiteProperties;
import com.algotrading.backend.service.KiteErrorUtil;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Live broker using the KiteConnect Java SDK.
 *
 * All order/LTP/cancel calls go through the SDK — no manual RestTemplate wiring.
 * The SDK handles auth headers, request encoding, and response parsing internally.
 *
 * Orders: NFO exchange, MARKET type, MIS (intraday) product, DAY validity.
 */
@Service("liveBrokerService")
@RequiredArgsConstructor
@Slf4j
public class LiveBrokerService implements BrokerService {

    private final MarketDataCache cache;
    private final KiteProperties  kite;
    private final KiteConnect     kiteConnect;   // access token already set by KiteAuthService

    // ---- BrokerService interface ----

    @Override
    public double placeBuyOrder(String instrument, int quantity) {
        log.info("[LIVE] BUY {} x{}", instrument, quantity);
        String orderId = placeOrder(instrument, quantity, Constants.TRANSACTION_TYPE_BUY);
        log.info("[LIVE] BUY done. orderId={}", orderId);
        return getLtp(instrument);
    }

    @Override
    public double placeSellOrder(String instrument, int quantity) {
        log.info("[LIVE] SELL {} x{}", instrument, quantity);
        String orderId = placeOrder(instrument, quantity, Constants.TRANSACTION_TYPE_SELL);
        log.info("[LIVE] SELL done. orderId={}", orderId);
        return getLtp(instrument);
    }

    /**
     * Returns LTP for an instrument.
     * Checks the in-memory KiteTicker cache first (zero latency),
     * falls back to SDK getLTP() call when cache is cold (e.g. right after algo start).
     */
    @Override
    public double getLtp(String instrument) {
        double cached = cache.getLastPrice(instrument);
        if (cached > 0) return cached;

        if (!kite.isConnected()) return 0;
        try {
            Map<String, LTPQuote> ltpMap = kiteConnect.getLTP(new String[]{"NFO:" + instrument});
            LTPQuote quote = ltpMap != null ? ltpMap.get("NFO:" + instrument) : null;
            return quote != null ? quote.lastPrice : 0;
        } catch (Exception | KiteException e) {
            log.warn("[LIVE] LTP fetch failed for {}: {}", instrument, e.getMessage());
            return 0;
        }
    }

    @Override
    public boolean isPaper() {
        return false;
    }

    /**
     * Checks Kite's net positions to see if we still hold an open position (long OR short) for
     * this instrument. Returns false if the position was already squared off manually via Kite
     * or another terminal.
     *
     * Uses != 0 (not > 0) so short (written) legs — which carry negative netQuantity — are also
     * detected as an open position, not just long legs. This mirrors the direction-aware check
     * each engine already performs internally via its own KiteConnect positions call.
     */
    @Override
    public boolean hasOpenPosition(String instrument) {
        Map<String, List<Position>> positions = getPositions();
        List<Position> netPositions = positions.get("net");
        if (netPositions == null || netPositions.isEmpty()) return false;
        return netPositions.stream()
                .anyMatch(p -> instrument.equalsIgnoreCase(p.tradingSymbol) && p.netQuantity != 0);
    }

    // ---- Order management ----

    /**
     * Places a MARKET MIS order via KiteConnect SDK.
     * Returns the order ID returned by Kite.
     */
    private static final int MAX_ORDER_ATTEMPTS = 3;

    private String placeOrder(String instrument, int quantity, String transactionType) {
        if (!kite.isConnected()) {
            throw new IllegalStateException("Kite not connected — cannot place " + transactionType + " order");
        }
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                OrderParams params = new OrderParams();
                params.tradingsymbol   = instrument;
                params.exchange        = Constants.EXCHANGE_NFO;
                params.transactionType = transactionType;
                params.orderType       = Constants.ORDER_TYPE_MARKET;
                params.quantity        = quantity;
                params.product         = Constants.PRODUCT_MIS;   // intraday
                params.validity        = Constants.VALIDITY_DAY;

                OrderResponse order = kiteConnect.placeOrder(params, Constants.VARIETY_REGULAR);
                log.info("[LIVE] {} {} x{} → orderId={} status={}",
                        transactionType, instrument, quantity, order.orderId, order.orderId);
                return order.orderId;
            } catch (Exception | KiteException e) {
                if (KiteErrorUtil.isSessionExpired(e)) {
                    log.error("[LIVE] placeOrder [{} {} x{}] failed — Kite session expired: {}",
                            transactionType, instrument, quantity, e.getMessage());
                    throw new IllegalStateException(KiteErrorUtil.sessionExpiredMessage());
                }
                if (KiteErrorUtil.shouldRetry(e, attempt, MAX_ORDER_ATTEMPTS,
                        "[LIVE] " + transactionType + " " + instrument, log)) {
                    continue;
                }
                String cause = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("[LIVE] placeOrder failed [{} {} x{}]: {}", transactionType, instrument, quantity, cause, e);
                throw new RuntimeException("Order failed: " + cause, e);
            }
        }
    }

    /**
     * Cancels an open order by order ID.
     */
    public String cancelOrder(String orderId) {
        if (!kite.isConnected()) throw new IllegalStateException("Kite not connected");
        try {
            Order order = kiteConnect.cancelOrder(orderId, Constants.VARIETY_REGULAR);
            log.info("[LIVE] Cancelled orderId={}", orderId);
            return order.orderId;
        } catch (Exception | KiteException e) {
            log.error("[LIVE] cancelOrder failed for {}: {}", orderId, e.getMessage());
            throw new RuntimeException("Cancel failed: " + e.getMessage(), e);
        }
    }

    /**
     * Returns current net and day positions from Kite.
     * Map keys: "net" and "day", each containing a List<Position>.
     */
    public Map<String, List<Position>> getPositions() {
        if (!kite.isConnected()) return Map.of();
        try {
            return kiteConnect.getPositions();
        } catch (Exception | KiteException e) {
            log.warn("[LIVE] getPositions failed: {}", e.getMessage());
            return Map.of();
        }
    }
}
