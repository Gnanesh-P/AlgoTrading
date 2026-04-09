package com.algotrading.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the list of valid trading-platform users.
 * Bound from the {@code app.credentials.users[]} block in application.yml.
 *
 * Environment-variable overrides (Render / Docker):
 *   APP_USERNAME  / APP_PASSWORD  — user 1 (the original single-user creds)
 *   APP_USER2     / APP_PASS2     — user 2
 *   APP_USER3     / APP_PASS3     — user 3
 *   APP_USER4     / APP_PASS4     — user 4
 *
 * Entries with a blank username are silently skipped at login time.
 */
@Component
@ConfigurationProperties(prefix = "app.credentials")
@Data
public class AppCredentialsProperties {

    /** List of user entries. Must match the YAML list structure. */
    private List<UserEntry> users = new ArrayList<>();

    @Data
    public static class UserEntry {
        private String username = "";
        private String password = "";

        public boolean isValid() {
            return username != null && !username.isBlank()
                && password != null && !password.isBlank();
        }
    }
}
