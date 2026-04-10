package com.algotrading.backend.controller;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.config.KiteProperties;
import com.algotrading.backend.dto.OptionChainResponse;
import com.algotrading.backend.engine.TradingEngineRegistry;
import com.algotrading.backend.model.ExpiryType;
import com.algotrading.backend.model.KiteInstrument;
import com.algotrading.backend.service.KiteAuthService;
import com.algotrading.backend.service.KiteInstrumentService;
import com.algotrading.backend.service.KiteTickerService;
import com.algotrading.backend.service.UserRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.security.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kite")
@RequiredArgsConstructor
@Slf4j
public class KiteController {

    private final KiteAuthService kiteAuthService;
    private final KiteInstrumentService instrumentService;
    private final KiteTickerService tickerService;
    private final KiteProperties kiteProperties;
    private final MarketDataCache cache;
    private final UserRegistryService userRegistry;
    private final TradingEngineRegistry engineRegistry;

    // ---- Authentication ----

    /** Returns the Kite login URL to open in browser */
    @GetMapping("/login-url")
    public ResponseEntity<Map<String, String>> getLoginUrl() {
        return ResponseEntity.ok(Map.of("loginUrl", kiteAuthService.getLoginUrl()));
    }

    /**
     * OAuth callback from Kite. Kite redirects here after user authorizes.
     * Exchanges request_token for access_token, then redirects to the main UI.
     */
    @GetMapping("/callback")
    public RedirectView callback(@RequestParam("request_token") String requestToken,
                                  @RequestParam(value = "action", required = false) String action,
                                  @RequestParam(value = "status", required = false) String status) {
        try {
            kiteAuthService.exchangeToken(requestToken);
            log.info("Kite callback successful");
            return new RedirectView("/?kite=connected");
        } catch (Exception e) {
            log.error("Kite callback error: {}", e.getMessage());
            return new RedirectView("/?kite=error&message=" + e.getMessage());
        }
    }

