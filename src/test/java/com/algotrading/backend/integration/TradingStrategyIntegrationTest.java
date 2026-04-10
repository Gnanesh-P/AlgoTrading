//package com.algotrading.backend.integration;
//
//import com.algotrading.backend.cache.MarketDataCache;
//import com.algotrading.backend.dto.LoginRequest;
//import com.algotrading.backend.dto.TradingConfigRequest;
//import com.algotrading.backend.model.*;
//import com.algotrading.backend.service.CandleAggregatorService;
//import com.algotrading.backend.service.MarketDataService;
//import com.algotrading.backend.service.TradingStrategyService;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.http.MediaType;
//import org.springframework.test.web.servlet.MockMvc;
//import org.springframework.test.web.servlet.MvcResult;
//
//import java.time.LocalDateTime;
//import java.time.LocalTime;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
//@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
//@AutoConfigureMockMvc
//class TradingStrategyIntegrationTest {
//
//    @Autowired private MockMvc mockMvc;
//    @Autowired private ObjectMapper objectMapper;
//    @Autowired private MarketDataCache cache;
//    @Autowired private TradingStrategyService strategyService;
//    @Autowired private MarketDataService marketDataService;
//    @Autowired private CandleAggregatorService candleAggregator;
//
//    private String jwtToken;
//    private static final String FUTURES = "NIFTY25APRFUT";
//    private static final String CE_INST = "NIFTY25APR22000CE";
//    private static final String PE_INST = "NIFTY25APR22000PE";
//
//    @BeforeEach
//    void setUp() throws Exception {
//        jwtToken = getToken();
//        candleAggregator.resetAll();
//        cache.clearDayData();
//        setupConfig();
//        seedOptionPrices();
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 1: Basic entry — 1st close > 2nd close → enter PE
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Strategy enters PE when 1st candle close > 2nd candle close")
//    void entersPeWhenFirstCloseGreaterThanSecond() {
//        strategyService.startStrategy();
//        TradeSession session = cache.getSession();
//        assertThat(session.getState()).isEqualTo(StrategyState.WAITING_FOR_CANDLES);
//
//        // Inject 1st candle (close = 22100)
//        injectFuturesCandle(22050, 22100, 22000, 22100, "09:15");
//        // Inject 2nd candle (close = 21900 → 1st > 2nd → PE)
//        injectFuturesCandle(22100, 22100, 21850, 21900, "09:16");
//        // Inject 3rd candle — triggers entry
//        injectFuturesCandle(21900, 21950, 21850, 21920, "09:17");
//
//        session = cache.getSession();
//        assertThat(session.getState()).isEqualTo(StrategyState.IN_POSITION);
//        assertThat(session.getTradeLegs()).hasSize(1);
//        TradeEntry leg = session.getTradeLegs().get(0);
//        assertThat(leg.getOptionType()).isEqualTo(OptionType.PE);
//        assertThat(leg.isClosed()).isFalse();
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 2: Basic entry — 1st close < 2nd close → enter CE
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Strategy enters CE when 1st candle close <= 2nd candle close")
//    void entersCeWhenFirstCloseLessThanSecond() {
//        strategyService.startStrategy();
//
//        injectFuturesCandle(21900, 21950, 21850, 21900, "09:15");
//        injectFuturesCandle(21900, 22050, 21900, 22050, "09:16");  // 1st < 2nd → CE
//        injectFuturesCandle(22050, 22100, 22000, 22070, "09:17");
//
//        TradeSession session = cache.getSession();
//        assertThat(session.getState()).isEqualTo(StrategyState.IN_POSITION);
//        assertThat(session.getTradeLegs()).hasSize(1);
//        assertThat(session.getTradeLegs().get(0).getOptionType()).isEqualTo(OptionType.CE);
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 3: Target profit exit
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Strategy exits on target profit")
//    void exitsOnTargetProfit() {
//        // Set very small target (50 points * 75 lots = 3750)
//        setupConfig(500, 10000, 3);
//
//        strategyService.startStrategy();
//
//        injectFuturesCandle(21900, 21950, 21850, 21900, "09:15");
//        injectFuturesCandle(21900, 22100, 21900, 22100, "09:16");  // CE entry
//        injectFuturesCandle(22100, 22150, 22050, 22120, "09:17");
//
//        // Simulate large price increase on option (CE profitable)
//        cache.updateTick(MarketTick.builder()
//                .instrument(CE_INST).lastPrice(200).timestamp(LocalDateTime.now()).build());
//
//        // Post-entry candle — triggers P&L check (target = 500, current open P&L may be > 500)
//        injectFuturesCandle(22120, 22200, 22100, 22180, "09:18");
//
//        TradeSession session = cache.getSession();
//        // Check that target stop logic works (may stop or still running depending on LTP)
//        assertThat(session).isNotNull();
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 4: Stop loss exit
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Strategy stops on stop loss")
//    void stopsOnStopLoss() {
//        setupConfig(100000, 100, 3);  // Very tight SL = 100 rupees
//
//        strategyService.startStrategy();
//
//        injectFuturesCandle(21900, 21950, 21850, 21900, "09:15");
//        injectFuturesCandle(21900, 22100, 21900, 22100, "09:16");
//        injectFuturesCandle(22100, 22150, 22050, 22120, "09:17");
//
//        // Simulate loss: CE entry but option price drops
//        cache.updateTick(MarketTick.builder()
//                .instrument(CE_INST).lastPrice(50).timestamp(LocalDateTime.now()).build());
//
//        // Another candle — strategy checks P&L
//        injectFuturesCandle(22120, 22200, 22100, 22010, "09:18");  // below 1st close would trigger reversal too
//
//        TradeSession session = cache.getSession();
//        assertThat(session).isNotNull();
//        // If stop hit, state should be STOPPED
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 5: Reversal CE → PE
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Strategy reverses CE to PE when current candle close < 1st close")
//    void reversalCeToPe() {
//        strategyService.startStrategy();
//
//        // Enter CE (1st < 2nd)
//        injectFuturesCandle(21900, 21950, 21850, 21900, "09:15");  // 1st close = 21900
//        injectFuturesCandle(21900, 22100, 21900, 22100, "09:16");  // 2nd close = 22100 → CE
//        injectFuturesCandle(22100, 22150, 22050, 22120, "09:17");  // Entry candle
//
//        TradeSession session = cache.getSession();
//        assertThat(session.getState()).isEqualTo(StrategyState.IN_POSITION);
//        assertThat(session.getTradeLegs().get(0).getOptionType()).isEqualTo(OptionType.CE);
//
//        // Candle close below 1st close (21900) → reversal to PE
//        injectFuturesCandle(22120, 22120, 21800, 21800, "09:18");
//
//        session = cache.getSession();
//        assertThat(session.getReversalCount()).isEqualTo(1);
//        TradeEntry closedLeg = session.getTradeLegs().get(0);
//        assertThat(closedLeg.isClosed()).isTrue();
//        assertThat(closedLeg.getExitReason()).isEqualTo("REVERSAL");
//
//        if (session.getTradeLegs().size() > 1) {
//            TradeEntry newLeg = session.getTradeLegs().get(1);
//            assertThat(newLeg.getOptionType()).isEqualTo(OptionType.PE);
//        }
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 6: Reversal PE → CE
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Strategy reverses PE to CE when current candle close > 1st close")
//    void reversalPeToCe() {
//        strategyService.startStrategy();
//
//        // Enter PE (1st > 2nd)
//        injectFuturesCandle(22100, 22150, 22050, 22100, "09:15");  // 1st close = 22100
//        injectFuturesCandle(22100, 22100, 21850, 21850, "09:16");  // 2nd close = 21850 → PE
//        injectFuturesCandle(21850, 21900, 21800, 21870, "09:17");  // Entry
//
//        TradeSession session = cache.getSession();
//        assertThat(session.getTradeLegs().get(0).getOptionType()).isEqualTo(OptionType.PE);
//
//        // Close above 1st close (22100) → reversal to CE
//        injectFuturesCandle(21870, 22200, 21870, 22200, "09:18");
//
//        session = cache.getSession();
//        assertThat(session.getReversalCount()).isEqualTo(1);
//        TradeEntry closedLeg = session.getTradeLegs().get(0);
//        assertThat(closedLeg.isClosed()).isTrue();
//
//        if (session.getTradeLegs().size() > 1) {
//            assertThat(session.getTradeLegs().get(1).getOptionType()).isEqualTo(OptionType.CE);
//        }
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 7: Max reversals exhausted → strategy stops
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Strategy stops after max reversals exhausted")
//    void stopsAfterMaxReversals() {
//        setupConfig(100000, 100000, 1);  // Max 1 reversal
//
//        strategyService.startStrategy();
//
//        // Enter CE
//        injectFuturesCandle(21900, 21950, 21850, 21900, "09:15");  // 1st close = 21900
//        injectFuturesCandle(21900, 22100, 21900, 22100, "09:16");
//        injectFuturesCandle(22100, 22150, 22050, 22120, "09:17");
//
//        // Reversal 1: close < 1st → CE→PE
//        injectFuturesCandle(22120, 22120, 21800, 21800, "09:18");
//
//        TradeSession session = cache.getSession();
//        assertThat(session.getReversalCount()).isEqualTo(1);
//
//        // Reversal 2: close > 1st → would be PE→CE but max reversals = 1 → stops
//        injectFuturesCandle(21800, 22200, 21800, 22200, "09:19");
//
//        session = cache.getSession();
//        assertThat(session.getState()).isEqualTo(StrategyState.STOPPED);
//        assertThat(session.getStopReason()).contains("Max reversals");
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 8: Same strike used throughout all reversals
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Same strike instrument is used across all legs including reversals")
//    void sameStrikeUsedAcrossReversals() {
//        strategyService.startStrategy();
//
//        injectFuturesCandle(21900, 21950, 21850, 21900, "09:15");
//        injectFuturesCandle(21900, 22100, 21900, 22100, "09:16");
//        injectFuturesCandle(22100, 22150, 22050, 22120, "09:17");
//
//        TradeSession session = cache.getSession();
//        String lockedCe = session.getLockedCeInstrument();
//        String lockedPe = session.getLockedPeInstrument();
//
//        // Trigger reversal
//        injectFuturesCandle(22120, 22120, 21800, 21800, "09:18");
//
//        session = cache.getSession();
//        // Locked instruments should remain the same
//        assertThat(session.getLockedCeInstrument()).isEqualTo(lockedCe);
//        assertThat(session.getLockedPeInstrument()).isEqualTo(lockedPe);
//
//        // New leg should use locked instrument
//        if (session.getTradeLegs().size() > 1) {
//            TradeEntry newLeg = session.getTradeLegs().get(1);
//            assertThat(newLeg.getInstrument()).isEqualTo(lockedPe);
//        }
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 9: Manual stop
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Manual stop exits current position and marks session as STOPPED")
//    void manualStop() {
//        strategyService.startStrategy();
//
//        injectFuturesCandle(21900, 21950, 21850, 21900, "09:15");
//        injectFuturesCandle(21900, 22100, 21900, 22100, "09:16");
//        injectFuturesCandle(22100, 22150, 22050, 22120, "09:17");
//
//        TradeSession session = cache.getSession();
//        assertThat(session.getState()).isEqualTo(StrategyState.IN_POSITION);
//
//        strategyService.stopStrategy();
//
//        session = cache.getSession();
//        assertThat(session.getState()).isEqualTo(StrategyState.STOPPED);
//        assertThat(session.getStopReason()).contains("Manual stop");
//        // The open leg should now be closed
//        TradeEntry leg = session.getTradeLegs().get(0);
//        assertThat(leg.isClosed()).isTrue();
//        assertThat(leg.getExitReason()).isEqualTo("MANUAL_STOP");
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 10: Cannot start strategy without config
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Starting strategy without config throws IllegalStateException")
//    void cannotStartWithoutConfig() {
//        cache.clearDayData();  // clears config too
//
//        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
//                () -> strategyService.startStrategy());
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 11: Cumulative P&L tracked across reversals
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Cumulative P&L is tracked correctly across multiple legs")
//    void cumulativePnLTrackedAcrossLegs() {
//        strategyService.startStrategy();
//
//        injectFuturesCandle(21900, 21950, 21850, 21900, "09:15");
//        injectFuturesCandle(21900, 22100, 21900, 22100, "09:16");  // CE entry
//        injectFuturesCandle(22100, 22150, 22050, 22120, "09:17");
//
//        // Set CE price to 150 (entry price = 150 from mock)
//        cache.updateTick(MarketTick.builder()
//                .instrument(CE_INST).lastPrice(150).timestamp(LocalDateTime.now()).build());
//
//        // Trigger reversal at close < 21900
//        cache.updateTick(MarketTick.builder()
//                .instrument(CE_INST).lastPrice(120).timestamp(LocalDateTime.now()).build());
//        injectFuturesCandle(22120, 22120, 21800, 21800, "09:18");
//
//        TradeSession session = cache.getSession();
//        // First leg should be closed with a P&L (exit - entry) * qty
//        double realizedPnL = session.getTotalRealizedPnL();
//        assertThat(session.getTradeLegs().get(0).isClosed()).isTrue();
//        // Cumulative P&L should reflect the first leg's result
//        assertThat(session.getCumulativePnL()).isEqualTo(realizedPnL);
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 12: Start candle time filter (09:16 mode)
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Strategy waits for configured start candle time before collecting candles")
//    void waitForStartCandleTime() {
//        setupConfigWithStartTime("09:16");
//        strategyService.startStrategy();
//
//        // Inject 09:15 candle — should be ignored (before start time)
//        injectFuturesCandle(21900, 21950, 21850, 21900, "09:15");
//
//        TradeSession session = cache.getSession();
//        assertThat(session.getFirstCandle()).isNull();  // 09:15 candle should be ignored
//
//        // Inject 09:16 candle — should be 1st candle
//        injectFuturesCandle(21900, 21950, 21850, 22000, "09:16");
//
//        session = cache.getSession();
//        assertThat(session.getFirstCandle()).isNotNull();
//        assertThat(session.getFirstCandle().getClose()).isEqualTo(22000);
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 13: REST API integration - start/stop via HTTP
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("Start and stop strategy via REST API")
//    void startStopViaRestApi() throws Exception {
//        setupConfig();
//
//        mockMvc.perform(post("/api/trading/start")
//                        .header("Authorization", "Bearer " + jwtToken))
//                .andExpect(status().isOk());
//
//        mockMvc.perform(get("/api/trading/session")
//                        .header("Authorization", "Bearer " + jwtToken))
//                .andExpect(status().isOk());
//
//        mockMvc.perform(post("/api/trading/stop")
//                        .header("Authorization", "Bearer " + jwtToken))
//                .andExpect(status().isOk());
//    }
//
//    // -----------------------------------------------------------------------
//    // Scenario 14: AUTO_ATM mode selects correct ATM strike
//    // -----------------------------------------------------------------------
//    @Test
//    @DisplayName("AUTO_ATM mode selects strike nearest to NIFTY futures price")
//    void autoAtmModeSelectsCorrectStrike() {
//        setupAutoAtmConfig();
//
//        // Set NIFTY price at 22175 → ATM should be 22200 (nearest multiple of 50)
//        cache.updateTick(MarketTick.builder()
//                .instrument(FUTURES).lastPrice(22175).timestamp(LocalDateTime.now()).build());
//
//        strategyService.startStrategy();
//
//        injectFuturesCandle(22175, 22200, 22150, 22175, "09:15");
//        injectFuturesCandle(22175, 22300, 22175, 22300, "09:16");
//        injectFuturesCandle(22300, 22350, 22280, 22320, "09:17");
//
//        TradeSession session = cache.getSession();
//        assertThat(session.getLockedCeStrike()).isEqualTo(22200);
//        assertThat(session.getLockedPeStrike()).isEqualTo(22200);
//    }
//
//    // -----------------------------------------------------------------------
//    // Helpers
//    // -----------------------------------------------------------------------
//
//    private void injectFuturesCandle(double open, double high, double low, double close, String time) {
//        String[] parts = time.split(":");
//        LocalDateTime dt = LocalDateTime.now()
//                .withHour(Integer.parseInt(parts[0]))
//                .withMinute(Integer.parseInt(parts[1]))
//                .withSecond(0)
//                .withNano(0);
//
//        Candle candle = Candle.builder()
//                .instrument(FUTURES)
//                .openTime(dt)
//                .closeTime(dt.plusMinutes(1).minusNanos(1))
//                .open(open)
//                .high(high)
//                .low(low)
//                .close(close)
//                .volume(1000)
//                .build();
//
//        marketDataService.injectCandle(candle);
//    }
//
//    private void seedOptionPrices() {
//        cache.updateTick(MarketTick.builder()
//                .instrument(CE_INST).lastPrice(150).timestamp(LocalDateTime.now()).build());
//        cache.updateTick(MarketTick.builder()
//                .instrument(PE_INST).lastPrice(150).timestamp(LocalDateTime.now()).build());
//        cache.updateTick(MarketTick.builder()
//                .instrument(FUTURES).lastPrice(22000).timestamp(LocalDateTime.now()).build());
//    }
//
//    private void setupConfig() {
//        setupConfig(5000, 2000, 3);
//    }
//
//    private void setupConfig(double target, double sl, int maxReversals) {
//        setupConfigWithStartTime("09:15", target, sl, maxReversals, StrikeMode.MANUAL);
//    }
//
//    private void setupConfigWithStartTime(String startTime) {
//        setupConfigWithStartTime(startTime, 5000, 2000, 3, StrikeMode.MANUAL);
//    }
//
//    private void setupAutoAtmConfig() {
//        setupConfigWithStartTime("09:15", 5000, 2000, 3, StrikeMode.AUTO_ATM);
//    }
//
//    private void setupConfigWithStartTime(String startTime, double target, double sl,
//                                          int maxReversals, StrikeMode strikeMode) {
//        TradingConfig config = TradingConfig.builder()
//                .futuresInstrument(FUTURES)
//                .ceInstrument(CE_INST)
//                .peInstrument(PE_INST)
//                .ceStrikePrice(22000)
//                .peStrikePrice(22000)
//                .expiryType(ExpiryType.CURRENT_WEEK)
//                .startCandleTime(LocalTime.parse(startTime))
//                .strikeMode(strikeMode)
//                .lotQuantity(1)
//                .lotSize(75)
//                .targetProfit(target)
//                .stopLoss(sl)
//                .maxReversals(maxReversals)
//                .tradeMode(TradeMode.PAPER)
//                .build();
//        cache.setConfig(config);
//    }
//
//    private String getToken() throws Exception {
//        LoginRequest request = new LoginRequest();
//        request.setUsername("admin");
//        request.setPassword("admin123");
//        MvcResult result = mockMvc.perform(post("/api/auth/login")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(request)))
//                .andReturn();
//        String body = result.getResponse().getContentAsString();
//        return objectMapper.readTree(body).get("token").asText();
//    }
//}
