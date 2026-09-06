package com.algotrading.backend.service;

import com.algotrading.backend.config.KiteProperties;
import com.algotrading.backend.dto.OiSignalResponse;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Quote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Heuristic NIFTY market-reversal signal built from the option chain's Open Interest (OI) —
 * NOT a guaranteed prediction, just a transparent, rule-based read of positioning:
 *
 *  - PCR (Put OI / Call OI) for directional bias: heavy Put writing (PCR high) implies a
 *    support floor and bullish bias; heavy Call writing (PCR low) implies a resistance
 *    ceiling and bearish bias.
 *  - PCR extremity as a contrarian kicker: one-sided positioning (PCR very high/low) tends to
 *    get unwound, so it nudges the score AGAINST the prevailing bias — this is the "reversal"
 *    half of the signal, distinct from plain trend-following.
 *  - OI momentum: which side (Call or Put) has been adding OI faster over the last few
 *    minutes, i.e. who is building fresh conviction right now.
 *  - Support/Resistance: the strikes with the single largest Put OI / Call OI in the ATM±400
 *    window, and whether price sits close to either — reversals are most meaningful right at
 *    those levels, not in the middle of a range.
 *
 * All four factors are combined into one -100..100 score with a plain-English reasoning list
 * so the number is never a black box.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OiSignalService {

    private static final String INDEX = "NIFTY";
    private static final int STRIKE_GAP = 50;
    private static final int STRIKE_RANGE = 400;
    // Monthly OI walls tend to sit further from spot than weekly ones, so the monthly scan uses a
    // wider strike window than the weekly PCR scan above.
    private static final int MONTHLY_STRIKE_RANGE = 1000;
    private static final long CACHE_TTL_MS = 30_000;
    private static final long MIN_BASELINE_AGE_MS = 120_000; // don't compute % change over <2min — too noisy
    private static final long MAX_HISTORY_AGE_MS = 15 * 60_000;
    private static final int MAX_HISTORY = 20;

    // OI-based Support/Resistance level candidates (see class javadoc addendum below) refresh at
    // most every 30 minutes — option OI shifts slowly, and recomputing it needs an extra option
    // chain scan (monthly, wider strike window) on top of the fast 30s PCR cache above.
    private static final long LEVELS_CACHE_TTL_MS = 30 * 60_000L;
    private static final int[] MONTHLY_LEVEL_SCORES = {100, 80, 60};
    private static final int[] WEEKLY_LEVEL_SCORES  = {40, 30, 20};

    // 10-min NIFTY OI momentum table — one row per 10-minute bucket from session open to close.
    // Direction per bucket comes purely from (putOiChangePct - callOiChangePct): Put OI building
    // faster than Call OI (or Call OI unwinding faster) reads bullish, and vice versa. Cumulative
    // Direction is a running total of each bucket's directionScore (+2/+1/0/-1/-2), clamped to
    // +/-CUMULATIVE_CAP so it stays a bounded "market strength so far today" gauge instead of
    // drifting unbounded over ~37 buckets in a session.
    private static final LocalTime SESSION_START = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final int INTERVAL_MINUTES = 10;
    private static final double OI_DIRECTION_NEUTRAL_BAND = 1.0;    // |diff| below this = Neutral
    private static final double OI_DIRECTION_STRONG_THRESHOLD = 4.0; // |diff| at/above this = Strong
    private static final int CUMULATIVE_CAP = 6;
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final List<OiSignalResponse.IntervalRow> dayIntervals = new ArrayList<>();
    private LocalDate intervalsDate;
    private LocalTime lastIntervalBoundary;
    private int cumulativeScore = 0;

    private final KiteProperties kite;
    private final KiteConnect kiteConnect;
    private final KiteInstrumentService kiteInstrumentService;
    private final OptionInstrumentService optionInstrumentService;

    private final Deque<Snapshot> history = new ArrayDeque<>();
    private volatile OiSignalResponse cached;
    private volatile long cachedAt = 0;

    // OI-based Support/Resistance level candidates — every strike that made the monthly and/or
    // weekly top-3-by-OI ranking, with its merged score. Refreshed at most every
    // LEVELS_CACHE_TTL_MS; the final SUP1-3/RES1-3 (filtered by spot position, nearest-first) are
    // recomputed from these candidates on every request using the LATEST spot price, since that
    // part is free (no extra Kite call) and keeps "nearest to spot" accurate even between refreshes.
    private volatile Map<Integer, Integer> cachedSupportCandidates = Map.of();
    private volatile Map<Integer, Integer> cachedResistanceCandidates = Map.of();
    private volatile String cachedMonthlyExpiryLabel = "";
    private volatile long levelsCachedAt = 0;

    private record Snapshot(long timestamp, long totalCallOi, long totalPutOi) {}
    private record SignalResult(int score, String label, List<String> reasoning) {}
    private record ChainSymbols(Map<Integer, String> ce, Map<Integer, String> pe) {}

    public synchronized OiSignalResponse getSignal() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_TTL_MS) {
            return cached;
        }
        if (!kite.isConnected()) {
            return notConnected("Connect Kite to see the live NIFTY OI signal");
        }
        try {
            OiSignalResponse response = computeSignal();
            cached = response;
            cachedAt = now;
            return response;
        } catch (Exception | KiteException e) {
            log.warn("OI signal computation failed: {}", e.getMessage());
            return cached != null ? cached : notConnected("OI data temporarily unavailable: " + e.getMessage());
        }
    }

    private OiSignalResponse computeSignal() throws Exception, KiteException {
        kiteConnect.setAccessToken(kite.getAccessToken());

        Quote spot = fetchQuote("NSE:NIFTY 50");
        double ltp = spot != null ? spot.lastPrice : 0;
        if (ltp <= 0) {
            return notConnected("Waiting for a live NIFTY price...");
        }

        LocalDate expiry = optionInstrumentService.getAutoExpiryDate();
        int atm = (int) Math.round(ltp / STRIKE_GAP) * STRIKE_GAP;

        List<Integer> strikes = new ArrayList<>();
        for (int strike = atm - STRIKE_RANGE; strike <= atm + STRIKE_RANGE; strike += STRIKE_GAP) {
            strikes.add(strike);
        }

        Map<Integer, String> ceSymbols = new LinkedHashMap<>();
        Map<Integer, String> peSymbols = new LinkedHashMap<>();
        List<String> quoteKeys = new ArrayList<>();
        for (int strike : strikes) {
            kiteInstrumentService.findOption(INDEX, expiry, strike, "CE").ifPresent(i -> {
                ceSymbols.put(strike, i.getTradingsymbol());
                quoteKeys.add("NFO:" + i.getTradingsymbol());
            });
            kiteInstrumentService.findOption(INDEX, expiry, strike, "PE").ifPresent(i -> {
                peSymbols.put(strike, i.getTradingsymbol());
                quoteKeys.add("NFO:" + i.getTradingsymbol());
            });
        }
        if (ceSymbols.isEmpty() || peSymbols.isEmpty()) {
            return notConnected("Option chain not cached yet — ensure Kite is connected");
        }

        Map<String, Quote> quotes = kiteConnect.getQuote(quoteKeys.toArray(new String[0]));

        List<OiSignalResponse.StrikeOi> strikeOiList = new ArrayList<>();
        long totalCallOi = 0, totalPutOi = 0;
        int resistanceStrike = atm, supportStrike = atm;
        long maxCallOi = -1, maxPutOi = -1;

        for (int strike : strikes) {
            long ceOi = oiFor(quotes, ceSymbols.get(strike));
            long peOi = oiFor(quotes, peSymbols.get(strike));
            totalCallOi += ceOi;
            totalPutOi += peOi;
            if (ceOi > maxCallOi) { maxCallOi = ceOi; resistanceStrike = strike; }
            if (peOi > maxPutOi) { maxPutOi = peOi; supportStrike = strike; }
            strikeOiList.add(OiSignalResponse.StrikeOi.builder()
                    .strike(strike).ceOi(ceOi).peOi(peOi).atm(strike == atm).build());
        }

        double pcr = totalCallOi > 0 ? (double) totalPutOi / totalCallOi : 0;

        long nowMs = System.currentTimeMillis();
        Snapshot baseline = oldestUsableSnapshot(nowMs);
        double callChangePct = baseline != null && baseline.totalCallOi() > 0
                ? (totalCallOi - baseline.totalCallOi()) * 100.0 / baseline.totalCallOi() : 0;
        double putChangePct = baseline != null && baseline.totalPutOi() > 0
                ? (totalPutOi - baseline.totalPutOi()) * 100.0 / baseline.totalPutOi() : 0;
        recordSnapshot(nowMs, totalCallOi, totalPutOi);
        recordIntervalSnapshot(ltp, totalCallOi, totalPutOi);

        // OI-based Support/Resistance indicator (monthly + weekly merged, top-3 each side) —
        // candidates refresh at most every 30 min (see refreshLevelCandidatesIfStale); the final
        // SUP1-3/RES1-3 selection (filter by spot position, nearest-first) always uses the LATEST
        // ltp, which is free (no extra Kite call).
        refreshLevelCandidatesIfStale(atm);
        List<OiSignalResponse.LevelInfo> supportLevels = finalLevels(cachedSupportCandidates, ltp, true);
        List<OiSignalResponse.LevelInfo> resistanceLevels = finalLevels(cachedResistanceCandidates, ltp, false);

        // Feed the nearest OI-based level into the existing PCR/momentum proximity check in place
        // of the old crude "single max-OI strike in the weekly ATM window" — falls back to that
        // crude value if the new indicator hasn't produced a candidate on either side yet.
        int effectiveSupportStrike = !supportLevels.isEmpty() ? supportLevels.get(0).getStrike() : supportStrike;
        int effectiveResistanceStrike = !resistanceLevels.isEmpty() ? resistanceLevels.get(0).getStrike() : resistanceStrike;

        SignalResult result = computeSignalScore(pcr, callChangePct, putChangePct, ltp,
                effectiveSupportStrike, effectiveResistanceStrike);

        OiSignalResponse response = OiSignalResponse.builder()
                .dataAvailable(true)
                .index(INDEX)
                .ltp(ltp)
                .expiryLabel(expiry.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)))
                .monthlyExpiryLabel(cachedMonthlyExpiryLabel)
                .pcr(round2(pcr))
                .totalCallOi(totalCallOi)
                .totalPutOi(totalPutOi)
                .callOiChangePct(round2(callChangePct))
                .putOiChangePct(round2(putChangePct))
                .supportStrike(effectiveSupportStrike)
                .resistanceStrike(effectiveResistanceStrike)
                .signal(result.label())
                .signalScore(result.score())
                .reasoning(result.reasoning())
                .strikes(strikeOiList)
                .supportLevels(supportLevels)
                .resistanceLevels(resistanceLevels)
                .levelsAsOf(levelsCachedAt > 0
                        ? Instant.ofEpochMilli(levelsCachedAt).atZone(ZoneId.of("Asia/Kolkata")).toLocalDateTime()
                        : null)
                .intervalTable(new ArrayList<>(dayIntervals))
                .asOf(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();

        return response;
    }

    /**
     * Appends one row to the 10-min OI momentum table whenever wall-clock time has crossed a new
     * 10-minute bucket boundary since session open (09:15). Reuses the weekly near-ATM
     * totalCallOi/totalPutOi already fetched above for the PCR calc — no extra Kite call. The
     * table (and cumulative score) resets automatically at the start of each new trading day.
     */
    private void recordIntervalSnapshot(double ltp, long totalCallOi, long totalPutOi) {
        ZonedDateTime nowZoned = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDate today = nowZoned.toLocalDate();
        LocalTime nowTime = nowZoned.toLocalTime();
        if (nowTime.isBefore(SESSION_START)) return;

        if (!today.equals(intervalsDate)) {
            dayIntervals.clear();
            intervalsDate = today;
            lastIntervalBoundary = null;
            cumulativeScore = 0;
        }

        LocalTime boundary = currentBoundary(nowTime);
        if (lastIntervalBoundary != null && !boundary.isAfter(lastIntervalBoundary)) return;

        if (dayIntervals.isEmpty()) {
            dayIntervals.add(OiSignalResponse.IntervalRow.builder()
                    .label(SESSION_START.format(HHMM) + " (Open)")
                    .spot(round2(ltp))
                    .callOi(totalCallOi)
                    .putOi(totalPutOi)
                    .direction("NEUTRAL")
                    .directionScore(0)
                    .cumulativeScore(0)
                    .cumulativeLabel(cumulativeLabelFor(0))
                    .build());
        } else {
            OiSignalResponse.IntervalRow prev = dayIntervals.get(dayIntervals.size() - 1);
            double callChangePct = prev.getCallOi() > 0
                    ? (totalCallOi - prev.getCallOi()) * 100.0 / prev.getCallOi() : 0;
            double putChangePct = prev.getPutOi() > 0
                    ? (totalPutOi - prev.getPutOi()) * 100.0 / prev.getPutOi() : 0;

            double diff = putChangePct - callChangePct;
            String direction;
            int directionScore;
            if (Math.abs(diff) < OI_DIRECTION_NEUTRAL_BAND) {
                direction = "NEUTRAL"; directionScore = 0;
            } else if (diff >= OI_DIRECTION_STRONG_THRESHOLD) {
                direction = "STRONG_BULLISH"; directionScore = 2;
            } else if (diff > 0) {
                direction = "BULLISH"; directionScore = 1;
            } else if (diff <= -OI_DIRECTION_STRONG_THRESHOLD) {
                direction = "STRONG_BEARISH"; directionScore = -2;
            } else {
                direction = "BEARISH"; directionScore = -1;
            }

            cumulativeScore = Math.max(-CUMULATIVE_CAP, Math.min(CUMULATIVE_CAP, cumulativeScore + directionScore));

            dayIntervals.add(OiSignalResponse.IntervalRow.builder()
                    .label(lastIntervalBoundary.format(HHMM) + " – " + boundary.format(HHMM))
                    .spot(round2(ltp))
                    .callOi(totalCallOi)
                    .putOi(totalPutOi)
                    .callOiChangePct(round2(callChangePct))
                    .putOiChangePct(round2(putChangePct))
                    .direction(direction)
                    .directionScore(directionScore)
                    .cumulativeScore(cumulativeScore)
                    .cumulativeLabel(cumulativeLabelFor(cumulativeScore))
                    .build());
        }
        lastIntervalBoundary = boundary;
    }

    private LocalTime currentBoundary(LocalTime nowTime) {
        if (!nowTime.isBefore(MARKET_CLOSE)) return MARKET_CLOSE;
        long minutesSinceOpen = Duration.between(SESSION_START, nowTime).toMinutes();
        long bucket = (minutesSinceOpen / INTERVAL_MINUTES) * INTERVAL_MINUTES;
        return SESSION_START.plusMinutes(bucket);
    }

    private String cumulativeLabelFor(int score) {
        if (score >= 5) return "VERY_STRONG_BULLISH";
        if (score >= 3) return "STRONG_BULLISH";
        if (score >= 1) return "BULLISH";
        if (score <= -5) return "VERY_STRONG_BEARISH";
        if (score <= -3) return "STRONG_BEARISH";
        if (score <= -1) return "BEARISH";
        return "NEUTRAL";
    }

    /**
     * Recomputes the monthly+weekly OI-based Support/Resistance candidate scores if the cache is
     * older than {@value #LEVELS_CACHE_TTL_MS}ms. Runs its own dedicated option-chain fetch
     * (monthly at a wider ATM±{@value #MONTHLY_STRIKE_RANGE} window, weekly at ATM±{@value
     * #STRIKE_RANGE}) independent of the fast 30s PCR fetch above, since this indicator is
     * deliberately refreshed on a much slower cadence. On failure, keeps whatever was cached
     * before rather than clearing it — a transient Kite hiccup shouldn't blank out the panel.
     */
    private void refreshLevelCandidatesIfStale(int atm) {
        long now = System.currentTimeMillis();
        if (now - levelsCachedAt < LEVELS_CACHE_TTL_MS) return;
        try {
            LocalDate weeklyExpiry = optionInstrumentService.getAutoExpiryDate();
            LocalDate monthlyExpiry = optionInstrumentService.getMonthlyExpiryDate();

            ChainSymbols weeklySymbols = buildChainSymbols(weeklyExpiry, atm, STRIKE_RANGE);
            ChainSymbols monthlySymbols = buildChainSymbols(monthlyExpiry, atm, MONTHLY_STRIKE_RANGE);

            List<String> quoteKeys = new ArrayList<>();
            weeklySymbols.ce().values().forEach(s -> quoteKeys.add("NFO:" + s));
            weeklySymbols.pe().values().forEach(s -> quoteKeys.add("NFO:" + s));
            monthlySymbols.ce().values().forEach(s -> quoteKeys.add("NFO:" + s));
            monthlySymbols.pe().values().forEach(s -> quoteKeys.add("NFO:" + s));
            if (quoteKeys.isEmpty()) return; // instrument cache not loaded yet — keep prior cache

            Map<String, Quote> quotes = kiteConnect.getQuote(quoteKeys.stream().distinct().toArray(String[]::new));

            Map<Integer, Long> weeklyCallOi  = toOiMap(weeklySymbols.ce(), quotes);
            Map<Integer, Long> weeklyPutOi   = toOiMap(weeklySymbols.pe(), quotes);
            Map<Integer, Long> monthlyCallOi = toOiMap(monthlySymbols.ce(), quotes);
            Map<Integer, Long> monthlyPutOi  = toOiMap(monthlySymbols.pe(), quotes);

            Map<Integer, Integer> monthlySupport    = rankTopScores(monthlyPutOi, MONTHLY_LEVEL_SCORES);
            Map<Integer, Integer> monthlyResistance = rankTopScores(monthlyCallOi, MONTHLY_LEVEL_SCORES);
            Map<Integer, Integer> weeklySupport     = rankTopScores(weeklyPutOi, WEEKLY_LEVEL_SCORES);
            Map<Integer, Integer> weeklyResistance  = rankTopScores(weeklyCallOi, WEEKLY_LEVEL_SCORES);

            cachedSupportCandidates = mergeScores(monthlySupport, weeklySupport);
            cachedResistanceCandidates = mergeScores(monthlyResistance, weeklyResistance);
            cachedMonthlyExpiryLabel = monthlyExpiry.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH));
            levelsCachedAt = now;
        } catch (Exception | KiteException e) {
            log.warn("OI Support/Resistance level refresh failed (keeping previous levels): {}", e.getMessage());
        }
    }

    private ChainSymbols buildChainSymbols(LocalDate expiry, int atm, int range) {
        Map<Integer, String> ce = new LinkedHashMap<>();
        Map<Integer, String> pe = new LinkedHashMap<>();
        for (int strike = atm - range; strike <= atm + range; strike += STRIKE_GAP) {
            int s = strike;
            kiteInstrumentService.findOption(INDEX, expiry, s, "CE").ifPresent(i -> ce.put(s, i.getTradingsymbol()));
            kiteInstrumentService.findOption(INDEX, expiry, s, "PE").ifPresent(i -> pe.put(s, i.getTradingsymbol()));
        }
        return new ChainSymbols(ce, pe);
    }

    private Map<Integer, Long> toOiMap(Map<Integer, String> symbolMap, Map<String, Quote> quotes) {
        Map<Integer, Long> result = new LinkedHashMap<>();
        symbolMap.forEach((strike, sym) -> result.put(strike, oiFor(quotes, sym)));
        return result;
    }

    /** Ranks strikes by OI descending and assigns scores[0] to the top strike, scores[1] to 2nd, etc. */
    private Map<Integer, Integer> rankTopScores(Map<Integer, Long> oiMap, int[] scores) {
        List<Map.Entry<Integer, Long>> ranked = oiMap.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted((a, b) -> {
                    int cmp = Long.compare(b.getValue(), a.getValue());
                    return cmp != 0 ? cmp : Integer.compare(a.getKey(), b.getKey());
                })
                .limit(scores.length)
                .toList();
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            result.put(ranked.get(i).getKey(), scores[i]);
        }
        return result;
    }

    /** Strike exists in both → scores add; strike exists in only one → that score is used as-is. */
    private Map<Integer, Integer> mergeScores(Map<Integer, Integer> monthly, Map<Integer, Integer> weekly) {
        Map<Integer, Integer> merged = new LinkedHashMap<>(monthly);
        weekly.forEach((strike, score) -> merged.merge(strike, score, Integer::sum));
        return merged;
    }

    /**
     * Support: keep only candidate strikes BELOW spot, sort descending (closest to spot first),
     * take 3 → SUP1..SUP3. Resistance: keep only strikes ABOVE spot, sort ascending, take 3 →
     * RES1..RES3.
     */
    private List<OiSignalResponse.LevelInfo> finalLevels(Map<Integer, Integer> candidates, double spot, boolean support) {
        return candidates.entrySet().stream()
                .filter(e -> support ? e.getKey() < spot : e.getKey() > spot)
                .sorted(support
                        ? (a, b) -> Integer.compare(b.getKey(), a.getKey())
                        : Map.Entry.comparingByKey())
                .limit(3)
                .map(e -> OiSignalResponse.LevelInfo.builder()
                        .strike(e.getKey())
                        .score(e.getValue())
                        .strength(strengthFor(e.getValue()))
                        .build())
                .toList();
    }

    private String strengthFor(int score) {
        if (score >= 120) return "VERY_STRONG";
        if (score >= 80) return "STRONG";
        if (score >= 40) return "MEDIUM";
        return "WEAK";
    }

    private SignalResult computeSignalScore(double pcr, double callChangePct, double putChangePct,
                                             double ltp, int supportStrike, int resistanceStrike) {
        List<String> reasoning = new ArrayList<>();
        int score = 0;

        if (pcr >= 1.3) {
            score += 25;
            reasoning.add(String.format(Locale.ENGLISH,
                    "PCR %.2f — heavy Put writing → bullish bias (strong support below)", pcr));
        } else if (pcr <= 0.7) {
            score -= 25;
            reasoning.add(String.format(Locale.ENGLISH,
                    "PCR %.2f — heavy Call writing → bearish bias (strong resistance above)", pcr));
        } else {
            reasoning.add(String.format(Locale.ENGLISH,
                    "PCR %.2f — balanced Call/Put writing, no strong directional bias", pcr));
        }

        if (pcr >= 1.7) {
            score -= 20;
            reasoning.add("PCR is stretched high — one-sided Put writing often gets unwound: watch for a bearish reversal");
        } else if (pcr <= 0.4) {
            score += 20;
            reasoning.add("PCR is stretched low — one-sided Call writing often gets unwound: watch for a bullish reversal");
        }

        double momentum = putChangePct - callChangePct;
        if (momentum > 5) {
            score += 25;
            reasoning.add(String.format(Locale.ENGLISH,
                    "Put OI %+.1f%% vs Call OI %+.1f%% — fresh Put writing outpacing Calls (support strengthening)",
                    putChangePct, callChangePct));
        } else if (momentum < -5) {
            score -= 25;
            reasoning.add(String.format(Locale.ENGLISH,
                    "Call OI %+.1f%% vs Put OI %+.1f%% — fresh Call writing outpacing Puts (resistance strengthening)",
                    callChangePct, putChangePct));
        } else {
            reasoning.add("OI change on both sides is roughly balanced — no clear fresh momentum yet");
        }

        double distToSupport = Math.abs(ltp - supportStrike) / ltp * 100;
        double distToResistance = Math.abs(ltp - resistanceStrike) / ltp * 100;
        boolean nearSupport = ltp >= supportStrike && distToSupport <= 0.3;
        boolean nearResistance = ltp <= resistanceStrike && distToResistance <= 0.3;

        if (nearSupport && momentum >= 0) {
            score += 30;
            reasoning.add(String.format(Locale.ENGLISH,
                    "Price is close to support %d with Put OI building — reversal-up zone", supportStrike));
        } else if (nearResistance && momentum <= 0) {
            score -= 30;
            reasoning.add(String.format(Locale.ENGLISH,
                    "Price is close to resistance %d with Call OI building — reversal-down zone", resistanceStrike));
        }

        score = Math.max(-100, Math.min(100, score));

        String label;
        if (score >= 50) label = "STRONG_BULLISH_REVERSAL";
        else if (score >= 15) label = "BULLISH";
        else if (score > -15) label = "NEUTRAL";
        else if (score > -50) label = "BEARISH";
        else label = "STRONG_BEARISH_REVERSAL";

        return new SignalResult(score, label, reasoning);
    }

    private Snapshot oldestUsableSnapshot(long nowMs) {
        Snapshot fallback = null;
        for (Snapshot s : history) {
            if (fallback == null) fallback = s;
            if (nowMs - s.timestamp() >= MIN_BASELINE_AGE_MS) {
                return s;
            }
        }
        return fallback;
    }

    private void recordSnapshot(long nowMs, long totalCallOi, long totalPutOi) {
        history.addLast(new Snapshot(nowMs, totalCallOi, totalPutOi));
        while (history.size() > MAX_HISTORY
                || (!history.isEmpty() && nowMs - history.peekFirst().timestamp() > MAX_HISTORY_AGE_MS)) {
            history.removeFirst();
        }
    }

    private long oiFor(Map<String, Quote> quotes, String tradingsymbol) {
        if (tradingsymbol == null || quotes == null) return 0;
        Quote q = quotes.get("NFO:" + tradingsymbol);
        return q != null ? (long) q.oi : 0;
    }

    private Quote fetchQuote(String key) {
        try {
            Map<String, Quote> map = kiteConnect.getQuote(new String[]{key});
            return map != null ? map.get(key) : null;
        } catch (Exception | KiteException e) {
            log.warn("Quote fetch failed for {}: {}", key, e.getMessage());
            return null;
        }
    }

    private OiSignalResponse notConnected(String msg) {
        return OiSignalResponse.builder()
                .dataAvailable(false)
                .message(msg)
                .index(INDEX)
                .asOf(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
