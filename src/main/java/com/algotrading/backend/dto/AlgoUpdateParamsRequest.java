package com.algotrading.backend.dto;

import lombok.Data;

@Data
public class AlgoUpdateParamsRequest {
    private double targetPrice;
    private double stopLoss;
    private boolean stopLossEnabled;
    private double trailingProfit;
}
