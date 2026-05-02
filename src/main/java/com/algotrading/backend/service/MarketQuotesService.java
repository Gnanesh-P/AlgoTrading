package com.algotrading.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MarketQuotesService {

    private static final long CACHE_TTL_MS = 60_000;

    // Ordered for consistent display
    private static final List<String[]> SYMBOLS = List.of(
        new String[]{"^NSEI",     "NIFTY 50"},
        new String[]{"^NSEBANK",  "BANKNIFTY"},
        new String[]{"^BSESN",    "SENSEX"},
        new String[]{"^INDIAVIX", "INDIA VIX"}
    );

    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile List<Map<String, Object>> cached = null;
    private volatile long cachedAt = 0;

    public List<Map<String, Object>> getQuotes() {
        if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return cached;
        }
        try {
            List<Map<String, Object>> result = SYMBOLS.parallelStream()
                .map(pair -> fetchSymbol(pair[0], pair[1]))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            if (!result.isEmpty()) {
                cached = result;
                cachedAt = System.currentTimeMillis();
            }
        } catch (Exception e) {
            log.warn("Market quotes refresh failed: {}", e.getMessage());
        }
        return cached != null ? cached : fallback();
    }

    private Map<String, Object> fetchSymbol(String symbol, String displayName) {
        try {
            String encoded = symbol.replace("^", "%5E");
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                + encoded + "?interval=1d&range=1d&includePrePost=false";

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0");
            headers.set("Accept", "application/json");

            ResponseEntity<String> res = http.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode meta = mapper.readTree(res.getBody())
                .path("chart").path("result").get(0).path("meta");

            double price    = meta.path("regularMarketPrice").asDouble();
            double prev     = meta.path("chartPreviousClose").asDouble();
            double changePct = prev > 0 ? ((price - prev) / prev * 100.0) : 0.0;
            boolean up      = changePct >= 0;

            Map<String, Object> q = new LinkedHashMap<>();
            q.put("symbol", displayName);
            q.put("price",  formatPrice(price, displayName));
            q.put("change", String.format("%s%.2f%%", up ? "+" : "", changePct));
            q.put("up",     up);
            return q;

        } catch (Exception e) {
            log.debug("Failed to fetch {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private String formatPrice(double price, String name) {
        if (name.contains("VIX")) return String.format("%.2f", price);
        // Indian number format: 80,420.50
        return String.format("%,.2f", price);
    }

    private List<Map<String, Object>> fallback() {
        return List.of(
            Map.of("symbol", "NIFTY 50",  "price", "N/A", "change", "N/A", "up", true),
            Map.of("symbol", "BANKNIFTY", "price", "N/A", "change", "N/A", "up", true),
            Map.of("symbol", "SENSEX",    "price", "N/A", "change", "N/A", "up", true),
            Map.of("symbol", "INDIA VIX", "price", "N/A", "change", "N/A", "up", false)
        );
    }
}
