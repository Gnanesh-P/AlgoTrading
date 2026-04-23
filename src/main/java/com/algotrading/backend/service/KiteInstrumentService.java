package com.algotrading.backend.service;

import com.algotrading.backend.config.KiteProperties;
import com.algotrading.backend.dto.OptionChainResponse;
import com.algotrading.backend.model.ExpiryType;
import com.algotrading.backend.model.KiteInstrument;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class KiteInstrumentService {

    private static final int STRIKE_RANGE = 400;
    private static final int STRIKE_GAP = 50;

    private final KiteProperties kite;
    private final KiteConnect kiteConnect;

    private final List<KiteInstrument> instrumentCache = new ArrayList<>();
    private LocalDate lastFetchDate;

    // ---- Public methods ----

    /**
     * Returns the nearest NIFTY Future instrument (current month, not NIFTYNXT50).
     */
    public List<KiteInstrument> getNiftyFutures() {
        ensureCacheLoaded();
        List<KiteInstrument> result = new ArrayList<>();
        result.add(KiteInstrument.builder()
                .instrumentToken(256265L)
                .tradingsymbol("NIFTY 50")
                .name("NIFTY 50")
                .instrumentType("EQ")
                .segment("INDICES")
                .exchange("NSE")
                .build());
        instrumentCache.stream()
                .filter(i -> "NIFTY".equals(i.getName()))
                .filter(i -> "FUT".equals(i.getInstrumentType()))
                .filter(i -> "NFO-FUT".equals(i.getSegment()))
                .sorted(Comparator.comparing(KiteInstrument::getExpiry))
                .forEach(result::add);
        return result;
    }

    /**
     * Returns NIFTY CE and PE options for a given expiry type, filtered ±400 from ATM.
     */
    public List<KiteInstrument> getNiftyOptions(ExpiryType expiryType, double niftyPrice) {
        ensureCacheLoaded();
        LocalDate targetExpiry = getExpiryDate(expiryType);
        int atmStrike = computeAtm(niftyPrice);

        return instrumentCache.stream()
                .filter(i -> "CE".equals(i.getInstrumentType()) || "PE".equals(i.getInstrumentType()))
                .filter(i -> "NIFTY".equals(i.getName()))
                .filter(i -> i.getExpiry() != null && i.getExpiry().equals(targetExpiry))
//                .filter(i -> i.getStrike() >= atmStrike - STRIKE_RANGE
//                        && i.getStrike() <= atmStrike + STRIKE_RANGE)
                .sorted(Comparator.comparingDouble(KiteInstrument::getStrike))
                .collect(Collectors.toList());
    }

    /**
     * Build option chain response for UI dropdown. Groups CE/PE by strike price.
     */
    public OptionChainResponse buildKiteOptionChain(String futuresSymbol, double niftyPrice,
                                                    ExpiryType expiryType) {
        List<KiteInstrument> options = getNiftyOptions(expiryType, niftyPrice);
        int atmStrike = computeAtm(niftyPrice);

        Map<Integer, OptionChainResponse.StrikeData> strikeMap = new LinkedHashMap<>();

        for (KiteInstrument opt : options) {
            int strike = (int) opt.getStrike();
            strikeMap.computeIfAbsent(strike, k -> OptionChainResponse.StrikeData.builder()
                    .strikePrice(strike)
                    .isAtm(strike == atmStrike)
                    .expiryType(expiryType.name())
                    .build());

            OptionChainResponse.StrikeData data = strikeMap.get(strike);
            if ("CE".equals(opt.getInstrumentType())) {
                data.setCeInstrument(opt.getTradingsymbol());
                data.setCeToken(opt.getInstrumentToken());
                data.setCeLtp(opt.getLastPrice());
            } else {
                data.setPeInstrument(opt.getTradingsymbol());
                data.setPeToken(opt.getInstrumentToken());
                data.setPeLtp(opt.getLastPrice());
            }
        }

        return OptionChainResponse.builder()
                .futuresInstrument(futuresSymbol)
                .niftyPrice(niftyPrice)
                .atmStrike(atmStrike)
                .strikes(new ArrayList<>(strikeMap.values()))
                .build();
    }

    /**
     * Get expiry dates for current and next week.
     * Derived from actual instrument data so holiday shifts are handled correctly.
     */
    public Map<String, LocalDate> getExpiryDates() {
        ensureCacheLoaded();
        List<LocalDate> dates = resolveNiftyOptionExpiries();
        Map<String, LocalDate> result = new LinkedHashMap<>();
        if (!dates.isEmpty()) {
            result.put("CURRENT_WEEK", dates.get(0));
            result.put("NEXT_WEEK", dates.size() > 1 ? dates.get(1) : dates.get(0).plusWeeks(1));
        } else {
            result.put("CURRENT_WEEK", calendarFallbackExpiry(ExpiryType.CURRENT_WEEK));
            result.put("NEXT_WEEK",    calendarFallbackExpiry(ExpiryType.NEXT_WEEK));
        }
        return result;
    }

    /**
     * Manually trigger a refresh of the instrument cache.
     */
    public int refreshCache() {
        lastFetchDate = null;
        instrumentCache.clear();
        ensureCacheLoaded();
        return instrumentCache.size();
    }

    /**
     * Find instrument by trading symbol (ensures cache is loaded first).
     */
    public Optional<KiteInstrument> findBySymbol(String tradingsymbol) {
        ensureCacheLoaded();
        return instrumentCache.stream()
                .filter(i -> tradingsymbol.equals(i.getTradingsymbol()))
                .findFirst();
    }

    /**
     * Find a NIFTY option by expiry date + strike + option type (CE or PE).
     * More robust than symbol-name lookup — avoids format mismatches between
     * our constructed name and what Kite actually stores in the instrument dump.
     */
    public Optional<KiteInstrument> findNiftyOption(LocalDate expiry, int strike, String optionType) {
        ensureCacheLoaded();
        return instrumentCache.stream()
                .filter(i -> "NIFTY".equals(i.getName()))
                .filter(i -> optionType.equals(i.getInstrumentType()))
                .filter(i -> expiry.equals(i.getExpiry()))
                .filter(i -> (int) i.getStrike() == strike)
                .findFirst();
    }

    /**
     * Fetch the current live LTP for an NFO instrument via Kite REST API.
     *
     * NOTE: Do NOT use KiteInstrument.lastPrice for options — Kite's instrument
     * dump carries lastPrice=0 for all option instruments (only populated for
     * equities and futures). This method calls the live LTP endpoint instead.
     *
     * Returns 0 if Kite is not connected or the call fails.
     */
    public double fetchCurrentLtp(String tradingsymbol) {
        if (!kite.isConnected()) {
            log.warn("Kite not connected — cannot fetch live LTP for {}", tradingsymbol);
            return 0;
        }
        try {
            String key = "NFO:" + tradingsymbol;
            Map<String, com.zerodhatech.models.LTPQuote> ltpMap =
                    kiteConnect.getLTP(new String[]{key});
            if (ltpMap == null) return 0;
            com.zerodhatech.models.LTPQuote quote = ltpMap.get(key);
            double price = quote != null ? quote.lastPrice : 0;
            log.info("Live LTP for {} = {}", tradingsymbol, price);
            return price;
        } catch (Exception | KiteException e) {
            log.warn("Live LTP fetch failed for {}: {}", tradingsymbol, e.getMessage());
            return 0;
        }
    }

    // ---- Private helpers ----

    private synchronized void ensureCacheLoaded() {
        if (!instrumentCache.isEmpty() && LocalDate.now(ZoneId.of("Asia/Kolkata")).equals(lastFetchDate)) {
            return;
        }
        if (!kite.isConnected()) {
            log.warn("Kite not connected. Instrument cache unavailable.");
            return;
        }
        fetchAndCacheInstruments();
    }

    private void fetchAndCacheInstruments() {
        try {
            log.info("Fetching NFO instruments from Kite SDK...");
            kiteConnect.setAccessToken(kite.getAccessToken());
            ArrayList<Instrument> instruments = (ArrayList<Instrument>) kiteConnect.getInstruments("NFO");

            List<KiteInstrument> mapped = instruments.stream()
                    .map(this::toKiteInstrument)
                    .collect(Collectors.toList());

            instrumentCache.clear();
            instrumentCache.addAll(mapped);
            lastFetchDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            log.info("Loaded {} NFO instruments from Kite SDK", instrumentCache.size());
        } catch (Exception e) {
            // KiteException extends IOException → also caught here
            log.error("Failed to fetch Kite instruments: {} — {}", e.getClass().getSimpleName(), e.getMessage());
        } catch (KiteException e) {
            throw new RuntimeException(e);
        }
    }

    private KiteInstrument toKiteInstrument(Instrument i) {
        LocalDate expiry = null;
        if (i.expiry != null) {
            expiry = i.expiry.toInstant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        }
        return KiteInstrument.builder()
                .instrumentToken(i.instrument_token)
                .exchangeToken(i.exchange_token)
                .tradingsymbol(i.tradingsymbol)
                .name(i.name)
                .lastPrice(i.last_price)
                .expiry(expiry)
                .strike(Double.parseDouble(i.strike))
                .tickSize(i.tick_size)
                .lotSize((int) i.lot_size)
                .instrumentType(i.instrument_type)
                .segment(i.segment)
                .exchange(i.exchange)
                .build();
    }

    /**
     * Returns the actual expiry date by reading from the instrument cache.
     *
     * WHY: NIFTY weekly expiry is normally Tuesday, but NSE shifts it to
     * the previous trading day when Tuesday is a market holiday
     * (e.g. Apr 15 holiday → expiry on Mon Apr 14).
     * Calendar math cannot know this; only the live Kite instrument
     * data carries the correct holiday-adjusted date.
     *
     * Falls back to calendar-based computation only when the cache is empty
     * (i.e. Kite not yet connected).
     */
    private LocalDate getExpiryDate(ExpiryType type) {
        List<LocalDate> dates = resolveNiftyOptionExpiries();
        if (!dates.isEmpty()) {
            if (type == ExpiryType.CURRENT_WEEK) {
                return dates.get(0);
            }
            // NEXT_WEEK → second distinct upcoming expiry, or +1 week if only one found
            return dates.size() > 1 ? dates.get(1) : dates.get(0).plusWeeks(1);
        }
        // Fallback (no cache): naive Tuesday calculation
        return calendarFallbackExpiry(type);
    }

    /**
     * Collects the distinct upcoming NIFTY option expiry dates from the cache,
     * sorted ascending. The first entry is the current week expiry, second is next week.
     */
    private List<LocalDate> resolveNiftyOptionExpiries() {
        LocalDate today = LocalDate.now();
        return instrumentCache.stream()
                .filter(i -> "NIFTY".equals(i.getName()))
                .filter(i -> "CE".equals(i.getInstrumentType()) || "PE".equals(i.getInstrumentType()))
                .map(KiteInstrument::getExpiry)
                .filter(Objects::nonNull)
                .filter(d -> !d.isBefore(today))   // exclude past expiries
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /** Used only when instrument cache is empty (Kite not connected yet). */
    private LocalDate calendarFallbackExpiry(ExpiryType type) {
        LocalDate d = LocalDate.now();
        while (d.getDayOfWeek() != DayOfWeek.TUESDAY) {
            d = d.plusDays(1);
        }
        return type == ExpiryType.NEXT_WEEK ? d.plusWeeks(1) : d;
    }

    private int computeAtm(double price) {
        return (int) (Math.round(price / STRIKE_GAP) * STRIKE_GAP);
    }
}