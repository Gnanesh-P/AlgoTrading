package com.algotrading.backend.controller;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.config.KiteProperties;
import com.algotrading.backend.model.ExpiryType;
import com.algotrading.backend.model.KiteInstrument;
import com.algotrading.backend.model.TradingConfig;
import com.algotrading.backend.service.KiteInstrumentService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Returns instruments in the flat format expected by the UI template.
 */
@RestController
@RequestMapping("/api/instruments")
@RequiredArgsConstructor
public class InstrumentController {

    private final KiteInstrumentService kiteInstrumentService;
    private final KiteProperties kiteProperties;
    private final MarketDataCache cache;

    // ---- Futures ----

    @GetMapping("/futures")
    public ResponseEntity<List<FuturesDto>> getFutures() {
        if (kiteProperties.isConnected()) {
            List<FuturesDto> result = kiteInstrumentService.getNiftyFutures().stream()
                    .map(i -> FuturesDto.builder()
                            .symbol(i.getTradingsymbol())
                            .expiry(i.getExpiry() != null ? i.getExpiry().toString() : "")
                            .instrumentToken(i.getInstrumentToken())
                            .lotSize(i.getLotSize() > 0 ? i.getLotSize() : 75)
                            .build())
                    .collect(Collectors.toList());
            return ResponseEntity.ok(result);
        }
        // Fallback when Kite not connected
        return ResponseEntity.ok(fallbackFutures());
    }

    // ---- Options ----

    @GetMapping("/options")
    public ResponseEntity<OptionsDataDto> getOptions() {
        TradingConfig config = cache.getConfig();
        String symbol = config != null && config.getFuturesInstrument() != null
                ? config.getFuturesInstrument() : "NIFTY";
        double price = cache.getLastPrice(symbol);
        if (price <= 0) price = 22000.0;

        if (kiteProperties.isConnected()) {
            List<KiteInstrument> currentWeek =
                    kiteInstrumentService.getNiftyOptions(ExpiryType.CURRENT_WEEK, price);
            List<KiteInstrument> nextWeek =
                    kiteInstrumentService.getNiftyOptions(ExpiryType.NEXT_WEEK, price);

            return ResponseEntity.ok(OptionsDataDto.builder()
                    .currentWeekOptions(toOptionDtos(currentWeek))
                    .nextWeekOptions(toOptionDtos(nextWeek))
                    .build());
        }
        // Fallback
        return ResponseEntity.ok(fallbackOptions(price));
    }

    // ---- Helpers ----

    private List<OptionDto> toOptionDtos(List<KiteInstrument> instruments) {
        return instruments.stream()
                .map(i -> OptionDto.builder()
                        .tradingSymbol(i.getTradingsymbol())
                        .optionType(i.getInstrumentType())   // CE or PE
                        .strike((int) i.getStrike())
                        .instrumentToken(i.getInstrumentToken())
                        .expiry(i.getExpiry() != null ? i.getExpiry().toString() : "")
                        .lotSize(i.getLotSize() > 0 ? i.getLotSize() : 65)
                        .build())
                .collect(Collectors.toList());
    }

    private List<FuturesDto> fallbackFutures() {
        LocalDate expiry = nextTuesday();
        List<FuturesDto> list = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LocalDate e = expiry.plusWeeks(i);
            list.add(FuturesDto.builder()
                    .symbol("NIFTY" + e.format(java.time.format.DateTimeFormatter.ofPattern("yyMMM")).toUpperCase() + "FUT")
                    .expiry(e.toString())
                    .instrumentToken(0L)
                    .lotSize(75)
                    .build());
        }
        return list;
    }

    private OptionsDataDto fallbackOptions(double niftyPrice) {
        int atm = (int) (Math.round(niftyPrice / 50.0) * 50);
        LocalDate cwExpiry = nextTuesday();
        LocalDate nwExpiry = cwExpiry.plusWeeks(1);

        List<OptionDto> cw = new ArrayList<>();
        List<OptionDto> nw = new ArrayList<>();
        for (int strike = atm - 400; strike <= atm + 400; strike += 50) {
            for (String type : List.of("CE", "PE")) {
                String cwSym = "NIFTY" + cwExpiry.format(java.time.format.DateTimeFormatter.ofPattern("yyMMM")).toUpperCase() + strike + type;
                String nwSym = "NIFTY" + nwExpiry.format(java.time.format.DateTimeFormatter.ofPattern("yyMMM")).toUpperCase() + strike + type;
                cw.add(OptionDto.builder().tradingSymbol(cwSym).optionType(type).strike(strike).instrumentToken(0L).expiry(cwExpiry.toString()).lotSize(75).build());
                nw.add(OptionDto.builder().tradingSymbol(nwSym).optionType(type).strike(strike).instrumentToken(0L).expiry(nwExpiry.toString()).lotSize(75).build());
            }
        }
        return OptionsDataDto.builder().currentWeekOptions(cw).nextWeekOptions(nw).build();
    }

    private LocalDate nextTuesday() {
        LocalDate d = LocalDate.now();
        while (d.getDayOfWeek() != DayOfWeek.TUESDAY) d = d.plusDays(1);
        return d;
    }

    // ---- Inner DTOs ----

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FuturesDto {
        private String symbol;
        private String expiry;
        private long   instrumentToken;
        private int    lotSize;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OptionDto {
        private String tradingSymbol;
        private String optionType;    // CE | PE
        private int    strike;
        private long   instrumentToken;
        private String expiry;
        private int    lotSize;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OptionsDataDto {
        private List<OptionDto> currentWeekOptions;
        private List<OptionDto> nextWeekOptions;
    }
}
