package com.metropulse.simulator.scenario;

/**
 * Turns a scenario into per-vehicle, per-tick behaviour.
 *
 * <p>Scenarios are deliberately blunt: they pick one or two vehicles out of the fleet and change how
 * those vehicles move, so the operational rules downstream have something real to detect. Nothing
 * here decides whether an alert fires; the backend derives that from the telemetry it receives.
 *
 * <p>The thresholds these scenarios aim at (bunching, more than 100 m off route, telemetry older
 * than 60 s) are MetroPulse project thresholds, not transit-industry standards.
 */
public final class ScenarioProfile {

    /** Comfortably beyond the 100 m route-deviation threshold the dashboard highlights. */
    private static final double DEVIATION_OFFSET_METERS = 180.0;

    /** Long enough that the affected vehicle goes OFFLINE (no telemetry for over 60 s). */
    private static final int SILENT_TICKS = 45;
    private static final int REPORTING_TICKS = 60;

    private ScenarioProfile() {
    }

    public static ScenarioAdjustment adjustmentFor(ScenarioType scenario, int vehicleIndex, long tickNumber) {
        return switch (scenario) {
            case NORMAL_OPERATION -> ScenarioAdjustment.NORMAL;

            // The leader crawls, so the vehicle behind it closes the gap and the pair bunches.
            case BUNCHING -> vehicleIndex == 0 ? ScenarioAdjustment.slowedTo(0.35) : ScenarioAdjustment.NORMAL;

            case ROUTE_DEVIATION -> vehicleIndex == 0
                    ? ScenarioAdjustment.offRouteBy(DEVIATION_OFFSET_METERS)
                    : ScenarioAdjustment.NORMAL;

            // One vehicle stops reporting for a while, then comes back.
            case TELEMETRY_LOSS -> vehicleIndex == 2 && isSilentWindow(tickNumber)
                    ? ScenarioAdjustment.silent()
                    : ScenarioAdjustment.NORMAL;

            case LONG_DWELL -> vehicleIndex == 1 ? ScenarioAdjustment.holding() : ScenarioAdjustment.NORMAL;

            // Battery is handled by the fleet state; movement stays normal.
            case EV_LOW_BATTERY -> ScenarioAdjustment.NORMAL;

            case MULTI_INCIDENT -> switch (vehicleIndex) {
                case 0 -> ScenarioAdjustment.offRouteBy(DEVIATION_OFFSET_METERS);
                case 1 -> ScenarioAdjustment.holding();
                case 2 -> isSilentWindow(tickNumber) ? ScenarioAdjustment.silent() : ScenarioAdjustment.NORMAL;
                default -> ScenarioAdjustment.slowedTo(0.5);
            };

            // Degraded first, then normal, so recovery and hysteresis rules have something to close on.
            case RECOVERY -> isRecoveryDegradedPhase(tickNumber)
                    ? ScenarioAdjustment.slowedTo(0.3)
                    : ScenarioAdjustment.NORMAL;
        };
    }

    /** True when a vehicle affected by {@link ScenarioType#TELEMETRY_LOSS} is in its silent window. */
    public static boolean isSilentWindow(long tickNumber) {
        return Math.floorMod(tickNumber, SILENT_TICKS + REPORTING_TICKS) < SILENT_TICKS;
    }

    /** True while {@link ScenarioType#RECOVERY} is still in its degraded phase. */
    public static boolean isRecoveryDegradedPhase(long tickNumber) {
        return Math.floorMod(tickNumber, 120) < 60;
    }

    /** Starting battery percentage for a vehicle under the given scenario. */
    public static double initialBatteryPercent(ScenarioType scenario, int vehicleIndex) {
        if (scenario == ScenarioType.EV_LOW_BATTERY && vehicleIndex == 0) {
            return 14.0;
        }
        return 82.0 - vehicleIndex * 4.0;
    }
}
