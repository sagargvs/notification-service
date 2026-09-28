package com.stornuage.notification.service;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-user rate limiter for sensitive endpoints.
 *
 * Device registration specifically: 5 per hour per user. Legitimate
 * token rotation is rare; repeated registrations suggest abuse.
 *
 * In-memory (per-pod) is acceptable for this threshold — even if a
 * user somehow hits multiple pods, 5 * pod_count still blocks runaway.
 * For stricter distributed limiting, swap to a Redis-backed limiter.
 */
@Component
public class UserRateLimiter {

    private final RateLimiterRegistry registry;

    public UserRateLimiter() {
        this.registry = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(5)
                .limitRefreshPeriod(Duration.ofHours(1))
                .timeoutDuration(Duration.ZERO)  // non-blocking; fail fast
                .build());
    }

    /**
     * @return true if the call is within rate; false if the user has exhausted their budget.
     */
    public boolean tryAcquire(String scope, String userId) {
        var limiter = registry.rateLimiter(scope + ":" + userId);
        return limiter.acquirePermission();
    }
}
