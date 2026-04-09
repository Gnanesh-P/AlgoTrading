package com.algotrading.backend.service;

import com.algotrading.backend.dto.OptionChainResponse;
import com.algotrading.backend.model.ExpiryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OptionInstrumentServiceTest {

    @Autowired
    private OptionInstrumentService optionInstrumentService;

    @Test
    @DisplayName("ATM strike computed correctly for price near multiple of 50")
    void computeAtmStrikeRoundsToNearest50() {
        assertThat(optionInstrumentService.computeAtmStrike(22175)).isEqualTo(22200);
        assertThat(optionInstrumentService.computeAtmStrike(22124)).isEqualTo(22100);
        assertThat(optionInstrumentService.computeAtmStrike(22125)).isEqualTo(22150); // 22125/50=442.5 rounds up to 443
        assertThat(optionInstrumentService.computeAtmStrike(22000)).isEqualTo(22000);
        assertThat(optionInstrumentService.computeAtmStrike(21950)).isEqualTo(21950);
        assertThat(optionInstrumentService.computeAtmStrike(22574)).isEqualTo(22550);
    }

    @Test
    @DisplayName("Option chain contains strikes within ±400 range")
    void optionChainContainsCorrectRange() {
        OptionChainResponse chain = optionInstrumentService.buildOptionChain(
                "NIFTY25APRFUT", 22000, ExpiryType.CURRENT_WEEK);

        assertThat(chain.getAtmStrike()).isEqualTo(22000);
        List<OptionChainResponse.StrikeData> strikes = chain.getStrikes();

        int minStrike = strikes.stream().mapToInt(OptionChainResponse.StrikeData::getStrikePrice).min().orElse(0);
        int maxStrike = strikes.stream().mapToInt(OptionChainResponse.StrikeData::getStrikePrice).max().orElse(0);

        assertThat(minStrike).isEqualTo(21600);  // 22000 - 400
        assertThat(maxStrike).isEqualTo(22400);  // 22000 + 400
    }

    @Test
    @DisplayName("Exactly one strike is marked as ATM")
    void exactlyOneAtmStrike() {
        OptionChainResponse chain = optionInstrumentService.buildOptionChain(
                "NIFTY25APRFUT", 22000, ExpiryType.CURRENT_WEEK);

        long atmCount = chain.getStrikes().stream()
                .filter(OptionChainResponse.StrikeData::isAtm)
                .count();
        assertThat(atmCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Each strike has both CE and PE instrument keys")
    void eachStrikeHasCeAndPeKeys() {
        OptionChainResponse chain = optionInstrumentService.buildOptionChain(
                "NIFTY25APRFUT", 22000, ExpiryType.CURRENT_WEEK);

        chain.getStrikes().forEach(strike -> {
            assertThat(strike.getCeInstrument()).contains("CE");
            assertThat(strike.getPeInstrument()).contains("PE");
            assertThat(strike.getCeInstrument()).contains(String.valueOf(strike.getStrikePrice()));
        });
    }

    @Test
    @DisplayName("Strikes are spaced 50 points apart")
    void strikesSpaced50PointsApart() {
        OptionChainResponse chain = optionInstrumentService.buildOptionChain(
                "NIFTY25APRFUT", 22000, ExpiryType.CURRENT_WEEK);

        List<Integer> strikes = chain.getStrikes().stream()
                .map(OptionChainResponse.StrikeData::getStrikePrice)
                .sorted()
                .toList();

        for (int i = 1; i < strikes.size(); i++) {
            assertThat(strikes.get(i) - strikes.get(i - 1)).isEqualTo(50);
        }
    }
}
