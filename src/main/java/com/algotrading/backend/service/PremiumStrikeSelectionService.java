package com.algotrading.backend.service;

import com.algotrading.backend.model.KiteInstrument;
import com.algotrading.backend.model.TradingConfig;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Quote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Auto strike selection by live premium — shared by V1's "Auto" strike mode (see
 * {@code ScalpingStrategyEngine}) and NIFTY Breakout V2 ({@code BreakoutV2StrategyEngine}).
 *
 * Walks strikes ITM from ATM on both sides in strike-step increments, batch-fetches live
 * premiums, and returns the FIRST strike per side (CE walking down, PE walking up) whose premium
 * is greater than the given threshold. Always uses the current week's expiry (no next-week
 * option, no auto-roll-near-expiry) — callers needing a different expiry policy should not use
 * this service.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PremiumStrikeSelectionService {

    private static final int SCAN_STEPS = 20; // strikes to scan each direction from ATM

    private final KiteConnect kiteConnect;
    private final KiteInstrumentService kiteInstrumentService;
    private final OptionInstrumentService optionInstrumentService;

    public record Selection(int ceStrike, KiteInstrument ce, int peStrike, KiteInstrument pe, LocalDate expiry) {}

    public Optional<Selection> selectByPremium(TradingConfig config, double spotPrice, double minPremium) {
        String index = config.isSensex() ? "SENSEX" : config.isBankNifty() ? "BANKNIFTY" : "NIFTY";
        int strikeStep = optionInstrumentService.strikeStepFor(config);
        LocalDate expiry = optionInstrumentService.currentWeekExpiry(config);
        int atm = optionInstrumentService.computeAtmStrike(spotPrice, strikeStep);

        LinkedHashMap<Integer, KiteInstrument> ceCandidates = new LinkedHashMap<>();
        LinkedHashMap<Integer, KiteInstrument> peCandidates = new LinkedHashMap<>();
        for (int i = 0; i <= SCAN_STEPS; i++) {
            int ceStrike = atm - i * strikeStep; // walk ITM for CE (lower strikes = higher premium)
            int peStrike = atm + i * strikeStep; // walk ITM for PE (higher strikes = higher premium)
            kiteInstrumentService.findOption(index, expiry, ceStrike, "CE").ifPresent(inst -> ceCandidates.put(ceStrike, inst));
            kiteInstrumentService.findOption(index, expiry, peStrike, "PE").ifPresent(inst -> peCandidates.put(peStrike, inst));
        }

        if (ceCandidates.isEmpty() || peCandidates.isEmpty()) {
            log.warn("Premium strike selection: no CE/PE candidates resolved near ATM {} for {} expiry {} — instrument cache may not be loaded",
                    atm, index, expiry);
            return Optional.empty();
        }

        String segment = config.exchangeSegment();
        List<String> keys = new ArrayList<>();
        ceCandidates.values().forEach(inst -> keys.add(segment + ":" + inst.getTradingsymbol()));
        peCandidates.values().forEach(inst -> keys.add(segment + ":" + inst.getTradingsymbol()));

        Map<String, Quote> quotes;
        try {
            quotes = kiteConnect.getQuote(keys.toArray(new String[0]));
        } catch (Exception | KiteException e) {
            log.warn("Premium strike selection: quote fetch failed: {}", e.getMessage());
            return Optional.empty();
        }

        Map.Entry<Integer, KiteInstrument> ce = pickFirstAbovePremium(ceCandidates, quotes, segment, minPremium);
        Map.Entry<Integer, KiteInstrument> pe = pickFirstAbovePremium(peCandidates, quotes, segment, minPremium);

        if (ce == null || pe == null) {
            log.warn("Premium strike selection: no CE/PE strike with premium > {} found within {} strikes of ATM {}",
                    minPremium, SCAN_STEPS, atm);
            return Optional.empty();
        }

        log.info("Premium strike selection: spot={} atm={} → CE {}@{} PE {}@{} expiry={} (premium>{})",
                spotPrice, atm, ce.getKey(), ce.getValue().getTradingsymbol(),
                pe.getKey(), pe.getValue().getTradingsymbol(), expiry, minPremium);

        return Optional.of(new Selection(ce.getKey(), ce.getValue(), pe.getKey(), pe.getValue(), expiry));
    }

    private Map.Entry<Integer, KiteInstrument> pickFirstAbovePremium(Map<Integer, KiteInstrument> candidates,
                                                                       Map<String, Quote> quotes,
                                                                       String segment,
                                                                       double minPremium) {
        for (Map.Entry<Integer, KiteInstrument> e : candidates.entrySet()) {
            Quote q = quotes.get(segment + ":" + e.getValue().getTradingsymbol());
            if (q != null && q.lastPrice > minPremium) return e;
        }
        return null;
    }
}
