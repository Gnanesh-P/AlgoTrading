package com.algotrading.backend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Slf4j
public class AlgoTradingApplication {
    public static void main(String[] args) {
        log.info("Enable the logs data");
        SpringApplication.run(AlgoTradingApplication.class, args);
    }
}
