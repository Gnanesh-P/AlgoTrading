package com.algotrading.backend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;


@SpringBootApplication
@EnableScheduling
@Slf4j
public class AlgoTradingApplication {
    public static void main(String[] args) {
        log.info("Enable the logs");
        try {

            // IMPORTANT
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

            // Proxy Details
            String proxyHost = "linux.tradingip.in";
            int proxyPort = 443;

            String proxyUser = "ZerodhahjgjsdkhdfFo3G4bDi";
            String proxyPassword = "testHGGjdsj4c7ea9a6be5d872";

            // HTTP CONNECT Proxy
            Proxy proxy = new Proxy(
                    Proxy.Type.HTTP,
                    new InetSocketAddress(proxyHost, proxyPort)
            );

            // OkHttp Client
            OkHttpClient client = new OkHttpClient.Builder()

                    .proxy(proxy)

                    .proxyAuthenticator((route, response) -> {

                        // Avoid infinite auth loop
                        if (response.request().header("Proxy-Authorization") != null) {
                            return null;
                        }

                        String credential =
                                Credentials.basic(proxyUser, proxyPassword);

                        return response.request()
                                .newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build();
                    })

                    .connectTimeout(Duration.ofSeconds(30))
                    .readTimeout(Duration.ofSeconds(30))
                    .build();

            // Test Request
            Request request = new Request.Builder()
                    .url("https://ifconfig.me/ip")
                    .get()
                    .build();

            System.out.println("Sending request through proxy...");

            try (Response response = client.newCall(request).execute()) {

                System.out.println("HTTP Status : " + response.code());

                if (response.body() != null) {
                    String body = response.body().string();

                    System.out.println("Response:");
                    System.out.println(body);
                }

                if (response.isSuccessful()) {
                    System.out.println("PROXY WORKING SUCCESSFULLY");
                } else {
                    System.out.println("PROXY FAILED");
                }
            }

        } catch (Exception e) {

            System.out.println("ERROR OCCURRED");
            e.printStackTrace();
        }
        SpringApplication.run(AlgoTradingApplication.class, args);
    }
}



