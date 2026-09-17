package com.metropulse.simulator.scenario;

/** Mutable state of one synthetic vehicle between ticks. */
public final class SimulatedVehicle {

    private final String vehicleId;
    private String tripCode;
    private double routeProgress;
    private double speedKph;
    private double batteryPercent;
    private int occupancyEstimate;

    public SimulatedVehicle(String vehicleId, double routeProgress, double batteryPercent, int occupancyEstimate) {
        this.vehicleId = vehicleId;
        this.routeProgress = routeProgress;
        this.batteryPercent = batteryPercent;
        this.occupancyEstimate = occupancyEstimate;
    }

    public String vehicleId() {
        return vehicleId;
    }

    /** The scheduled trip this vehicle is currently operating. */
    public String tripCode() {
        return tripCode;
    }

    /**
     * Puts the vehicle at the start of a new trip.
     *
     * <p>A vehicle that has finished its run is at the eastern terminal and its next departure leaves
     * from the western one. The return leg is not simulated, so the vehicle appears at the start of
     * the shape rather than driving back down it: the platform sees a vehicle that has begun a new
     * trip, which is true, having skipped a movement that was never reported anyway.
     */
    public void beginTrip(String tripCode) {
        this.tripCode = tripCode;
        this.routeProgress = 0.0;
    }

    public double routeProgress() {
        return routeProgress;
    }

    public double speedKph() {
        return speedKph;
    }

    public double batteryPercent() {
        return batteryPercent;
    }

    public int occupancyEstimate() {
        return occupancyEstimate;
    }

    public void setSpeedKph(double speedKph) {
        this.speedKph = Math.max(0.0, speedKph);
    }

    public void setOccupancyEstimate(int occupancyEstimate) {
        this.occupancyEstimate = Math.max(0, occupancyEstimate);
    }

    /** Advances along the route, stopping at the end: a trip finishes at its last stop. */
    public void advance(double progressDelta) {
        this.routeProgress = Math.min(1.0, routeProgress + progressDelta);
    }

    /**
     * Holds the vehicle at a position it cannot advance past this tick.
     *
     * <p>Used when the vehicle has caught the one in front: it keeps its place in the queue and its
     * reported speed drops to what it actually achieved.
     */
    public void holdAt(double routeProgress) {
        this.routeProgress = routeProgress;
        this.speedKph = 0.0;
    }

    /** Drains the battery, floored so a long run does not produce negative percentages. */
    public void drainBattery(double percentDrained) {
        this.batteryPercent = Math.max(1.0, batteryPercent - percentDrained);
    }
}
