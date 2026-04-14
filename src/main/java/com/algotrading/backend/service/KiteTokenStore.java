package com.algotrading.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Persists the Kite access token to a local file so it survives server restarts.
 *
 * Why a file and not an env var:
 *   - Kite access tokens expire every day. On Render (or any cloud host) you would
 *     need to SSH in and update an env var + restart the service every morning.
 *   - Instead, the token is set once via the UI (OAuth callback or manual paste),
 *     saved here, and loaded automatically on every subsequent restart — no env-var
 *     changes, no redeployments needed.
 *
 * File location:
 *   Default : ./data/kite-access-token.txt
 *   Override: KITE_TOKEN_FILE env var  (e.g. /data/kite-access-token.txt on Render
 *             with a persistent disk mounted at /data)
 *
 * Token lifecycle:
 *   - Written  : every time the user sets a new token via UI (OAuth or manual paste).
 *   - Read     : once at application startup in KiteAuthService @PostConstruct.
 *   - Deleted  : never automatically — the user sets a fresh token each morning,
 *                which overwrites the old file.
 */
@Service
@Slf4j
public class KiteTokenStore {

    @Value("${app.kite-token-file:/app/data/kite-access-token.txt}")
    private String tokenFilePath;

    /**
     * Save the token to disk. Called by KiteAuthService whenever the token is updated.
     * Errors are logged but never thrown — must not interrupt the OAuth/token-set flow.
     */
    public void save(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) return;
        try {
            File file = new File(tokenFilePath);
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), accessToken.trim(), StandardCharsets.UTF_8);
            log.info("Kite access token saved to {}", tokenFilePath);
        } catch (Exception e) {
            log.error("Failed to persist Kite access token: {}", e.getMessage());
        }
    }

    /**
     * Load the previously saved token from disk.
     * Returns empty if no token file exists (first-time setup, or file was deleted).
     */
    public Optional<String> load() {
        File file = new File(tokenFilePath);
        if (!file.exists()) return Optional.empty();
        try {
            String token = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
            if (token.isBlank()) return Optional.empty();
            log.info("Kite access token loaded from {}", tokenFilePath);
            return Optional.of(token);
        } catch (Exception e) {
            log.error("Failed to load Kite access token from file: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Delete the saved token (e.g. when user explicitly disconnects Kite). */
    public void clear() {
        File file = new File(tokenFilePath);
        if (file.exists() && file.delete()) {
            log.info("Kite access token file cleared");
        }
    }
}
