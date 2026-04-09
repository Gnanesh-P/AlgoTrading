package com.algotrading.backend.integration;

import com.algotrading.backend.cache.MarketDataCache;
import com.algotrading.backend.dto.LoginRequest;
import com.algotrading.backend.dto.MarketTickRequest;
import com.algotrading.backend.model.MarketTick;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class MarketDataIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MarketDataCache cache;

    private String jwtToken;

    @BeforeEach
    void setUp() throws Exception {
        jwtToken = getToken();
        cache.clearDayData();
    }

    @Test
    @DisplayName("Push tick and retrieve it via GET")
    void pushAndRetrieveTick() throws Exception {
        MarketTickRequest req = buildTickRequest("NIFTY25APRFUT", 22050.5);

        mockMvc.perform(post("/api/market-data/tick")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        MarketTick stored = cache.getLatestTick("NIFTY25APRFUT");
        assertThat(stored).isNotNull();
        assertThat(stored.getLastPrice()).isEqualTo(22050.5);
    }

    @Test
    @DisplayName("Push batch ticks and verify all are stored")
    void pushBatchTicks() throws Exception {
        List<MarketTickRequest> batch = List.of(
                buildTickRequest("NIFTY25APRFUT", 22000),
                buildTickRequest("NIFTY25APR22000CE", 150),
                buildTickRequest("NIFTY25APR22000PE", 180)
        );

        mockMvc.perform(post("/api/market-data/ticks/batch")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("3")));

        assertThat(cache.getLatestTick("NIFTY25APRFUT")).isNotNull();
        assertThat(cache.getLatestTick("NIFTY25APR22000CE")).isNotNull();
        assertThat(cache.getLatestTick("NIFTY25APR22000PE")).isNotNull();
    }

    @Test
    @DisplayName("GET all ticks returns all pushed instruments")
    void getAllTicks() throws Exception {
        cache.updateTick(MarketTick.builder()
                .instrument("NIFTY25APRFUT").lastPrice(22000).timestamp(LocalDateTime.now()).build());
        cache.updateTick(MarketTick.builder()
                .instrument("NIFTY25APR22000CE").lastPrice(150).timestamp(LocalDateTime.now()).build());

        mockMvc.perform(get("/api/market-data/ticks")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.NIFTY25APRFUT.lastPrice").value(22000))
                .andExpect(jsonPath("$.NIFTY25APR22000CE.lastPrice").value(150));
    }

    @Test
    @DisplayName("GET tick for specific instrument")
    void getTickForInstrument() throws Exception {
        cache.updateTick(MarketTick.builder()
                .instrument("NIFTY25APRFUT").lastPrice(22100).timestamp(LocalDateTime.now()).build());

        mockMvc.perform(get("/api/market-data/tick/NIFTY25APRFUT")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastPrice").value(22100));
    }

    @Test
    @DisplayName("GET tick for non-existent instrument returns 204")
    void getTickForMissingInstrument() throws Exception {
        mockMvc.perform(get("/api/market-data/tick/NONEXISTENT")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Market data endpoints require authentication")
    void marketDataRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/market-data/ticks"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Tick with invalid price returns 400")
    void tickWithInvalidPrice() throws Exception {
        MarketTickRequest req = buildTickRequest("NIFTY25APRFUT", -100);

        mockMvc.perform(post("/api/market-data/tick")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    private MarketTickRequest buildTickRequest(String instrument, double price) {
        MarketTickRequest req = new MarketTickRequest();
        req.setInstrument(instrument);
        req.setLastPrice(price);
        req.setTimestamp(LocalDateTime.now());
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
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
