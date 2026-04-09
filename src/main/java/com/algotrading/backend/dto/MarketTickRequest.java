package com.algotrading.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MarketTickRequest {
    @NotBlank
    private String instrument;
    @Positive
    private double lastPrice;
    private double openPrice;
    private double highPrice;
    private double lowPrice;
    private long volume;
    private LocalDateTime timestamp;
}
