package com.algotrading.backend.integration;

import com.algotrading.backend.dto.LoginRequest;
import com.algotrading.backend.dto.TradingConfigRequest;
import com.algotrading.backend.model.ExpiryType;
import com.algotrading.backend.model.StrikeMode;
import com.algotrading.backend.model.TradeMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String jwtToken;

    @BeforeEach
    void setUp() throws Exception {
        jwtToken = getToken();
    }

    @Test
    @DisplayName("Save valid config returns 200 with config details")
    void saveValidConfig() throws Exception {
        TradingConfigRequest req = buildValidConfig();

        mockMvc.perform(post("/api/config")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.futuresInstrument").value("NIFTY25APRFUT"))
                .andExpect(jsonPath("$.lotQuantity").value(1))
                .andExpect(jsonPath("$.targetProfit").value(5000))
                .andExpect(jsonPath("$.stopLoss").value(2000))
                .andExpect(jsonPath("$.maxReversals").value(3))
                .andExpect(jsonPath("$.tradeMode").value("PAPER"));
    }

    @Test
    @DisplayName("Save config with invalid start time returns 400")
    void saveConfigInvalidStartTime() throws Exception {
        TradingConfigRequest req = buildValidConfig();
        req.setStartCandleTime("09:20");  // Invalid

        mockMvc.perform(post("/api/config")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Save config with 09:15 start time succeeds")
    void saveConfigWith0915StartTime() throws Exception {
        TradingConfigRequest req = buildValidConfig();
        req.setStartCandleTime("09:15");

        mockMvc.perform(post("/api/config")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Save config with 09:17 start time succeeds")
    void saveConfigWith0917StartTime() throws Exception {
        TradingConfigRequest req = buildValidConfig();
        req.setStartCandleTime("09:17");

        mockMvc.perform(post("/api/config")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Get config after saving returns saved config")
    void getConfigAfterSave() throws Exception {
        TradingConfigRequest req = buildValidConfig();
        mockMvc.perform(post("/api/config")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/config")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.futuresInstrument").value("NIFTY25APRFUT"));
    }

    @Test
    @DisplayName("Option chain returns strikes within ±400 range")
    void optionChainReturnsCorrectRange() throws Exception {
        mockMvc.perform(get("/api/config/option-chain")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("futuresInstrument", "NIFTY25APRFUT")
                        .param("expiryType", "CURRENT_WEEK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strikes").isArray())
                .andExpect(jsonPath("$.atmStrike").isNumber());
    }

    @Test
    @DisplayName("Config requires authentication")
    void configRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isForbidden());
    }

    private TradingConfigRequest buildValidConfig() {
        TradingConfigRequest req = new TradingConfigRequest();
        req.setFuturesInstrument("NIFTY25APRFUT");
        req.setCeInstrument("NIFTY25APR22000CE");
        req.setPeInstrument("NIFTY25APR22000PE");
        req.setCeStrikePrice(22000);
        req.setPeStrikePrice(22000);
        req.setExpiryType(ExpiryType.CURRENT_WEEK);
        req.setStartCandleTime("09:15");
        req.setStrikeMode(StrikeMode.MANUAL);
        req.setLotQuantity(1);
        req.setLotSize(75);
        req.setTargetProfit(5000);
        req.setStopLoss(2000);
        req.setMaxReversals(3);
        req.setTradeMode(TradeMode.PAPER);
        return req;
    }

    private String getToken() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }
}
