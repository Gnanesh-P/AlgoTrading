package com.algotrading.backend.service;

import com.algotrading.backend.config.KiteProperties;
import com.algotrading.backend.dto.OiSignalResponse;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Quote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    private static final Map<String, String> OI_SIGNAL_TELEGRAM_LABELS = Map.of(
            "STRONG_BULLISH_REVERSAL", "🚀 Strong Bullish Reversal",
            "BULLISH", "📈 Bullish",
            "NEUTRAL", "➖ Neutral / Range-bound",
            "BEARISH", "📉 Bearish",
            "STRONG_BEARISH_REVERSAL", "🔻 Strong Bearish Reversal"
    );

    private final KiteProperties kite;
    private final KiteConnect kiteConnect;
    private final KiteInstrumentService kiteInstrumentService;
    private final OptionInstrumentService optionInstrumentService;
    private final TelegramService telegramService;

    private final Deque<Snapshot> history = new ArrayDeque<>();
    private volatile OiSignalResponse cached;
    private volatile long cachedAt = 0;
    private volatile String lastNotifiedSignal;

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
                .asOf(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();

        notifyIfSignalChanged(response);
        return response;
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

    /**
     * Telegram alert only fires when the signal LABEL actually changes (not on every 30s poll) —
     * otherwise a NEUTRAL/BULLISH reading sitting still would spam the same message forever.
     * The very first computation since app start always sends one (lastNotifiedSignal starts null),
     * announcing the current state.
     */
    private void notifyIfSignalChanged(OiSignalResponse r) {
        if (r.getSignal() == null || r.getSignal().equals(lastNotifiedSignal)) return;
        lastNotifiedSignal = r.getSignal();

        String label = OI_SIGNAL_TELEGRAM_LABELS.getOrDefault(r.getSignal(), r.getSignal());
        String reasoningHtml = r.getReasoning() == null ? "" : String.join("\n", r.getReasoning().stream()
                .map(line -> "• " + line).toList());

        String msg = String.format(
                "📡 <b>NIFTY OI Signal Update</b>\n" +
                        "Signal: <b>%s</b> (score %+d)\n" +
                        "LTP: <code>%.2f</code>  PCR: <code>%.2f</code>\n" +
                        "Support: <code>%d</code>  Resistance: <code>%d</code>\n\n" +
                        "%s",
                label, r.getSignalScore(), r.getLtp(), r.getPcr(),
                r.getSupportStrike(), r.getResistanceStrike(), reasoningHtml);

        telegramService.sendStrategyMessage(msg);
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
