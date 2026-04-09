package com.algotrading.backend.controller;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.dto.OptionChainResponse;
import com.algotrading.backend.dto.TradingConfigRequest;
import com.algotrading.backend.model.ExpiryType;
import com.algotrading.backend.model.TradingConfig;
import com.algotrading.backend.service.ConfigService;
import com.algotrading.backend.service.OptionInstrumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;
    private final OptionInstrumentService optionInstrumentService;
    private final MarketDataCache cache;

    /** Save pre-market trading configuration */
    @PostMapping
    public ResponseEntity<TradingConfig> saveConfig(@Valid @RequestBody TradingConfigRequest request) {
        TradingConfig config = configService.saveConfig(request);
        return ResponseEntity.ok(config);
    }

    /** Get current configuration */
    @GetMapping
    public ResponseEntity<TradingConfig> getConfig() {
        TradingConfig config = configService.getConfig();
        if (config == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(config);
    }

    /**
     * Get option chain for UI dropdown.
     * Requires NIFTY futures instrument and current price.
     */
    @GetMapping("/option-chain")
    public ResponseEntity<OptionChainResponse> getOptionChain(
            @RequestParam String futuresInstrument,
            @RequestParam(required = false, defaultValue = "CURRENT_WEEK") ExpiryType expiryType) {

        double niftyPrice = cache.getLastPrice(futuresInstrument);
        if (niftyPrice <= 0) {
            // Fallback: use a dummy price if no tick data yet
            niftyPrice = 22000.0;
        }
        OptionChainResponse response = optionInstrumentService.buildOptionChain(
                futuresInstrument, niftyPrice, expiryType);
        return ResponseEntity.ok(response);
    }
}
