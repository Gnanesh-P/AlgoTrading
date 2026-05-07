package com.algotrading.backend.config;

import com.zerodhatech.kiteconnect.KiteConnect;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.*;
import java.time.Duration;

@Configuration
@Slf4j
public class AppConfig {

    private final ProxyProperties proxy;

    public AppConfig(ProxyProperties proxy) {
        this.proxy = proxy;
    }

    @PostConstruct
    public void initProxy() {
        if (!proxy.isEnabled()) return;
        String host = proxy.getHost();
        String port = String.valueOf(proxy.getPort());

        // JDK 8u111+ disables BASIC auth for HTTPS CONNECT tunnels by default.
        // Empty string re-enables it so HttpURLConnection (KiteAuthService RestTemplate) can authenticate with the proxy.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes",  "");

        // NOTE: http/https.proxyHost system properties are intentionally NOT set here.
        // Setting them would route ALL HttpURLConnection traffic (Telegram, Yahoo Finance etc.)
        // through the proxy. Instead, each Kite-specific component gets an explicit Proxy object.

        // Java Authenticator — handles proxy BASIC auth for URLConnection (KiteAuthService RestTemplate)
        String user = proxy.getUsername();
        String pwd  = proxy.getPassword();
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() == RequestorType.PROXY) {
                    return new PasswordAuthentication(user, pwd.toCharArray());
                }
                return null;
            }
        });
        log.info("JVM proxy configured → {}:{}", host, port);
        logProxyOutgoingIp();
    }

    private static final String[] IP_CHECK_URLS = {
        "http://api.ipify.org/",
        "http://ifconfig.me/ip",
        "http://checkip.amazonaws.com/",
        "http://ipecho.net/plain",
        "http://ip.42.pl/raw"
    };

    private void logProxyOutgoingIp() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(5_000);
        RestTemplate checker = new RestTemplate(factory);

        String staticIp = null;
        String usedUrl  = null;
        for (String url : IP_CHECK_URLS) {
            try {
                String response = checker.getForObject(url, String.class);
                if (response != null && !response.isBlank()) {
                    staticIp = response.trim();
                    usedUrl  = url;
                    break;
                }
            } catch (Exception ignored) {
                // try next
            }
        }

        if (staticIp != null) {
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("  PROXY CONNECTED  (via {})", usedUrl);
            log.info("  Proxy host   : {}:{}", proxy.getHost(), proxy.getPort());
            log.info("  Outgoing IP  : {}  ← whitelist this at Zerodha", staticIp);
            log.info("  Used for     : Kite REST API (orders / LTP / auth)");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        } else {
            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.warn("  PROXY CHECK FAILED — could not reach any IP-check service");
            log.warn("  Proxy host   : {}:{}", proxy.getHost(), proxy.getPort());
            log.warn("  Orders may be rejected if Zerodha IP whitelist is enforced");
            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        if (!proxy.isEnabled()) {
            return builder
                    .setConnectTimeout(Duration.ofSeconds(10))
                    .setReadTimeout(Duration.ofSeconds(30))
                    .build();
        }
        // Explicit proxy on the factory so every RestTemplate call routes through the static IP
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return new RestTemplate(factory);
    }

    @Bean
    public KiteConnect kiteConnect(KiteProperties kiteProperties) throws Exception {
        // Pass java.net.Proxy to the SDK constructor — KiteRequestHandler sets it on OkHttpClient
        Proxy javaProxy = proxy.isEnabled()
                ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.getHost(), proxy.getPort()))
                : null;
        KiteConnect kite = new KiteConnect(kiteProperties.getApiKey(), javaProxy, false);
        if (proxy.isEnabled()) {
            injectProxyAuthenticator(kite);
        }
        return kite;
    }

    /**
     * KiteRequestHandler builds the OkHttpClient without a proxyAuthenticator.
     * This reflects into client field and rebuilds it with BASIC proxy auth so
     * the SDK can authenticate with the proxy on every REST call.
     */
    private void injectProxyAuthenticator(KiteConnect kite) {
        try {
            Field handlerField = KiteConnect.class.getDeclaredField("kiteRequestHandler");
            handlerField.setAccessible(true);
            Object handler = handlerField.get(kite);

            Field clientField = handler.getClass().getDeclaredField("client");
            clientField.setAccessible(true);
            OkHttpClient existing = (OkHttpClient) clientField.get(handler);

            String user = proxy.getUsername();
            String pwd  = proxy.getPassword();
            OkHttpClient withAuth = existing.newBuilder()
                    .proxyAuthenticator((route, response) ->
                            response.request().newBuilder()
                                    .header("Proxy-Authorization", Credentials.basic(user, pwd))
                                    .build())
                    .build();

            clientField.set(handler, withAuth);
            log.info("Proxy authenticator injected into KiteConnect OkHttpClient");
        } catch (Exception e) {
            log.error("Failed to inject proxyAuthenticator into KiteConnect: {}", e.getMessage());
        }
    }
}
