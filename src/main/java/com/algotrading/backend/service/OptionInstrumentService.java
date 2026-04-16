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

@Service
@Slf4j
public class OptionInstrumentService {

    private static final int NIFTY_STRIKE_GAP = 50;
    private static final int STRIKE_RANGE = 400;

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

    public int computeCeStrike(double niftyPrice) {
        return (int) (Math.ceil(niftyPrice / NIFTY_STRIKE_GAP) * NIFTY_STRIKE_GAP);
    }

    public int computePeStrike(double niftyPrice) {
        return (int) (Math.floor(niftyPrice / NIFTY_STRIKE_GAP) * NIFTY_STRIKE_GAP);
    }

    public int computeAtmStrike(double niftyPrice) {
        return (int) (Math.round(niftyPrice / NIFTY_STRIKE_GAP) * NIFTY_STRIKE_GAP);
    }

    public String buildInstrumentKey(int strikePrice, String optionType, ExpiryType expiryType) {
        LocalDate expiry = getExpiryDate(expiryType);
        String monthYear = expiry.format(DateTimeFormatter.ofPattern("yyMMM")).toUpperCase();
        return "NIFTY" + monthYear + strikePrice + optionType;
    }

    public void resolveAndLockInstruments(TradingConfig config, TradeSession session,
                                          double currentNiftyPrice) {
        if (config.getStrikeMode() == StrikeMode.AUTO_ATM) {
            LocalDate autoExpiry = getAutoExpiryDate();
            String monthYear = autoExpiry.format(DateTimeFormatter.ofPattern("yyMMM")).toUpperCase();

            int ceStrike = computeCeStrike(currentNiftyPrice);
            int peStrike = computePeStrike(currentNiftyPrice);

            session.setLockedCeStrike(ceStrike);
            session.setLockedPeStrike(peStrike);
            session.setLockedCeInstrument("NIFTY" + monthYear + ceStrike + "CE");
            session.setLockedPeInstrument("NIFTY" + monthYear + peStrike + "PE");
            log.info("AUTO_ATM: NIFTY={} → CE strike={} ({}) | PE strike={} ({})",
                    currentNiftyPrice, ceStrike, session.getLockedCeInstrument(),
                    peStrike, session.getLockedPeInstrument());
        } else {
            session.setLockedCeStrike(config.getCeStrikePrice());
            session.setLockedPeStrike(config.getPeStrikePrice());
            session.setLockedCeInstrument(config.getCeInstrument());
            session.setLockedPeInstrument(config.getPeInstrument());
            log.info("MANUAL: CE={} PE={}", session.getLockedCeInstrument(), session.getLockedPeInstrument());
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

    private LocalDate getAutoExpiryDate() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate expiry = findWeeklyExpiry(today);

        int tradingDaysToExpiry = countTradingDays(today, expiry);

        if (tradingDaysToExpiry <= 1) {
            log.info("AUTO expiry: {} trading day(s) to {}, switching to next week", tradingDaysToExpiry, expiry);
            expiry = findWeeklyExpiry(expiry.plusDays(1));
        }
        return expiry;
    }

    private LocalDate getExpiryDate(ExpiryType expiryType) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate expiry = findWeeklyExpiry(today);
        if (expiryType == ExpiryType.NEXT_WEEK) {
            expiry = findWeeklyExpiry(expiry.plusDays(1));
        }
        return expiry;
    }

    private LocalDate findWeeklyExpiry(LocalDate from) {
        LocalDate d = from;
        while (d.getDayOfWeek() != DayOfWeek.TUESDAY) {
            d = d.plusDays(1);
        }
        if (NSE_HOLIDAYS.contains(d)) {
            d = d.minusDays(1);
            while (d.getDayOfWeek() == DayOfWeek.SATURDAY
                    || d.getDayOfWeek() == DayOfWeek.SUNDAY
                    || NSE_HOLIDAYS.contains(d)) {
                d = d.minusDays(1);
            }
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