    /** Manually set access token (if already have one) */
    @PostMapping("/access-token")
    public ResponseEntity<Map<String, Object>> setAccessToken(@RequestBody Map<String, String> body) {
        String token = body.get("accessToken");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "accessToken is required"));
        }
        kiteAuthService.setAccessToken(token);
        return ResponseEntity.ok(Map.of("status", "connected", "message", "Access token set successfully"));
    }

    /**
     * Per-user Kite access token update.
     *
     * Each subscriber has their own Zerodha account. After completing their daily Kite
     * OAuth login they must update their token here so the engine can place live orders.
     *
     * This endpoint:
     *  1. Saves the new token into the user's PlatformUser record (users.json).
     *  2. Updates the token in the user's running engine (if any) so live orders work immediately.
     *
     * Users update their OWN token. Admins can update any user's token.
     */
    @PostMapping("/my-access-token")
    public ResponseEntity<Map<String, Object>> setMyAccessToken(
            @RequestBody Map<String, String> body,
            Principal principal) {

        String username = principal != null ? principal.getName() : null;
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        String token = body.get("accessToken");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "accessToken is required"));
        }

        // 1. Persist to users.json
        userRegistry.updateAccessToken(username, token);
        log.info("[{}] Kite access token updated via /my-access-token", username);

        // 2. Update the running engine if one exists (hot-reload token)
        engineRegistry.getEngine(username).ifPresent(engine -> engine.updateKiteAccessToken(token));

        return ResponseEntity.ok(Map.of(
                "status",  "token_updated",
                "message", "Kite access token saved. Your engine will use it for the next order.",
                "user",    username));
    }

    /** Get Kite connection status */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "connected", kiteProperties.isConnected(),
                "apiKey", kiteProperties.getApiKey(),
                "tickerActive", tickerService.isActive(),
                "subscribedInstruments", tickerService.getSubscribedInstruments()
        ));
    }

    // ---- Instruments ----

    /** Get all NIFTY futures instruments (for dropdown selection) */
    @GetMapping("/instruments/futures")
    public ResponseEntity<List<KiteInstrument>> getNiftyFutures() {
        return ResponseEntity.ok(instrumentService.getNiftyFutures());
    }

    /**
     * Get NIFTY options for a given expiry type.
     * Filtered to ±400 strikes from current ATM.
     * Pass ?niftyPrice=22000 if you want to override the cached price.
     */
    @GetMapping("/instruments/options")
    public ResponseEntity<OptionChainResponse> getNiftyOptions(
            @RequestParam(defaultValue = "CURRENT_WEEK") ExpiryType expiryType,
            @RequestParam(required = false) String futuresSymbol,
            @RequestParam(required = false) Double niftyPrice) {

        String symbol = futuresSymbol;
        double price = niftyPrice != null ? niftyPrice : cache.getLastPrice(symbol);
        if (price <= 0) price = 22000;  // fallback default

        OptionChainResponse chain = kiteProperties.isConnected()
                ? instrumentService.buildKiteOptionChain(symbol, price, expiryType)
                : buildFallbackChain(symbol, price, expiryType);

        return ResponseEntity.ok(chain);
    }

    /** Get options for BOTH current and next week in one call */
    @GetMapping("/instruments/options/all")
    public ResponseEntity<Map<String, Object>> getAllOptions(
            @RequestParam(required = false) String futuresSymbol,
            @RequestParam(required = false) Double niftyPrice) {

        String symbol = futuresSymbol != null ? futuresSymbol : "NIFTY25APRFUT";
        double price = niftyPrice != null ? niftyPrice : cache.getLastPrice(symbol);
        if (price <= 0) price = 22000;

        OptionChainResponse currentWeek = kiteProperties.isConnected()
                ? instrumentService.buildKiteOptionChain(symbol, price, ExpiryType.CURRENT_WEEK)
                : buildFallbackChain(symbol, price, ExpiryType.CURRENT_WEEK);

        OptionChainResponse nextWeek = kiteProperties.isConnected()
                ? instrumentService.buildKiteOptionChain(symbol, price, ExpiryType.NEXT_WEEK)
                : buildFallbackChain(symbol, price, ExpiryType.NEXT_WEEK);

        Map<String, LocalDate> expiryDates = kiteProperties.isConnected()
                ? instrumentService.getExpiryDates()
                : Map.of("CURRENT_WEEK", getNextTuesday(), "NEXT_WEEK", getNextTuesday().plusWeeks(1));

        return ResponseEntity.ok(Map.of(
                "currentWeek", currentWeek,
                "nextWeek", nextWeek,
                "expiryDates", expiryDates,
                "niftyPrice", price
        ));
    }

    /** Refresh instrument cache manually */
    @PostMapping("/instruments/refresh")
    public ResponseEntity<Map<String, Object>> refreshInstruments() {
        if (!kiteProperties.isConnected()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Kite not connected"));
        }
        int count = instrumentService.refreshCache();
        return ResponseEntity.ok(Map.of("message", "Cache refreshed", "instrumentCount", count));
    }

    /**
     * Subscribe instruments for live KiteTicker WebSocket ticks.
     * Body: list of objects with "token" (long) and "symbol" (String).
     * Example: [{"token": 12345678, "symbol": "NIFTY25APRFUT"}]
     */
    @PostMapping("/subscribe")
    public ResponseEntity<Map<String, Object>> subscribe(
            @RequestBody List<Map<String, Object>> instruments) {
        Map<Long, String> tokenMap = new LinkedHashMap<>();
        for (Map<String, Object> entry : instruments) {
            Object tokenObj = entry.get("token");
            Object symbolObj = entry.get("symbol");
            if (tokenObj != null && symbolObj != null) {
                long token = Long.parseLong(tokenObj.toString());
                tokenMap.put(token, symbolObj.toString());
            }
        }
        tickerService.subscribe(tokenMap);
        return ResponseEntity.ok(Map.of(
                "subscribed", tickerService.getSubscribedInstruments(),
                "tokens",     tickerService.getSubscribedTokens()));
    }

    /** Status of KiteTicker WebSocket subscriptions */
    @GetMapping("/subscribe/status")
    public ResponseEntity<Map<String, Object>> subscribeStatus() {
        return ResponseEntity.ok(Map.of(
                "connected",  tickerService.isActive(),
                "subscribed", tickerService.getSubscribedInstruments(),
                "tokens",     tickerService.getSubscribedTokens()));
    }

    // ---- Fallback option chain (no Kite connection) ----

    private OptionChainResponse buildFallbackChain(String symbol, double price, ExpiryType expiryType) {
        // Delegate to the existing offline option chain builder
        int atm = (int) (Math.round(price / 50.0) * 50);
        List<OptionChainResponse.StrikeData> strikes = new java.util.ArrayList<>();
        for (int s = atm - 400; s <= atm + 400; s += 50) {
            String expTag = expiryType == ExpiryType.CURRENT_WEEK ? "CW" : "NW";
            strikes.add(OptionChainResponse.StrikeData.builder()
                    .strikePrice(s)
                    .ceInstrument("NIFTY" + expTag + s + "CE")
                    .peInstrument("NIFTY" + expTag + s + "PE")
                    .isAtm(s == atm)
                    .expiryType(expiryType.name())
                    .build());
        }
        return OptionChainResponse.builder()
                .futuresInstrument(symbol)
                .niftyPrice(price)
                .atmStrike(atm)
                .strikes(strikes)
                .build();
    }

    private LocalDate getNextTuesday() {
        LocalDate d = LocalDate.now();
        while (d.getDayOfWeek().getValue() != 4) d = d.plusDays(1);
        return d;
    }
}
