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
import com.algotrading.backend.service.KiteTokenStore;
import com.algotrading.backend.service.UserRegistryService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Margin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    private final KiteTokenStore kiteTokenStore;
    private final MarketDataCache cache;
    private final UserRegistryService userRegistry;
    private final TradingEngineRegistry engineRegistry;
    private final KiteConnect kiteConnect;

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
            return new RedirectView("/algo?kite=connected");
        } catch (Exception e) {
            log.error("Kite callback error: {}", e.getMessage());
            String encoded;
            try {
                encoded = URLEncoder.encode(e.getMessage() != null ? e.getMessage() : "Unknown error",
                        StandardCharsets.UTF_8.toString());
            } catch (UnsupportedEncodingException uee) {
                encoded = "Kite authentication failed";
            }
            return new RedirectView("/algo?kite=error&message=" + encoded);
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
        log.error("[{}] Kite access token updated via /my-access-token", username);

        // 2. Update all of this user's running engines, if any (hot-reload token)
        engineRegistry.getAllForUser(username).forEach(engine -> engine.updateKiteAccessToken(token));
        kiteAuthService.exchangeToken(token);

        // 3. Also update the GLOBAL Kite connection (shared ticker + instrument feed).
        //    This makes kiteProperties.isConnected() = true so:
        //      - Status badge shows "Kite: Connected"
        //      - KiteTicker WebSocket starts (paper-mode tick feed)
        //      - Instrument/option chain fetching works for everyone
        //    Any authenticated user setting their token initialises the global connection.
        //    The global connection uses whichever token was set most recently — this is fine
        //    because all Kite tokens from the same API key can fetch instruments and stream ticks.
//        kiteAuthService.setAccessToken(token);
        log.info("[{}] Global Kite connection updated from /my-access-token", username);

        return ResponseEntity.ok(Map.of(
                "status",  "token_updated",
                "message", "Kite access token saved and global connection updated.",
                "user",    username));
    }

    /**
     * Disconnect Kite — clears the saved token from disk and stops the WebSocket.
     * The user will need to set a new token to reconnect (e.g. after token expiry).
     */
    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect() {
        kiteTokenStore.clear();
        kiteProperties.setAccessToken(null);
        tickerService.unsubscribeAll();
        return ResponseEntity.ok(Map.of("status", "disconnected",
                "message", "Kite access token cleared. Set a new token to reconnect."));
    }

    /** Get Kite connection status */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        boolean tokenPresent = kiteProperties.isConnected();
        boolean tickerActive = tickerService.isActive();
        // "connected" = token is present AND ticker WebSocket is up
        // Use tokenPresent alone to decide if REST calls (instruments, LTP) will work
        return ResponseEntity.ok(Map.of(
                "connected",             tokenPresent,
                "tickerActive",          tickerActive,
                "apiKey",                kiteProperties.getApiKey() != null ? kiteProperties.getApiKey() : "",
                "subscribedInstruments", tickerService.getSubscribedInstruments()
        ));
    }

    /** Zerodha funds/margin balance for the "equity" segment (used for options margin too). */
    @GetMapping("/funds")
    public ResponseEntity<Map<String, Object>> getFunds() {
        if (!kiteProperties.isConnected()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Kite not connected"));
        }
        try {
            Margin margin = kiteConnect.getMargins("equity");
            Map<String, Object> result = new LinkedHashMap<>();
            if (margin.available != null) {
                result.put("availableCash", margin.available.cash);
                result.put("liveBalance", margin.available.liveBalance);
                result.put("collateral", margin.available.collateral);
                result.put("intradayPayin", margin.available.intradayPayin);
                result.put("adhocMargin", margin.available.adhocMargin);
            }
            if (margin.utilised != null) {
                result.put("utilisedDebits", margin.utilised.debits);
                result.put("utilisedSpan", margin.utilised.span);
                result.put("utilisedOptionPremium", margin.utilised.optionPremium);
                result.put("utilisedExposure", margin.utilised.exposure);
                result.put("m2mUnrealised", margin.utilised.m2mUnrealised);
                result.put("m2mRealised", margin.utilised.m2mRealised);
            }
            result.put("net", margin.net);
            // "Available Margin" — the actual money available for further trading (net of
            // utilised span/exposure/premium), same figure Kite's own app/console labels
            // "Available Margin". Falls back to live_balance if net isn't populated.
            // (All Margin fields from the SDK are Strings — parse defensively.)
            Double net = parseMargin(margin.net);
            Double availableMargin = (net != null && net != 0)
                    ? net
                    : (margin.available != null ? parseMargin(margin.available.liveBalance) : null);
            result.put("availableMargin", availableMargin);
            return ResponseEntity.ok(result);
        } catch (Exception | com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException e) {
            log.warn("Failed to fetch Kite funds: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "Failed to fetch funds: " + e.getMessage()));
        }
    }

    /** Margin fields from the Kite SDK are Strings (e.g. "12345.67") — parse defensively. */
    private static Double parseMargin(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Instruments ----

    /** Get futures instruments for a given index (NIFTY or BANKNIFTY) for dropdown selection */
    @GetMapping("/instruments/futures")
    public ResponseEntity<List<KiteInstrument>> getNiftyFutures(
            @RequestParam(defaultValue = "NIFTY") String index) {
        return ResponseEntity.ok(instrumentService.getFuturesFor(index));
    }

    /**
     * Get options for a given index (NIFTY or BANKNIFTY) and expiry type.
     * Filtered to ±400 strikes from current ATM.
     * Pass ?niftyPrice=22000 if you want to override the cached price.
     */
    @GetMapping("/instruments/options")
    public ResponseEntity<OptionChainResponse> getNiftyOptions(
            @RequestParam(defaultValue = "CURRENT_WEEK") ExpiryType expiryType,
            @RequestParam(required = false) String futuresSymbol,
            @RequestParam(required = false) Double niftyPrice,
            @RequestParam(defaultValue = "NIFTY") String index) {

        String symbol = futuresSymbol;
        double price = niftyPrice != null ? niftyPrice : cache.getLastPrice(symbol);
        if (price <= 0) price = fallbackPriceFor(index);  // fallback default

        OptionChainResponse chain = kiteProperties.isConnected()
                ? instrumentService.buildKiteOptionChain(index, symbol, price, expiryType)
                : buildFallbackChain(symbol, price, expiryType, strikeGapFor(index));

        return ResponseEntity.ok(chain);
    }

    /** Get options for BOTH current and next week in one call */
    @GetMapping("/instruments/options/all")
    public ResponseEntity<Map<String, Object>> getAllOptions(
            @RequestParam(required = false) String futuresSymbol,
            @RequestParam(required = false) Double niftyPrice,
            @RequestParam(defaultValue = "NIFTY") String index) {

        String symbol = futuresSymbol != null ? futuresSymbol : "NIFTY25APRFUT";
        double price = niftyPrice != null ? niftyPrice : cache.getLastPrice(symbol);
        int strikeGap = strikeGapFor(index);
        if (price <= 0) price = fallbackPriceFor(index);

        OptionChainResponse currentWeek = kiteProperties.isConnected()
                ? instrumentService.buildKiteOptionChain(index, symbol, price, ExpiryType.CURRENT_WEEK)
                : buildFallbackChain(symbol, price, ExpiryType.CURRENT_WEEK, strikeGap);

        OptionChainResponse nextWeek = kiteProperties.isConnected()
                ? instrumentService.buildKiteOptionChain(index, symbol, price, ExpiryType.NEXT_WEEK)
                : buildFallbackChain(symbol, price, ExpiryType.NEXT_WEEK, strikeGap);

        Map<String, LocalDate> expiryDates = kiteProperties.isConnected()
                ? instrumentService.getExpiryDates(index)
                : Map.of("CURRENT_WEEK", getNextExpiryWeekday(index), "NEXT_WEEK", getNextExpiryWeekday(index).plusWeeks(1));

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

    private OptionChainResponse buildFallbackChain(String symbol, double price, ExpiryType expiryType, int strikeGap) {
        // Delegate to the existing offline option chain builder
        int atm = (int) (Math.round(price / strikeGap) * strikeGap);
        String indexTag = "NIFTY";
        if (symbol != null && symbol.contains("BANKNIFTY")) indexTag = "BANKNIFTY";
        else if (symbol != null && symbol.contains("SENSEX")) indexTag = "SENSEX";
        List<OptionChainResponse.StrikeData> strikes = new java.util.ArrayList<>();
        for (int s = atm - 400; s <= atm + 400; s += strikeGap) {
            String expTag = expiryType == ExpiryType.CURRENT_WEEK ? "CW" : "NW";
            strikes.add(OptionChainResponse.StrikeData.builder()
                    .strikePrice(s)
                    .ceInstrument(indexTag + expTag + s + "CE")
                    .peInstrument(indexTag + expTag + s + "PE")
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

    /** Cosmetic-only fallback (Kite not connected): NIFTY/BANKNIFTY -> Tuesday, SENSEX -> Thursday. */
    private LocalDate getNextExpiryWeekday(String index) {
        java.time.DayOfWeek target = "SENSEX".equals(index)
                ? java.time.DayOfWeek.THURSDAY : java.time.DayOfWeek.TUESDAY;
        LocalDate d = LocalDate.now();
        while (d.getDayOfWeek() != target) d = d.plusDays(1);
        return d;
    }

    private int strikeGapFor(String index) {
        return ("BANKNIFTY".equals(index) || "SENSEX".equals(index)) ? 100 : 50;
    }

    private double fallbackPriceFor(String index) {
        if ("BANKNIFTY".equals(index)) return 48000;
        if ("SENSEX".equals(index)) return 80000;
        return 22000;
    }
}
