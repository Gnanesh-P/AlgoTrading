package com.algotrading.backend.service;

import com.algotrading.backend.config.KiteProperties;
import com.algotrading.backend.dto.OiSignalResponse;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Quote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private static final long CACHE_TTL_MS = 30_000;
    private static final long MIN_BASELINE_AGE_MS = 120_000; // don't compute % change over <2min — too noisy
    private static final long MAX_HISTORY_AGE_MS = 15 * 60_000;
    private static final int MAX_HISTORY = 20;

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

    private record Snapshot(long timestamp, long totalCallOi, long totalPutOi) {}
    private record SignalResult(int score, String label, List<String> reasoning) {}

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

        SignalResult result = computeSignalScore(pcr, callChangePct, putChangePct, ltp, supportStrike, resistanceStrike);

        OiSignalResponse response = OiSignalResponse.builder()
                .dataAvailable(true)
                .index(INDEX)
                .ltp(ltp)
                .expiryLabel(expiry.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)))
                .pcr(round2(pcr))
                .totalCallOi(totalCallOi)
                .totalPutOi(totalPutOi)
                .callOiChangePct(round2(callChangePct))
                .putOiChangePct(round2(putChangePct))
                .supportStrike(supportStrike)
                .resistanceStrike(resistanceStrike)
                .signal(result.label())
                .signalScore(result.score())
                .reasoning(result.reasoning())
                .strikes(strikeOiList)
                .asOf(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();

        // Telegram alert disabled for now — re-enable by uncommenting the line below.
        // notifyIfSignalChanged(response);
        return response;
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
