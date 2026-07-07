package com.algotrading.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Dedicates a small thread pool to all {@code @Scheduled} tasks.
 *
 * Root cause of the "server gets slow the longer a strategy runs" symptom: Spring Boot's
 * default {@code @Scheduled} executor is a SINGLE thread (no custom {@link TaskScheduler} bean
 * was registered before this fix). Every {@code @Scheduled} method in the app — the 2-second
 * {@code KiteRestPollService.pollLtpIfNeeded()} REST-LTP poll (which calls Kite over a proxied
 * OkHttpClient with a 30s connect/read timeout, see {@link AppConfig#buildProxyOkHttpClient()}),
 * the 5-second WebSocket session broadcaster ({@code TradeWebSocketController}), and both EOD
 * square-off crons — were all serialized onto that one thread.
 *
 * In practice: any time the Kite REST call is slow (proxy hiccup, Kite API rate limiting/latency)
 * it can block for up to 30 seconds on that single thread, during which NOTHING else scheduled
 * can run — live P&L broadcasts stall, and in the worst case the 15:29 EOD square-off cron could
 * be delayed behind a stuck REST call. This compounds the longer the app runs and the more often
 * the WebSocket ticker drops (triggering the REST poll fallback), matching the reported "keeps
 * getting slower while a strategy runs" behavior.
 *
 * Fix: give {@code @Scheduled} tasks their own small pool so a slow network call in one task
 * can never block the others.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(taskScheduler());
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(t ->
                org.slf4j.LoggerFactory.getLogger(SchedulingConfig.class)
                        .error("Uncaught error in scheduled task", t));
        scheduler.initialize();
        return scheduler;
    }
}
