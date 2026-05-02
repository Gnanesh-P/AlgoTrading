package com.algotrading.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class TelegramService {

    @Value("${app.telegram.bot-token:8643844696:AAEXrnBEu7uXn8LlNIna06mglD473wed7Pk}")
    private String botToken;

    @Value("${app.telegram.chat-id:-5270751014}")
    private String chatId;

    private final RestTemplate restTemplate = new RestTemplate();

    public void sendStrategyMessage(String message) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            Map<String, String> request = new HashMap<>();
            request.put("chat_id", chatId);
            request.put("text", message);
            request.put("parse_mode", "HTML");
            restTemplate.postForObject(url, request, String.class);
        } catch (Exception e) {
            log.error("Telegram message failed: {}", e.getMessage());
        }
    }
}
