package com.algotrading.backend.controller;

import com.algotrading.backend.service.MarketQuotesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/quotes")
@RequiredArgsConstructor
public class MarketQuotesController {

    private final MarketQuotesService quotesService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getQuotes() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(55, TimeUnit.SECONDS))
            .body(quotesService.getQuotes());
    }
}
