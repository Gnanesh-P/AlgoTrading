package com.algotrading.backend.service;

import com.algotrading.backend.dto.OptionChainResponse;
import com.algotrading.backend.model.ExpiryType;
import com.algotrading.backend.model.TradingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles option instrument name generation and ATM strike selection.
 * In production, replace with actual broker option chain API calls.
 */
@Service
@Slf4j
public class OptionInstrumentService {

    private static final int NIFTY_STRIKE_GAP = 50;
    private static final int STRIKE_RANGE = 400;  // ±400 from ATM

    /**
     * Compute ATM strike from current NIFTY Futures price.
     * ATM = nearest multiple of 50.
     */
    public int computeAtmStrike(double niftyPrice) {
        return (int) (Math.round(niftyPrice / NIFTY_STRIKE_GAP) * NIFTY_STRIKE_GAP);
    }

    /**
     * Generate option instrument key.
     * Format: NIFTY{YY}{MON}{STRIKE}{CE/PE}
     * e.g. NIFTY25APR22000CE
     */
    public String buildInstrumentKey(int strikePrice, String optionType, ExpiryType expiryType) {
        LocalDate expiry = getExpiryDate(expiryType);
        String monthYear = expiry.format(DateTimeFormatter.ofPattern("yyMMM")).toUpperCase();
        return "NIFTY" + monthYear + strikePrice + optionType;
    }

    /**
     * Resolve instruments for the session.
     * In AUTO_ATM mode: compute ATM from current NIFTY futures price.
     * In MANUAL mode: use the user-configured instruments.
     */
    public void resolveAndLockInstruments(TradingConfig config, com.algotrading.backend.model.TradeSession session,
                                          double currentNiftyPrice) {
        if (config.getStrikeMode() == com.algotrading.backend.model.StrikeMode.AUTO_ATM) {
            int atm = computeAtmStrike(currentNiftyPrice);
            session.setLockedCeStrike(atm);
            session.setLockedPeStrike(atm);
            session.setLockedCeInstrument(buildInstrumentKey(atm, "CE", config.getExpiryType()));
            session.setLockedPeInstrument(buildInstrumentKey(atm, "PE", config.getExpiryType()));
            log.info("AUTO_ATM mode: NIFTY={}, ATM={}, CE={}, PE={}",
                    currentNiftyPrice, atm, session.getLockedCeInstrument(), session.getLockedPeInstrument());
        } else {
            // MANUAL mode: use configured strikes
            session.setLockedCeStrike(config.getCeStrikePrice());
            session.setLockedPeStrike(config.getPeStrikePrice());
            session.setLockedCeInstrument(config.getCeInstrument());
            session.setLockedPeInstrument(config.getPeInstrument());
            log.info("MANUAL mode: CE={}, PE={}", session.getLockedCeInstrument(), session.getLockedPeInstrument());
        }
    }

    /**
     * Build the option chain for the UI dropdown (±400 from ATM in steps of 50).
     */
    public OptionChainResponse buildOptionChain(String futuresInstrument, double niftyPrice,
                                                ExpiryType expiryType) {
        int atmStrike = computeAtmStrike(niftyPrice);
        List<OptionChainResponse.StrikeData> strikes = new ArrayList<>();

        for (int strike = atmStrike - STRIKE_RANGE; strike <= atmStrike + STRIKE_RANGE; strike += NIFTY_STRIKE_GAP) {
            String ceKey = buildInstrumentKey(strike, "CE", expiryType);
            String peKey = buildInstrumentKey(strike, "PE", expiryType);
            strikes.add(OptionChainResponse.StrikeData.builder()
                    .strikePrice(strike)
                    .ceInstrument(ceKey)
                    .peInstrument(peKey)
                    .isAtm(strike == atmStrike)
                    .expiryType(expiryType.name())
                    .build());
        }

        return OptionChainResponse.builder()
                .futuresInstrument(futuresInstrument)
                .niftyPrice(niftyPrice)
                .atmStrike(atmStrike)
                .strikes(strikes)
                .build();
    }

    private LocalDate getExpiryDate(ExpiryType expiryType) {
        LocalDate today = LocalDate.now();
        // Find next Tuesday (NIFTY weekly expiry)
        LocalDate nextTuesday = today;
        while (nextTuesday.getDayOfWeek().getValue() != 4) {
            nextTuesday = nextTuesday.plusDays(1);
        }
        if (expiryType == ExpiryType.NEXT_WEEK) {
            nextTuesday = nextTuesday.plusWeeks(1);
        }
        return nextTuesday;
    }
}
