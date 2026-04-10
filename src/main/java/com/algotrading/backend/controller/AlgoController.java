package com.algotrading.backend.controller;

import com.algotrading.backend.dto.AlgoStartRequest;
import com.algotrading.backend.dto.AlgoStatusResponse;
import com.algotrading.backend.engine.TradingEngineRegistry;
import com.algotrading.backend.engine.UserTradingEngine;
import com.algotrading.backend.model.*;
import com.algotrading.backend.service.CandleAggregatorService;
import com.algotrading.backend.service.KiteInstrumentService;
import com.algotrading.backend.service.KiteTickerService;
import com.algotrading.backend.service.UserRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Algo control endpoints — multi-tenant edition.
 *
 * Each authenticated user runs their own isolated {@link UserTradingEngine}:
 *  • Start → creates a dedicated engine for the caller (plan/lot checks first).
 *  • Stop  → stops only the caller's own engine.
 *  • Status → returns only the caller's own session.
 *
 * Multiple users can trade simultaneously with their own Zerodha accounts.
 *
 * Conflict rules:
 *  • A user cannot start a second session while their first is WAITING or IN_POSITION (409).
 *  • Users do not block each other — each engine is fully independent.
 *
 * Plan enforcement:
 *  • Expired plan → HTTP 403.
 *  • Lot quantity exceeding plan limit → HTTP 403.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class AlgoController {

    private final TradingEngineRegistry   engineRegistry;
    private final UserRegistryService     userRegistry;
    private final KiteInstrumentService   kiteInstrumentService;
    // Needed to forward ticks to MarketDataService so the engine receives them
    private final KiteTickerService       tickerService;
    private final CandleAggregatorService candleAggregator;

    // ── Start ─────────────────────────────────────────────────────────────────

    @PostMapping("/algo/start")
    public ResponseEntity<String> startAlgo(@RequestBody AlgoStartRequest req,
                                             Principal principal) {

        String startedBy = principal != null ? principal.getName() : "unknown";

        // ── 1. Plan-expiry & lot-limit checks ────────────────────────────────
        var platformUserOpt = userRegistry.findByUsername(startedBy);
        if (platformUserOpt.isPresent()) {
            var pu = platformUserOpt.get();

            if (!pu.isEnabled()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Your account has been disabled. Contact the admin.");
            }
            if (!pu.isPlanActive()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Your plan expired on " + pu.getPlanExpiryDate()
                            + ". Please renew your subscription.");
            }

            int requestedLots = Math.max(req.getLotQuantity(), 1);
            if (requestedLots > pu.getMaxLotSize()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("You requested " + requestedLots + " lots but your plan allows max "
                            + pu.getMaxLotSize() + " lot(s). Please reduce lot quantity.");
            }
        }
        // If user not in registry (e.g. first-run admin), skip plan check

        // ── 2. Per-user conflict guard ────────────────────────────────────────
        // Each user gets their own engine — they don't block each other.
        // But a single user cannot start two sessions simultaneously.
        if (engineRegistry.hasActiveEngine(startedBy)) {
            log.warn("Start rejected for [{}]: already has an active session", startedBy);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("You already have an active session. Stop it first before starting a new one.");
        }

        // ── 3. Build TradingConfig ────────────────────────────────────────────
        int lots = Math.max(req.getLotQuantity(), 1);

        TradingConfig config = TradingConfig.builder()
                .futuresInstrument(req.getFutureSymbol())
                .ceInstrument(req.getCeSymbol())
                .peInstrument(req.getPeSymbol())
                .ceStrikePrice(0)
                .peStrikePrice(0)
                .expiryType(req.getExpiryType() != null
                        ? ExpiryType.valueOf(req.getExpiryType())
                        : ExpiryType.CURRENT_WEEK)
                .startCandleTime(LocalTime.parse(req.getEntryStartTime()))
                .strikeMode("AUTO".equalsIgnoreCase(req.getStrikeMode())
                        ? StrikeMode.AUTO_ATM : StrikeMode.MANUAL)
                .lotQuantity(lots)
                .targetProfit(req.getTargetPrice())
                .stopLoss(req.getStopLoss())
                .maxReversals(req.getMaxReversals())
                .trailingProfit(req.getTrailingProfit())
                .squareOffEod(req.isSquareOffEod())
                .tradeMode(req.isPaperTrade() ? TradeMode.PAPER : TradeMode.LIVE)
                .build();

        log.info("[{}] Config: {} lot(s) = {} qty, mode={}", startedBy, lots,
                config.getTotalQuantity(), config.getTradeMode());

        // ── 4. Build instrument token→symbol map ──────────────────────────────
        // If futureToken=0 (Kite not connected when UI loaded), auto-resolve from cache.
        Map<Long, String> instruments = new LinkedHashMap<>();

        if (req.getFutureToken() > 0) {
            instruments.put(req.getFutureToken(), req.getFutureSymbol());
        } else if (req.getFutureSymbol() != null && !req.getFutureSymbol().isBlank()) {
            kiteInstrumentService.findBySymbol(req.getFutureSymbol())
                    .filter(i -> i.getInstrumentToken() > 0)
                    .ifPresentOrElse(
                            i -> {
                                instruments.put(i.getInstrumentToken(), i.getTradingsymbol());
                                log.info("[{}] Futures token auto-resolved: {} → token={}",
                                        startedBy, i.getTradingsymbol(), i.getInstrumentToken());
                            },
                            () -> log.warn("[{}] Futures token=0 and not in cache for [{}]. "
                                    + "Candle closes will not arrive until Kite is connected.",
                                    startedBy, req.getFutureSymbol())
                    );
        }

        // CE/PE tokens — only needed for MANUAL mode (AUTO_ATM resolves at 2nd candle close)
        if (req.getCeToken() > 0) instruments.put(req.getCeToken(), req.getCeSymbol());
        if (req.getPeToken() > 0) instruments.put(req.getPeToken(), req.getPeSymbol());

        if (instruments.isEmpty()) {
            log.warn("[{}] No instruments subscribed — candle ticks will not arrive!", startedBy);
        }

        // ── 5. Clean slate — reset forming candles + global KiteTicker subs ───
        //
        // WHY THIS IS CRITICAL:
        //   The global KiteTickerService.processTicks() has a token→symbol map.
        //   If a token is NOT in that map, the tick is silently dropped with
        //   "unknown token, skipping" BEFORE it ever reaches MarketDataService.
        //   MarketDataService routes ticks to paper-mode engines via
        //   routeTickToPaperEngines(). If ticks never reach MarketDataService,
        //   the engine receives NOTHING — no candle closes, no SL checks.
        //
        //   Calling tickerService.subscribe(instruments) registers the token→symbol
        //   mapping in the global ticker so ticks ARE forwarded to MarketDataService,
        //   which then routes them to the engine.  LIVE-mode engines receive ticks
        //   directly from their own per-user KiteTicker (inside UserTradingEngine),
        //   but the global subscription still provides LTP for the status UI.
        candleAggregator.resetAll();       // clear any forming candles from previous session
        tickerService.unsubscribeAll();    // remove stale subscriptions from previous session
        if (!instruments.isEmpty()) {
            tickerService.subscribe(instruments);   // register tokens so ticks flow through
            log.info("[{}] Global KiteTicker subscribed {} instruments: {}",
                    startedBy, instruments.size(), instruments.values());
        }

        // ── 6. Get the PlatformUser (or create a transient one for the admin) ─
        PlatformUser user = platformUserOpt.orElseGet(() ->
                PlatformUser.builder()
                        .username(startedBy)
                        .role("ADMIN")
                        .enabled(true)
                        .maxLotSize(100)
                        .build()
        );

        // ── 7. Start engine ───────────────────────────────────────────────────
        UserTradingEngine engine = engineRegistry.startEngine(user, config, instruments, startedBy);
        TradeSession session = engine.getSession();
        log.info("[{}] Engine started. sessionId={}", startedBy, session.getSessionId());

        return ResponseEntity.ok(session.getSessionId());
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    @PostMapping("/algo/stop")
    public ResponseEntity<String> stopAlgo(Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";

        return engineRegistry.stopEngine(username)
                .map(s -> ResponseEntity.ok("Stopped: " + s.getSessionId()))
                .orElse(ResponseEntity.badRequest().body("No active session found for user: " + username));
    }

    // ── Status (caller's own session) ─────────────────────────────────────────

    @GetMapping("/algo/status")
    public ResponseEntity<AlgoStatusResponse> getStatus(Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";

        return (ResponseEntity<AlgoStatusResponse>) engineRegistry.getEngine(username)
                .map(engine -> {
                    AlgoStatusResponse resp = engine.buildStatusResponse();
                    return resp != null
                            ? ResponseEntity.ok(resp)
                            : ResponseEntity.<AlgoStatusResponse>noContent().build();
                })
                .orElse(ResponseEntity.<AlgoStatusResponse>noContent().build());
    }

    // ── All active sessions (admin overview) ──────────────────────────────────

    @GetMapping("/algo/sessions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getAllSessions() {
        return ResponseEntity.ok(engineRegistry.getAllSessionSummaries());
    }

    // ── Admin force-stop any session ──────────────────────────────────────────

    @PostMapping("/algo/admin/stop/{username}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> adminStop(@PathVariable String username) {
        engineRegistry.forceStopEngine(username);
        return ResponseEntity.ok("Force-stopped session for: " + username);
    }
}
