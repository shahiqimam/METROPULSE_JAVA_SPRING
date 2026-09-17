package com.metropulse.simulator.scenario;

/** Mutable state of one synthetic vehicle between ticks. */
public final class SimulatedVehicle {

    private final String vehicleId;
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

    /** Advances along the route, wrapping back to the start so the vehicle keeps running the shape. */
    public void advance(double progressDelta) {
        double next = routeProgress + progressDelta;
        this.routeProgress = next - Math.floor(next);
    }

    /** Drains the battery, floored so a long run does not produce negative percentages. */
    public void drainBattery(double percentDrained) {
        this.batteryPercent = Math.max(1.0, batteryPercent - percentDrained);
    }
}
