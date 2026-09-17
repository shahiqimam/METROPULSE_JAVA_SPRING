package com.metropulse.simulator.route;

/** A WGS84 position. Latitude and longitude are in degrees, matching EPSG:4326. */
public record GeoPoint(double latitude, double longitude) {
}
