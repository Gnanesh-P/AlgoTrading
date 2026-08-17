package com.algotrading.backend.controller;

import com.algotrading.backend.dto.AlgoStartRequest;
import com.algotrading.backend.dto.AlgoStatusResponse;
import com.algotrading.backend.dto.AlgoUpdateParamsRequest;
import com.algotrading.backend.engine.TradingEngine;
import com.algotrading.backend.engine.TradingEngineRegistry;
import com.algotrading.backend.model.*;
import com.algotrading.backend.service.AlgoSchedulerService;
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
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Slf4j
public class AlgoController {

    private final TradingEngineRegistry   engineRegistry;
    private final UserRegistryService     userRegistry;
    private final KiteInstrumentService   kiteInstrumentService;
    private final KiteTickerService       tickerService;
    private final CandleAggregatorService candleAggregator;
    private final AlgoSchedulerService    schedulerService;

    private StrategyKey resolveStrategyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("strategyKey is required");
        }
        try {
            return StrategyKey.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown strategyKey: " + raw);
        }
    }

    @PostMapping("/algo/start")
    public ResponseEntity<String> startAlgo(@RequestBody AlgoStartRequest req,
                                             Principal principal) {

        String startedBy = principal != null ? principal.getName() : "unknown";

        StrategyKey strategyKey;
        try {
            strategyKey = resolveStrategyKey(req.getStrategyKey());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }

        TradeDirection direction = (req.getTradeDirection() != null && !req.getTradeDirection().isBlank())
                ? TradeDirection.valueOf(req.getTradeDirection().toUpperCase())
                : TradeDirection.BUY;

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

        if (engineRegistry.hasActiveEngine(startedBy, strategyKey)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("You already have an active " + strategyKey + " session. Stop it first before starting a new one.");
        }

        int lots = Math.max(req.getLotQuantity(), 1);

        TradingConfig config = TradingConfig.builder()
                .strategyKey(strategyKey)
                .tradeDirection(direction)
                .reversalEnabled(req.isReversalEnabled())
                .futuresInstrument(req.getFutureSymbol())
                .ceInstrument(req.getCeSymbol())
                .peInstrument(req.getPeSymbol())
                .ceStrikePrice(req.getCeStrikePrice())
                .peStrikePrice(req.getPeStrikePrice())
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
                .breakoutPoints(req.getBreakoutPoints() > 0 ? req.getBreakoutPoints() : 5.0)
                .maxChasePoints(req.getMaxChasePoints() > 0 ? req.getMaxChasePoints() : 15.0)
                .build();

        log.info("[{}][{}] Config: {} lot(s) = {} qty, mode={}, direction={}", startedBy, strategyKey, lots,
                config.getTotalQuantity(), config.getTradeMode(), direction);

        Map<Long, String> instruments = new LinkedHashMap<>();

        if (req.getFutureToken() > 0) {
            instruments.put(req.getFutureToken(), req.getFutureSymbol());
        } else if (req.getFutureSymbol() != null && !req.getFutureSymbol().isBlank()) {
            kiteInstrumentService.findBySymbol(req.getFutureSymbol())
                    .filter(i -> i.getInstrumentToken() > 0)
                    .ifPresentOrElse(
                            i -> {
                                instruments.put(i.getInstrumentToken(), i.getTradingsymbol());
                            },
                            () -> log.warn("[{}] Futures token=0 and not in cache for [{}]. "
                                    + "Candle closes will not arrive until Kite is connected.",
                                    startedBy, req.getFutureSymbol())
                    );
        }

        if (req.getCeToken() > 0) instruments.put(req.getCeToken(), req.getCeSymbol());
        if (req.getPeToken() > 0) instruments.put(req.getPeToken(), req.getPeSymbol());

        if (instruments.isEmpty()) {
            log.warn("[{}] No instruments subscribed — candle ticks will not arrive!", startedBy);
        }

        // NOTE: do NOT call tickerService.unsubscribeAll() here — KiteTickerService is a single
        // global WebSocket shared across every user/strategy. Wiping all subscriptions on every
        // /algo/start would drop tick delivery for any other already-running engine (e.g. starting
        // BANKNIFTY_SCALP would kill ticks for an already-running NIFTY_SCALP/NIFTY_BREAKOUT
        // session). subscribe() merges tokens additively, so it's safe to call on its own.
        if (!instruments.isEmpty()) {
            tickerService.subscribe(instruments);
        }

        PlatformUser user = platformUserOpt.orElseGet(() ->
                PlatformUser.builder()
                        .username(startedBy)
                        .role("GNANESH")
                        .enabled(true)
                        .maxLotSize(100)
                        .build()
        );

        TradingEngine engine = engineRegistry.startEngine(user, strategyKey, config, instruments, startedBy);
        TradeSession session = engine.getSession();
        log.info("[{}][{}] Engine started. sessionId={}", startedBy, strategyKey, session.getSessionId());

        return ResponseEntity.ok(session.getSessionId());
    }

    @PostMapping("/algo/stop")
    public ResponseEntity<String> stopAlgo(@RequestParam("strategy") String strategy, Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";
        StrategyKey strategyKey;
        try {
            strategyKey = resolveStrategyKey(strategy);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }

        Optional<TradeSession> result = engineRegistry.stopEngine(username, strategyKey);

        // Unsubscribe global ticker once no active engines remain across any user/strategy
        boolean anyStillActive = engineRegistry.getAllEngines().values().stream()
                .anyMatch(TradingEngine::isActive);
        if (!anyStillActive) {
            tickerService.unsubscribeAll();
            log.info("[{}] All engines stopped — global KiteTicker unsubscribed", username);
        }

        return result
                .map(s -> ResponseEntity.ok("Stopped: " + s.getSessionId()))
                .orElse(ResponseEntity.badRequest().body("No active " + strategyKey + " session found for user: " + username));
    }

    @PostMapping("/algo/reset")
    public ResponseEntity<String> resetPnl(@RequestParam("strategy") String strategy, Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";
        StrategyKey strategyKey;
        try {
            strategyKey = resolveStrategyKey(strategy);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        engineRegistry.clearStoppedEngine(username, strategyKey);
        return ResponseEntity.ok("P&L reset");
    }

    @PostMapping("/algo/update-params")
    public ResponseEntity<String> updateParams(@RequestBody AlgoUpdateParamsRequest req,
                                               @RequestParam("strategy") String strategy,
                                               Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";
        StrategyKey strategyKey;
        try {
            strategyKey = resolveStrategyKey(strategy);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }

        StrategyKey finalStrategyKey = strategyKey;
        return engineRegistry.getEngine(username, strategyKey)
                .map(engine -> {
                    try {
                        engine.updateParams(req.getTargetPrice(), req.getStopLoss(),
                                req.isStopLossEnabled(), req.getTrailingProfit());
                        return ResponseEntity.ok("Parameters updated");
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(e.getMessage());
                    }
                })
                .orElse(ResponseEntity.badRequest().body("No active " + finalStrategyKey + " session found"));
    }

    @GetMapping("/algo/status")
    public ResponseEntity<AlgoStatusResponse> getStatus(@RequestParam("strategy") String strategy,
                                                         Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";
        StrategyKey strategyKey;
        try {
            strategyKey = resolveStrategyKey(strategy);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        return (ResponseEntity<AlgoStatusResponse>) engineRegistry.getEngine(username, strategyKey)
                .map(engine -> {
                    AlgoStatusResponse resp = engine.buildStatusResponse();
                    return resp != null
                            ? ResponseEntity.ok(resp)
                            : ResponseEntity.<AlgoStatusResponse>noContent().build();
                })
                .orElse(ResponseEntity.<AlgoStatusResponse>noContent().build());
    }

    /** Returns status for every strategy the current user has an engine for (active or recently stopped). */
    @GetMapping("/algo/status/all")
    public ResponseEntity<List<AlgoStatusResponse>> getAllStatus(Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";
        List<AlgoStatusResponse> statuses = engineRegistry.getAllForUser(username).stream()
                .map(TradingEngine::buildStatusResponse)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        return ResponseEntity.ok(statuses);
    }

    @GetMapping("/algo/sessions")
    @PreAuthorize("hasRole('GNANESH')")
    public ResponseEntity<List<Map<String, Object>>> getAllSessions() {
        return ResponseEntity.ok(engineRegistry.getAllSessionSummaries());
    }

    @PostMapping("/algo/admin/stop/{username}")
    @PreAuthorize("hasRole('GNANESH')")
    public ResponseEntity<String> adminStop(@PathVariable String username,
                                             @RequestParam(value = "strategy", required = false) String strategy) {
        if (strategy != null && !strategy.isBlank()) {
            engineRegistry.forceStopEngine(username, resolveStrategyKey(strategy));
        } else {
            engineRegistry.forceStopAllForUser(username);
        }
        return ResponseEntity.ok("Force-stopped session(s) for: " + username);
    }

    /**
     * Instantly triggers the same AUTO_ATM start logic as the morning 9:10 cron.
     * Can be called at any time — useful for manual testing or late starts.
     *
     * Optional query param ?username=xxx overrides the configured scheduler username.
     * Admin-only.
     *
     * GET /algo/scheduler/trigger
     * GET /algo/scheduler/trigger?username=gowtham
     */
    @GetMapping("/algo/scheduler/trigger")
    @PreAuthorize("hasRole('GNANESH')")
    public ResponseEntity<Map<String, String>> triggerScheduler(
            @RequestParam(required = false) String username) {

        String targetUser = (username != null && !username.isBlank())
                ? username : schedulerService.getSchedulerUsername();

        log.info("Manual scheduler trigger for [{}]", targetUser);
        String result = schedulerService.triggerAutoStart(targetUser);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("result",   result);
        body.put("username", targetUser);
        body.put("futures",  schedulerService.resolveNiftyFuturesSymbol());

        boolean isError = result.startsWith("ERROR");
        return isError
                ? ResponseEntity.badRequest().body(body)
                : ResponseEntity.ok(body);
    }
}
