package com.metropulse.analytics.api;

import com.metropulse.analytics.application.AnalyticsService;
import com.metropulse.analytics.domain.EvAnalytics;
import com.metropulse.analytics.domain.IncidentAnalytics;
import com.metropulse.analytics.domain.ServiceRegularityAnalytics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Operational metrics.
 *
 * <p>Every endpoint takes a window in hours, defaulting to a day, because a metric without a period
 * is not a metric. The maximum is capped so a query cannot ask for the whole table by accident.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private static final int DEFAULT_WINDOW_HOURS = 24;
    private static final int MAX_WINDOW_HOURS = 24 * 90;

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/service-regularity")
    public List<ServiceRegularityAnalytics> serviceRegularity(@RequestParam(required = false) Integer windowHours) {
        return analyticsService.serviceRegularity(window(windowHours));
    }

    @GetMapping("/alerts")
    public Map<String, Integer> alerts(@RequestParam(required = false) Integer windowHours) {
        return analyticsService.alertCountsByType(window(windowHours));
    }

    @GetMapping("/incidents")
    public IncidentAnalytics incidents(@RequestParam(required = false) Integer windowHours) {
        return analyticsService.incidents(window(windowHours));
    }

    @GetMapping("/ev")
    public EvAnalytics ev(@RequestParam(required = false) Integer windowHours) {
        return analyticsService.ev(window(windowHours));
    }

    private Duration window(Integer windowHours) {
        int hours = windowHours == null ? DEFAULT_WINDOW_HOURS : windowHours;
        return Duration.ofHours(Math.min(Math.max(hours, 1), MAX_WINDOW_HOURS));
    }
}
