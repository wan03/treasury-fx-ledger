package com.wex.fx.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the application {@link Clock} as an injectable bean (constitution §3/§8).
 *
 * <p>Production/dev use a system UTC clock; tests supply a fixed {@code Clock} via
 * {@code @ConditionalOnMissingBean}, so no code ever calls {@code LocalDate.now()}
 * directly — date validation (reject-future) and the 6-month window stay deterministic.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
