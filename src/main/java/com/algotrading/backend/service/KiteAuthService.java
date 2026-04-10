package com.algotrading.backend.service;

import com.algotrading.backend.config.KiteProperties;
import com.zerodhatech.kiteconnect.KiteConnect;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Service
@Slf4j
public class KiteAuthService {

    private final KiteProperties kite;
    private final RestTemplate restTemplate;
    private final KiteConnect kiteConnect;
    private final KiteTickerService kiteTickerService;
    private final KiteTokenStore tokenStore;

    public KiteAuthService(KiteProperties kite,
                           RestTemplate restTemplate,
                           KiteConnect kiteConnect,
                           KiteTokenStore tokenStore,
                           @Lazy KiteTickerService kiteTickerService) {
        this.kite = kite;
        this.restTemplate = restTemplate;
        this.kiteConnect = kiteConnect;
        this.tokenStore = tokenStore;
        this.kiteTickerService = kiteTickerService;
    }

    /**
     * On startup: load a previously saved token from disk and activate it.
     *
     * This means after a Render restart (or any server restart), the Kite WebSocket
     * reconnects automatically — no need for the user to paste the token again.
     * The user only needs to set the token once per day (Kite tokens expire at midnight).
     */
    @PostConstruct
    public void restoreTokenOnStartup() {
        tokenStore.load().ifPresentOrElse(
            savedToken -> {
                log.info("Restoring Kite access token from disk — auto-connecting WebSocket...");
                applyToken(savedToken);          // sets on KiteProperties + KiteConnect + starts WebSocket
            },
            () -> log.info("No saved Kite access token found — user must connect via UI")
        );
    }

    /** Returns the URL the user should open to log in with Kite */
    public String getLoginUrl() {
        return kite.getLoginUrl() + "?v=3&api_key=" + kite.getApiKey();
    }

    /**
     * Exchange the request_token (from Kite OAuth callback) for an access_token.
     * Checksum = SHA256(api_key + request_token + api_secret)
     */
    public String exchangeToken(String requestToken) {
        String checksum = sha256(kite.getApiKey() + requestToken + kite.getApiSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("X-Kite-Version", "3");
        headers.set("Authorization", "token " + kite.getApiKey() + ":" + "");

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("api_key", kite.getApiKey());
        body.add("request_token", requestToken);
        body.add("checksum", checksum);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    kite.getBaseUrl() + "/session/token",
                    HttpMethod.POST, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
                if (data != null && data.get("access_token") != null) {
                    String accessToken = data.get("access_token").toString();
                    log.info("Kite access token obtained via OAuth for user: {}", data.get("user_id"));
                    applyToken(accessToken);
                    return accessToken;
                }
            }
            throw new RuntimeException("Token exchange failed: " + response.getBody());
        } catch (Exception e) {
            log.error("Kite token exchange error: {}", e.getMessage());
            throw new RuntimeException("Kite authentication failed: " + e.getMessage());
        }
    }

    /** Manually set access token from UI (skip OAuth flow). Saves to disk + starts WebSocket. */
    public void setAccessToken(String accessToken) {
        log.info("Kite access token set manually via UI");
        applyToken(accessToken);
    }

    /**
     * Central method: activate a token.
     * Sets it on KiteProperties (for REST calls), KiteConnect (for SDK calls),
     * saves it to disk (for restart recovery), and starts the WebSocket ticker.
     *
     * All token entry points (OAuth, manual paste, startup restore) funnel here.
     */
    private void applyToken(String accessToken) {
        kite.setAccessToken(accessToken);
        kiteConnect.setAccessToken(accessToken);
        tokenStore.save(accessToken);              // persist → survives restart
        kiteTickerService.onTokenExchanged();      // (re)connect WebSocket
    }

    public boolean isConnected() {
        return kite.isConnected();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA256 failed", e);
        }
    }
}
