package com.algotrading.backend.service;

import com.algotrading.backend.dto.OptionChainResponse;
import com.algotrading.backend.model.ExpiryType;
import com.algotrading.backend.model.StrikeMode;
import com.algotrading.backend.model.TradingConfig;
import com.algotrading.backend.model.TradeSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Locale;

@Service
@Slf4j
public class OptionInstrumentService {

    private static final int NIFTY_STRIKE_GAP = 50;
    private static final int BANKNIFTY_STRIKE_GAP = 100;
    private static final int SENSEX_STRIKE_GAP = 100;
    private static final int STRIKE_RANGE = 400;

    /** Strike step for the given futures/index instrument symbol (50 for NIFTY, 100 for BANKNIFTY/SENSEX). */
    public int strikeStepFor(String instrument) {
        if (instrument == null) return NIFTY_STRIKE_GAP;
        String u = instrument.toUpperCase();
        if (u.contains("BANKNIFTY")) return BANKNIFTY_STRIKE_GAP;
        if (u.contains("SENSEX")) return SENSEX_STRIKE_GAP;
        return NIFTY_STRIKE_GAP;
    }

    /**
     * Strike step for a trading session, preferring the authoritative {@code config.strategyKey}
     * (set from which strategy card the user started — always correct) over sniffing the
     * futuresInstrument text, which is fragile: e.g. when Kite isn't connected yet the futures
     * dropdown only offers the synthetic spot-index placeholder "NIFTY BANK", which does NOT
     * contain the substring "BANKNIFTY" and silently produced a 50-point (NIFTY) step instead
     * of the correct 100-point Bank Nifty step.
     */
    public int strikeStepFor(TradingConfig config) {
        if (config.isSensex()) return SENSEX_STRIKE_GAP;
        return config.isBankNifty() ? BANKNIFTY_STRIKE_GAP : NIFTY_STRIKE_GAP;
    }

    /**
     * Weekly expiry weekday for a session — TUESDAY for NIFTY/BANKNIFTY (NSE), THURSDAY for
     * SENSEX (BSE). See {@link #findWeeklyExpiry(LocalDate, DayOfWeek)}.
     */
    private DayOfWeek expiryWeekdayFor(TradingConfig config) {
        return config.isSensex() ? DayOfWeek.THURSDAY : DayOfWeek.TUESDAY;
    }

    private static final Set<LocalDate> NSE_HOLIDAYS = Set.of(
        LocalDate.of(2026, 1, 26),
        LocalDate.of(2026, 2, 19),
        LocalDate.of(2026, 3, 20),
        LocalDate.of(2026, 3, 31),
        LocalDate.of(2026, 4, 2),
        LocalDate.of(2026, 4, 10),
        LocalDate.of(2026, 4, 14),
        LocalDate.of(2026, 5, 1),
        LocalDate.of(2026, 6, 19),
        LocalDate.of(2026, 7, 29),
        LocalDate.of(2026, 8, 15),
        LocalDate.of(2026, 8, 27),
        LocalDate.of(2026, 10, 2),
        LocalDate.of(2026, 10, 21),
        LocalDate.of(2026, 11, 5),
        LocalDate.of(2026, 11, 25),
        LocalDate.of(2026, 12, 25),
        LocalDate.of(2027, 1, 26),
        LocalDate.of(2027, 3, 12),
        LocalDate.of(2027, 3, 30),
        LocalDate.of(2027, 4, 1),
        LocalDate.of(2027, 4, 14),
        LocalDate.of(2027, 5, 1),
        LocalDate.of(2027, 8, 15),
        LocalDate.of(2027, 10, 2)
    );

    /**
     * First OTM CE strike: always strictly ABOVE price.
     * e.g. (step=50) 24330 → 24350, 24300 → 24350 (not ATM)
     */
    public int computeCeStrike(double price) {
        return computeCeStrike(price, NIFTY_STRIKE_GAP);
    }

    public int computeCeStrike(double price, int strikeStep) {
        return (int) (Math.floor(price / strikeStep) * strikeStep) + strikeStep;
    }

    /**
     * First OTM PE strike: always strictly at or below price (ATM-aligned floor).
     * e.g. (step=50) 24330 → 24300, 24300 → 24300
     */
    public int computePeStrike(double price) {
        return computePeStrike(price, NIFTY_STRIKE_GAP);
    }

    public int computePeStrike(double price, int strikeStep) {
        return (int) (Math.floor(price / strikeStep) * strikeStep);
    }

    public int computeAtmStrike(double price) {
        return computeAtmStrike(price, NIFTY_STRIKE_GAP);
    }

    public int computeAtmStrike(double price, int strikeStep) {
        return (int) (Math.round(price / strikeStep) * strikeStep);
    }

    public String buildInstrumentKey(int strikePrice, String optionType, ExpiryType expiryType) {
        LocalDate expiry = getExpiryDate(expiryType);
        String dateKey = expiry.format(DateTimeFormatter.ofPattern("yyddMMM")).toUpperCase();
        return "NIFTY" + dateKey + strikePrice + optionType;
    }

