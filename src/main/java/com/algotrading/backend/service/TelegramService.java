package com.algotrading.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class TelegramService {

    private final RestTemplate restTemplate = new RestTemplate();

    public TelegramService() {}

    public void sendStrategyMessage(String message) {
        String chatTokens = "-5270751014";
        String botId = "8643844696:AAEXrnBEu7uXn8LlNIna06mglD473wed7Pk";
        try {
            String url = "https://api.telegram.org/bot" + botId + "/sendMessage";

            Map<String, String> request = new HashMap<>();
            request.put("chat_id", chatTokens);
            request.put("text", message);
            request.put("parse_mode", "HTML");

            restTemplate.postForObject(url, request, String.class);
        } catch (Exception e) {
            log.error("Telegram message failed: {}", e.getMessage());
        }
    }
}
