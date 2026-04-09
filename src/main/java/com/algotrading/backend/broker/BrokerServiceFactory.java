package com.algotrading.backend.broker;

import com.algotrading.backend.model.TradeMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BrokerServiceFactory {

    private final PaperBrokerService paperBrokerService;
    private final LiveBrokerService liveBrokerService;

    public BrokerService getBrokerService(TradeMode mode) {
        return mode == TradeMode.LIVE ? liveBrokerService : paperBrokerService;
    }
}
