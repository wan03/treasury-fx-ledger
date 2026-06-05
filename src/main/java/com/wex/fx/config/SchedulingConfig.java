package com.wex.fx.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduling support app-wide, on a neutral config so it is independent of any single
 * adapter. Two {@code @Scheduled} holders rely on it:
 *
 * <ul>
 *   <li>the idempotency-key TTL sweep ({@code adapter.persistence.IdempotencySweeper}) — runs on every
 *       provider profile, including the default {@code ondemand};</li>
 *   <li>the Treasury ingest reconcile ({@code adapter.treasury.RateSyncService}) — created only for the
 *       {@code ingest}/{@code hybrid} providers, so its schedule is inert otherwise.</li>
 * </ul>
 *
 * <p>Keeping {@code @EnableScheduling} here (not on the Treasury provider config) ensures the sweep never
 * depends on which rate provider is active.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {}
