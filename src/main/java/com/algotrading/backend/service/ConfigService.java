package com.algotrading.backend.service;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.dto.TradingConfigRequest;
import com.algotrading.backend.model.TradingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigService {

    private static final Set<String> ALLOWED_START_TIMES = Set.of("09:15", "09:16", "09:17");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final MarketDataCache cache;

    public TradingConfig saveConfig(TradingConfigRequest req) {
        if (!ALLOWED_START_TIMES.contains(req.getStartCandleTime())) {
            throw new IllegalArgumentException(
                    "Start candle time must be one of: 09:15, 09:16, 09:17");
        }

        TradingConfig config = TradingConfig.builder()
                .futuresInstrument(req.getFuturesInstrument())
                .ceInstrument(req.getCeInstrument())
                .peInstrument(req.getPeInstrument())
                .ceStrikePrice(req.getCeStrikePrice())
                .peStrikePrice(req.getPeStrikePrice())
                .expiryType(req.getExpiryType())
                .startCandleTime(LocalTime.parse(req.getStartCandleTime(), TIME_FMT))
                .strikeMode(req.getStrikeMode())
                .lotQuantity(req.getLotQuantity())
                .lotSize(req.getLotSize() > 0 ? req.getLotSize() : 65)
                .targetProfit(req.getTargetProfit())
                .stopLoss(req.getStopLoss())
                .maxReversals(req.getMaxReversals())
                .tradeMode(req.getTradeMode())
                .build();

        cache.setConfig(config);
        log.info("Config saved: {}", config);
        return config;
    }

    public TradingConfig getConfig() {
        return cache.getConfig();
    }
}
