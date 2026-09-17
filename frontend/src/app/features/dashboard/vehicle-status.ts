import { LatestVehicleTelemetry } from '../../core/telemetry-api.service';

/**
 * A vehicle is treated as off route beyond this distance from its route shape.
 *
 * <p>This is a MetroPulse project threshold, not a transit-industry standard, and it is presentation
 * only: the backend stores the measured distance and does not raise an alert from it yet.
 */
export const OFF_ROUTE_METERS = 100;

/** Battery percentage below which an electric vehicle is called out. */
export const LOW_BATTERY_PERCENT = 20;

export type StatusRole = 'good' | 'warning' | 'serious' | 'critical';

/**
 * The single status a vehicle is shown with, worst-first.
 *
 * <p>Losing telemetry outranks being off route, because a vehicle that stopped reporting may be
 * anywhere; the last known position is not evidence that it is still there.
 */
export function statusRole(vehicle: LatestVehicleTelemetry): StatusRole {
  if (vehicle.connectivityState === 'OFFLINE') {
    return 'critical';
  }
  if ((vehicle.routeDeviationMeters ?? 0) > OFF_ROUTE_METERS) {
    return 'serious';
  }
  if (vehicle.connectivityState === 'STALE') {
    return 'warning';
  }
  return 'good';
}

/** Text for that status. Colour never carries the meaning on its own. */
export function connectivityLabel(vehicle: LatestVehicleTelemetry): string {
  switch (statusRole(vehicle)) {
    case 'critical':
      return 'No telemetry';
    case 'serious':
      return 'Off route';
    case 'warning':
      return 'Telemetry stale';
    default:
      return 'On route';
  }
}

export function isOffRoute(vehicle: LatestVehicleTelemetry): boolean {
  return (vehicle.routeDeviationMeters ?? 0) > OFF_ROUTE_METERS;
}

export function isLowBattery(vehicle: LatestVehicleTelemetry): boolean {
  return vehicle.batteryPercent !== null && vehicle.batteryPercent <= LOW_BATTERY_PERCENT;
}

/** Telemetry age as a short human string, for example "4s" or "2m 10s". */
export function formatAge(seconds: number): string {
  const whole = Math.max(0, Math.round(seconds));
  if (whole < 60) {
    return `${whole}s`;
  }
  const minutes = Math.floor(whole / 60);
  if (minutes < 60) {
    return `${minutes}m ${whole % 60}s`;
  }
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

/** Enum-style codes as readable text, for example STANDARD_BUS -> Standard bus. */
export function formatCode(value: string): string {
  const spaced = value.replace(/_/g, ' ').toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/** Service-day seconds as a wall clock time, since schedules can run past midnight. */
export function formatServiceTime(totalSeconds: number): string {
  const hours = Math.floor(totalSeconds / 3600) % 24;
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`;
}
