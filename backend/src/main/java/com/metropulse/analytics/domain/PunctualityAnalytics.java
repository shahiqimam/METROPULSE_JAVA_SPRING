package com.metropulse.analytics.domain;

/**
 * How close the service ran to its timetable.
 *
 * <p>Built from recorded stop arrivals — actual against planned — not from estimates. A call that was
 * never detected is not counted, which understates how many calls were made rather than overstating
 * how punctual they were.
 *
 * @param onTime       within the on-time window
 * @param late         beyond the late tolerance
 * @param early        beyond the early tolerance, which passengers feel more sharply than lateness
 * @param onTimePercent share of measured calls that were on time
 */
public record PunctualityAnalytics(
        String routeCode,
        int measuredCalls,
        int onTime,
        int late,
        int early,
        double onTimePercent,
        double averageDeviationSeconds,
        int worstLateSeconds,
        int worstEarlySeconds
) {
}
