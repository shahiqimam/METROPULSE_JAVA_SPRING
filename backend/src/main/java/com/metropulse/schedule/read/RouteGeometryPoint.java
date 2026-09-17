package com.metropulse.schedule.read;

import java.math.BigDecimal;

/** One vertex of a route's shape, in the order it appears along the line. */
public record RouteGeometryPoint(int sequence, BigDecimal latitude, BigDecimal longitude) {
}
