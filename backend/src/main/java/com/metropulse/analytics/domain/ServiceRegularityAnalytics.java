package com.metropulse.analytics.domain;

/**
 * How evenly a route's service has been running.
 *
 * <p>Regularity, not punctuality: this says whether vehicles are evenly spaced, not whether they are
 * on time. See {@code AnalyticsService} for why punctuality is absent.
 */
public record ServiceRegularityAnalytics(
        String routeCode,
        int targetHeadwaySeconds,
        int conditionsNow,
        int bunchingNow,
        int excessiveGapsNow,
        int bunchingAlertsInWindow,
        int excessiveGapAlertsInWindow,
        double averageAlertSeconds
) {
}
