package com.algotrading.backend.service;

import com.algotrading.backend.config.KiteProperties;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.ticker.KiteTicker;
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

    public KiteAuthService(KiteProperties kite,
                           RestTemplate restTemplate,
                           KiteConnect kiteConnect,
                           @Lazy KiteTickerService kiteTickerService) {
        this.kite = kite;
        this.restTemplate = restTemplate;
        this.kiteConnect = kiteConnect;
        this.kiteTickerService = kiteTickerService;
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
                    kite.setAccessToken(accessToken);
                    kiteConnect.setAccessToken(accessToken);
                    log.info("Kite access token obtained for user: {}", data.get("user_id"));
                    kiteTickerService.onTokenExchanged();
                    return accessToken;
                }
            }
            throw new RuntimeException("Token exchange failed: " + response.getBody());
        } catch (Exception e) {
            log.error("Kite token exchange error: {}", e.getMessage());
            throw new RuntimeException("Kite authentication failed: " + e.getMessage());
        }
    }

    /** Manually set access token (skip OAuth flow) */
    public void setAccessToken(String accessToken) {
        kite.setAccessToken(accessToken);
        kiteConnect.setAccessToken(accessToken);
        log.info("Kite access token set manually");
        kiteTickerService.onTokenExchanged();
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
