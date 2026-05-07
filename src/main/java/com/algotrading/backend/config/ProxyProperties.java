package com.algotrading.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "proxy")
@Getter
@Setter
public class ProxyProperties {
    private boolean enabled = true;
    private String host;
    private int port = 443;
    private String username;
    private String password;
}
