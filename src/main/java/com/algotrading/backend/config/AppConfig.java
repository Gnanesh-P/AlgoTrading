package com.algotrading.backend.config;

import com.zerodhatech.kiteconnect.KiteConnect;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
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

        if (!proxy.isEnabled()) {
            return;
        }

        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");

        log.info("Proxy Enabled -> {}:{}",
                proxy.getHost(),
                proxy.getPort());

        logProxyOutgoingIp();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Shared OkHttpClient with authenticated proxy
     */
    @Bean
    public OkHttpClient proxyOkHttpClient() {

        return buildProxyOkHttpClient();
    }

    /**
     * RestTemplate using OkHttp
     */
    @Bean
    public RestTemplate restTemplate(OkHttpClient proxyOkHttpClient) {

        OkHttp3ClientHttpRequestFactory factory =
                new OkHttp3ClientHttpRequestFactory(proxyOkHttpClient);

        return new RestTemplate(factory);
    }

    /**
     * Kite SDK with authenticated proxy injected
     */
    @Bean
    public KiteConnect kiteConnect(
            KiteProperties kiteProperties,
            OkHttpClient proxyOkHttpClient
    ) throws Exception {

        Proxy javaProxy = proxy.isEnabled()
                ? new Proxy(
                Proxy.Type.HTTP,
                new InetSocketAddress(
                        proxy.getHost(),
                        proxy.getPort()
                )
        )
                : null;

        KiteConnect kite = new KiteConnect(
                kiteProperties.getApiKey(),
                javaProxy,
                false
        );

        injectProxyClient(kite, proxyOkHttpClient);

        return kite;
    }

    /**
     * Inject custom authenticated OkHttpClient into Kite SDK
     */
    private void injectProxyClient(
            KiteConnect kite,
            OkHttpClient proxyOkHttpClient
    ) {

        try {

            Field handlerField =
                    KiteConnect.class.getDeclaredField("kiteRequestHandler");

            handlerField.setAccessible(true);

            Object handler = handlerField.get(kite);

            Field clientField =
                    handler.getClass().getDeclaredField("client");

            clientField.setAccessible(true);

            clientField.set(handler, proxyOkHttpClient);

            log.info("Authenticated OkHttpClient injected into Kite SDK");

        } catch (Exception e) {

            log.error("Failed to inject OkHttpClient into Kite SDK", e);
        }
    }

    /**
     * Verify proxy outgoing IP
     */
    private void logProxyOutgoingIp() {

        if (!proxy.isEnabled()) {
            return;
        }

        try {

            OkHttpClient client = buildProxyOkHttpClient();

            Request request = new Request.Builder()
                    .url("https://ifconfig.me/ip")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {

                if (response.body() != null) {

                    String ip = response.body()
                            .string()
                            .trim();

                    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    log.info("PROXY CONNECTED SUCCESSFULLY");
                    log.info("Proxy Host : {}:{}",
                            proxy.getHost(),
                            proxy.getPort());
                    log.info("Outgoing IP: {}", ip);
                    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                }
            }

        } catch (Exception e) {

            log.error("Proxy verification failed", e);
        }
    }

    private OkHttpClient buildProxyOkHttpClient() {

        if (!proxy.isEnabled()) {
            return new OkHttpClient();
        }

        Proxy javaProxy = new Proxy(
                Proxy.Type.HTTP,
                new InetSocketAddress(
                        proxy.getHost(),
                        proxy.getPort()
                )
        );

        String user = proxy.getUsername();
        String pwd = proxy.getPassword();

        return new OkHttpClient.Builder()

                .proxy(javaProxy)

                .proxyAuthenticator((route, response) -> {

                    if (response.request()
                            .header("Proxy-Authorization") != null) {
                        return null;
                    }

                    String credential =
                            Credentials.basic(user, pwd);

                    return response.request()
                            .newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                })

                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .retryOnConnectionFailure(true)
                .build();
    }
}