    public void resolveAndLockInstruments(TradingConfig config, TradeSession session,
                                          double currentNiftyPrice) {
        DayOfWeek expiryWeekday = expiryWeekdayFor(config);
        if (config.getStrikeMode() == StrikeMode.AUTO_ATM) {
            LocalDate today      = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            LocalDate thisExpiry = findWeeklyExpiry(today, expiryWeekday);
            LocalDate autoExpiry = getAutoExpiryDate();

            boolean isNextWeek = autoExpiry.isAfter(thisExpiry);
            String expiryLabel = (isNextWeek ? "Next Week" : "Current Week")
                    + " (" + autoExpiry.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)) + ")";

            int strikeStep = strikeStepFor(config);
            String indexPrefix = config.isBankNifty() ? "BANKNIFTY" : "NIFTY";

            int ceStrike = computeCeStrike(currentNiftyPrice, strikeStep);
            int peStrike = computePeStrike(currentNiftyPrice, strikeStep);

            // Store expiry date — engine will resolve the real tradingsymbol from Kite cache
            // using attribute lookup (expiry + strike + type) to avoid symbol name format issues.
            session.setLockedCeStrike(ceStrike);
            session.setLockedPeStrike(peStrike);
            session.setLockedExpiry(autoExpiry);
            session.setLockedExpiryLabel(expiryLabel);
            // Placeholder names shown in UI until engine resolves real ones from cache
            String dateKey = autoExpiry.format(DateTimeFormatter.ofPattern("yyddMMM")).toUpperCase();
            session.setLockedCeInstrument(indexPrefix + dateKey + ceStrike + "CE");
            session.setLockedPeInstrument(indexPrefix + dateKey + peStrike + "PE");
            log.info("AUTO_ATM: {}={} → CE strike={} PE strike={} expiry={} ({})",
                    indexPrefix, currentNiftyPrice, ceStrike, peStrike, autoExpiry, expiryLabel);
        } else {
            session.setLockedCeStrike(config.getCeStrikePrice());
            session.setLockedPeStrike(config.getPeStrikePrice());
            session.setLockedCeInstrument(config.getCeInstrument());
            session.setLockedPeInstrument(config.getPeInstrument());

            // Expiry label from config's expiryType
            LocalDate today  = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            LocalDate expiry = getExpiryDate(config.getExpiryType(), expiryWeekday);
            LocalDate thisExpiry = findWeeklyExpiry(today, expiryWeekday);
            boolean isNextWeek = expiry.isAfter(thisExpiry);
            String expiryLabel = (isNextWeek ? "Next Week" : "Current Week")
                    + " (" + expiry.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)) + ")";
            session.setLockedExpiryLabel(expiryLabel);
            log.info("MANUAL: CE={} PE={} expiry={}", session.getLockedCeInstrument(),
                    session.getLockedPeInstrument(), expiryLabel);
        }
    }

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

    public LocalDate getAutoExpiryDate() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate expiry = findWeeklyExpiry(today);

        int tradingDaysToExpiry = countTradingDays(today, expiry);

        if (tradingDaysToExpiry <= 1) {
            LocalDate nextExpiry = findExpiryAfter(expiry);
            log.info("AUTO expiry: {} trading day(s) to {}, switching to {}", tradingDaysToExpiry, expiry, nextExpiry);
            expiry = nextExpiry;
        }
        return expiry;
    }

    private LocalDate findExpiryAfter(LocalDate after) {
        LocalDate search = after.plusDays(1);
        LocalDate candidate;
        do {
            candidate = findWeeklyExpiry(search);
            search = search.plusDays(7);
        } while (!candidate.isAfter(after));
        return candidate;
    }

    private LocalDate getExpiryDate(ExpiryType expiryType) {
        return getExpiryDate(expiryType, DayOfWeek.TUESDAY);
    }

    private LocalDate getExpiryDate(ExpiryType expiryType, DayOfWeek expiryWeekday) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate expiry = findWeeklyExpiry(today, expiryWeekday);
        if (expiryType == ExpiryType.NEXT_WEEK) {
            expiry = findWeeklyExpiry(expiry.plusDays(1), expiryWeekday);
        }
        return expiry;
    }

    /**
     * NIFTY/BANKNIFTY weekly options expire every TUESDAY (NSE rule); SENSEX expires every
     * THURSDAY (BSE rule) — see {@link #expiryWeekdayFor(TradingConfig)}.
     * If the target weekday is a holiday, expiry moves to the preceding trading day.
     */
    private LocalDate findWeeklyExpiry(LocalDate from) {
        return findWeeklyExpiry(from, DayOfWeek.TUESDAY);
    }

    private LocalDate findWeeklyExpiry(LocalDate from, DayOfWeek expiryWeekday) {
        LocalDate d = from;
        while (d.getDayOfWeek() != expiryWeekday) {
            d = d.plusDays(1);
        }
        // If the expiry weekday is a holiday, step back until we hit a trading day.
        // NSE_HOLIDAYS is shared across NSE/BSE — Indian exchange holidays are effectively
        // identical across NSE and BSE, so no separate BSE holiday table is needed.
        while (NSE_HOLIDAYS.contains(d)
                || d.getDayOfWeek() == DayOfWeek.SATURDAY
                || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private int countTradingDays(LocalDate from, LocalDate to) {
        int count = 0;
        LocalDate d = from.plusDays(1);
        while (!d.isAfter(to)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !NSE_HOLIDAYS.contains(d)) {
                count++;
            }
            d = d.plusDays(1);
        }
        return count;
    }
}
