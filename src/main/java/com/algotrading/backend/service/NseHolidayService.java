package com.algotrading.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * NSE F&O trading holidays, fetched live from NSE's holiday-master API so weekly-expiry math
 * (e.g. "Tuesday is a holiday → expiry moves to Monday") follows the exchange's published list
 * instead of a hand-maintained table.
 *
 * Fetched on startup and refreshed daily at 08:00 IST. If NSE is unreachable (it blocks
 * non-browser clients intermittently), the last successful fetch is kept; before any fetch has
 * succeeded, the built-in {@link #FALLBACK_HOLIDAYS} table is used.
 *
 * Deliberately uses a direct OkHttpClient, not the Kite static-IP proxy client.
 */
@Service
@Slf4j
public class NseHolidayService {

    // NSE's bot protection 403s the API unless cookies come from a real page visit first —
    // priming with the holidays page (and sending it as Referer) is what reliably works.
    private static final String NSE_HOME = "https://www.nseindia.com/resources/exchange-communication-holidays";
    private static final String NSE_HOLIDAY_API = "https://www.nseindia.com/api/holiday-master?type=trading";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final DateTimeFormatter NSE_DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    /**
     * Used only until the first successful NSE fetch. 2026 = NSE's published F&O list (as fetched
     * 2026-10-16); 2027 entries are provisional until NSE publishes that year's list.
     */
    private static final Set<LocalDate> FALLBACK_HOLIDAYS = Set.of(
        LocalDate.of(2026, 1, 15),
        LocalDate.of(2026, 1, 26),
        LocalDate.of(2026, 3, 3),
        LocalDate.of(2026, 3, 26),
        LocalDate.of(2026, 3, 31),
        LocalDate.of(2026, 4, 3),
        LocalDate.of(2026, 4, 14),
        LocalDate.of(2026, 5, 1),
        LocalDate.of(2026, 5, 28),
        LocalDate.of(2026, 6, 26),
        LocalDate.of(2026, 9, 14),
        LocalDate.of(2026, 10, 2),
        LocalDate.of(2026, 10, 20),
        LocalDate.of(2026, 11, 10),
        LocalDate.of(2026, 11, 24),
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient client;

    private volatile Set<LocalDate> holidays = FALLBACK_HOLIDAYS;
    private volatile boolean fetchedFromNse = false;

    public NseHolidayService() {
        Map<String, List<Cookie>> cookieStore = new ConcurrentHashMap<>();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                // NSE's API only answers requests carrying the cookies set by its homepage.
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        cookieStore.put(url.host(), new ArrayList<>(cookies));
                    }

                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        return cookieStore.getOrDefault(url.host(), Collections.emptyList());
                    }
                })
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        refresh();
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Kolkata")
    public void scheduledRefresh() {
        refresh();
    }

    /** True if the date is a weekend or an NSE F&O trading holiday. */
    public boolean isNonTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY || holidays.contains(date);
    }

    public boolean isHoliday(LocalDate date) {
        return holidays.contains(date);
    }

    public Set<LocalDate> getHolidays() {
        return holidays;
    }

    public boolean isFetchedFromNse() {
        return fetchedFromNse;
    }

    /** Re-fetches the holiday list from NSE. Returns the number of holidays loaded (0 on failure). */
    public synchronized int refresh() {
        try {
            Set<LocalDate> fetched = fetchFromNse();
            if (fetched.isEmpty()) {
                log.warn("NSE holiday fetch returned no F&O holidays — keeping {} list ({} dates)",
                        fetchedFromNse ? "previous NSE" : "built-in fallback", holidays.size());
                return 0;
            }
            // NSE publishes only the current calendar year — keep fallback dates for any other year
            // (e.g. early-January expiries computed in late December).
            Set<Integer> fetchedYears = new HashSet<>();
            fetched.forEach(d -> fetchedYears.add(d.getYear()));
            Set<LocalDate> merged = new HashSet<>(fetched);
            FALLBACK_HOLIDAYS.stream().filter(d -> !fetchedYears.contains(d.getYear())).forEach(merged::add);
            holidays = Collections.unmodifiableSet(merged);
            fetchedFromNse = true;
            log.info("NSE F&O holidays loaded from NSE: {} dates {}", fetched.size(), fetched.stream().sorted().toList());
            return fetched.size();
        } catch (Exception e) {
            log.warn("NSE holiday fetch failed ({}) — keeping {} list ({} dates)", e.getMessage(),
                    fetchedFromNse ? "previous NSE" : "built-in fallback", holidays.size());
            return 0;
        }
    }

    private Set<LocalDate> fetchFromNse() throws Exception {
        // Prime cookies from the homepage first; NSE returns 401/403 to cookieless API calls.
        try (Response home = client.newCall(browserRequest(NSE_HOME,
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")).execute()) {
            if (!home.isSuccessful()) {
                log.debug("NSE homepage returned HTTP {}", home.code());
            }
        }
        try (Response resp = client.newCall(browserRequest(NSE_HOLIDAY_API, "*/*")).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IllegalStateException("HTTP " + resp.code());
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            // "FO" = equity derivatives segment (what NIFTY/BANKNIFTY options follow); "CM" as backup.
            JsonNode list = root.has("FO") ? root.get("FO") : root.get("CM");
            Set<LocalDate> result = new HashSet<>();
            if (list != null && list.isArray()) {
                for (JsonNode n : list) {
                    String date = n.path("tradingDate").asText("");
                    if (date.isEmpty()) continue;
                    try {
                        result.add(LocalDate.parse(date, NSE_DATE_FMT));
                    } catch (Exception parseErr) {
                        log.debug("Skipping unparseable NSE holiday date '{}'", date);
                    }
                }
            }
            return result;
        }
    }

    private Request browserRequest(String url, String accept) {
        return new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", NSE_HOME)
                .build();
    }
}
