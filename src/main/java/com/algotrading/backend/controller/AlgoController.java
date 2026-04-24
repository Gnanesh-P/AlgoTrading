package com.algotrading.backend.controller;

import com.algotrading.backend.dto.AlgoStartRequest;
import com.algotrading.backend.dto.AlgoStatusResponse;
import com.algotrading.backend.dto.AlgoUpdateParamsRequest;
import com.algotrading.backend.engine.TradingEngineRegistry;
import com.algotrading.backend.engine.UserTradingEngine;
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

    @PostMapping("/algo/start")
    public ResponseEntity<String> startAlgo(@RequestBody AlgoStartRequest req,
                                             Principal principal) {

        String startedBy = principal != null ? principal.getName() : "unknown";

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

        if (engineRegistry.hasActiveEngine(startedBy)) {
            log.warn("Start rejected for [{}]: already has an active session", startedBy);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("You already have an active session. Stop it first before starting a new one.");
        }

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

        if (req.getCeToken() > 0) instruments.put(req.getCeToken(), req.getCeSymbol());
        if (req.getPeToken() > 0) instruments.put(req.getPeToken(), req.getPeSymbol());

        if (instruments.isEmpty()) {
            log.warn("[{}] No instruments subscribed — candle ticks will not arrive!", startedBy);
        }

        candleAggregator.resetAll();
        tickerService.unsubscribeAll();
        if (!instruments.isEmpty()) {
            tickerService.subscribe(instruments);
            log.info("[{}] Global KiteTicker subscribed {} instruments: {}",
                    startedBy, instruments.size(), instruments.values());
        }

        PlatformUser user = platformUserOpt.orElseGet(() ->
                PlatformUser.builder()
                        .username(startedBy)
                        .role("ADMIN")
                        .enabled(true)
                        .maxLotSize(100)
                        .build()
        );

        UserTradingEngine engine = engineRegistry.startEngine(user, config, instruments, startedBy);
        TradeSession session = engine.getSession();
        log.info("[{}] Engine started. sessionId={}", startedBy, session.getSessionId());

        return ResponseEntity.ok(session.getSessionId());
    }

    @PostMapping("/algo/stop")
    public ResponseEntity<String> stopAlgo(Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";

        Optional<TradeSession> result = engineRegistry.stopEngine(username);

        // Unsubscribe global ticker once no active engines remain
        boolean anyStillActive = engineRegistry.getAllEngines().values().stream()
                .anyMatch(e -> e.isActive());
        if (!anyStillActive) {
            tickerService.unsubscribeAll();
            log.info("[{}] All engines stopped — global KiteTicker unsubscribed", username);
        }

        return result
                .map(s -> ResponseEntity.ok("Stopped: " + s.getSessionId()))
                .orElse(ResponseEntity.badRequest().body("No active session found for user: " + username));
    }

    @PostMapping("/algo/reset")
    public ResponseEntity<String> resetPnl(Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";
        engineRegistry.clearStoppedEngine(username);
        return ResponseEntity.ok("P&L reset");
    }

    @PostMapping("/algo/update-params")
    public ResponseEntity<String> updateParams(@RequestBody AlgoUpdateParamsRequest req,
                                               Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";

        return engineRegistry.getEngine(username)
                .map(engine -> {
                    try {
                        engine.updateParams(req.getTargetPrice(), req.getStopLoss(),
                                req.isStopLossEnabled(), req.getTrailingProfit());
                        return ResponseEntity.ok("Parameters updated");
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(e.getMessage());
                    }
                })
                .orElse(ResponseEntity.badRequest().body("No active session found"));
    }

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

    @GetMapping("/algo/sessions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getAllSessions() {
        return ResponseEntity.ok(engineRegistry.getAllSessionSummaries());
    }

    @PostMapping("/algo/admin/stop/{username}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> adminStop(@PathVariable String username) {
        engineRegistry.forceStopEngine(username);
        return ResponseEntity.ok("Force-stopped session for: " + username);
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
    @PreAuthorize("hasRole('ADMIN')")
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
