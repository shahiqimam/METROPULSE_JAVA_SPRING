package com.metropulse.simulator.scenario;

/**
 * What a scenario does to one vehicle on one tick.
 *
 * @param speedFactor        multiplier on the vehicle's cruising speed; 0.0 holds the vehicle still
 * @param lateralOffsetMeters how far off the route shape to place the vehicle, to the right of travel
 * @param reporting          false when the vehicle sends no telemetry at all on this tick
 */
public record ScenarioAdjustment(double speedFactor, double lateralOffsetMeters, boolean reporting) {

    public static final ScenarioAdjustment NORMAL = new ScenarioAdjustment(1.0, 0.0, true);

    public static ScenarioAdjustment slowedTo(double speedFactor) {
        return new ScenarioAdjustment(speedFactor, 0.0, true);
    }

    public static ScenarioAdjustment offRouteBy(double lateralOffsetMeters) {
        return new ScenarioAdjustment(1.0, lateralOffsetMeters, true);
    }

    public static ScenarioAdjustment holding() {
        return new ScenarioAdjustment(0.0, 0.0, true);
    }

    public static ScenarioAdjustment silent() {
        return new ScenarioAdjustment(1.0, 0.0, false);
    }
}
