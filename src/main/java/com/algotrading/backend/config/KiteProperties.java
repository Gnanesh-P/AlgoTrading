package com.algotrading.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "kite")
@Data
public class KiteProperties {
    private String apiKey;
    private String apiSecret;
    private volatile String accessToken;
    private String redirectUrl;
    private String baseUrl;
    private String loginUrl;

    public boolean isConnected() {
        return accessToken != null && !accessToken.isBlank();
    }
}
