package com.algotrading.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionChainResponse {
    private String futuresInstrument;
    private double niftyPrice;
    private int atmStrike;
    private List<StrikeData> strikes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StrikeData {
        private int    strikePrice;
        private String ceInstrument;
        private long   ceToken;          // instrument token for KiteTicker subscription
        private String peInstrument;
        private long   peToken;          // instrument token for KiteTicker subscription
        private double ceLtp;
        private double peLtp;
        private boolean isAtm;
        private String expiryType;
    }
}
