import { LatestVehicleTelemetry } from '../../core/telemetry-api.service';
import {
  OFF_ROUTE_METERS,
  connectivityLabel,
  formatAge,
  formatServiceTime,
  isLowBattery,
  isOffRoute,
  statusRole
} from './vehicle-status';

/**
 * The rules that decide what a controller sees on a vehicle.
 *
 * <p>These are worth testing precisely because they look trivial: the ordering of the status checks
 * is a judgement about which problem matters most, and getting it wrong shows the wrong badge on the
 * worst vehicle at the worst moment.
 */
describe('vehicle status', () => {
  function vehicle(overrides: Partial<LatestVehicleTelemetry> = {}): LatestVehicleTelemetry {
    return {
      vehicleId: 'BUS-042',
      vehicleType: 'STANDARD_BUS',
      propulsionType: 'BATTERY_ELECTRIC',
      capacity: 80,
      status: 'ACTIVE',
      sourceEventId: 'evt-1',
      recordedAt: '2026-09-17T09:00:00Z',
      receivedAt: '2026-09-17T09:00:01Z',
      latitude: 40.7152,
      longitude: -73.998,
      speedKph: 24,
      headingDegrees: 90,
      occupancyEstimate: 20,
      batteryPercent: 80,
      routeCode: 'M42',
      routeProgress: 0.5,
      routeDeviationMeters: 5,
      tripCode: 'M42-WKD-0700-EAST',
      scheduleDeviationSeconds: 0,
      nextStopName: 'Central Library',
      dwellingAtStopName: null,
      dwellSeconds: null,
      telemetryAgeSeconds: 2,
      connectivityState: 'ONLINE',
      ...overrides
    };
  }

  describe('statusRole', () => {
    it('reports a nominal vehicle as good', () => {
      expect(statusRole(vehicle())).toBe('good');
    });

    it('reports a stale vehicle as a warning', () => {
      expect(statusRole(vehicle({ connectivityState: 'STALE' }))).toBe('warning');
    });

    it('treats losing telemetry as worse than being off route', () => {
      // A vehicle that stopped reporting may be anywhere; its last position is not evidence.
      const lost = vehicle({ connectivityState: 'OFFLINE', routeDeviationMeters: 500 });
      expect(statusRole(lost)).toBe('critical');
      expect(connectivityLabel(lost)).toBe('No telemetry');
    });

    it('treats being off route as worse than stale telemetry', () => {
      const strayed = vehicle({ connectivityState: 'STALE', routeDeviationMeters: 300 });
      expect(statusRole(strayed)).toBe('serious');
    });
  });

  describe('isOffRoute', () => {
    it('is false exactly at the threshold', () => {
      expect(isOffRoute(vehicle({ routeDeviationMeters: OFF_ROUTE_METERS }))).toBe(false);
    });

    it('is true just past it', () => {
      expect(isOffRoute(vehicle({ routeDeviationMeters: OFF_ROUTE_METERS + 0.1 }))).toBe(true);
    });

    it('is false when the vehicle has no route to deviate from', () => {
      expect(isOffRoute(vehicle({ routeCode: null, routeDeviationMeters: null }))).toBe(false);
    });
  });

  describe('isLowBattery', () => {
    it('is true at or under twenty percent', () => {
      expect(isLowBattery(vehicle({ batteryPercent: 20 }))).toBe(true);
      expect(isLowBattery(vehicle({ batteryPercent: 21 }))).toBe(false);
    });

    it('is false for a vehicle without a battery', () => {
      expect(isLowBattery(vehicle({ batteryPercent: null }))).toBe(false);
    });
  });

  describe('formatAge', () => {
    it('reads in seconds under a minute', () => {
      expect(formatAge(0)).toBe('0s');
      expect(formatAge(59)).toBe('59s');
    });

    it('reads in minutes and seconds under an hour', () => {
      expect(formatAge(60)).toBe('1m 0s');
      expect(formatAge(125)).toBe('2m 5s');
    });

    it('reads in hours beyond that', () => {
      expect(formatAge(3_700)).toBe('1h 1m');
    });

    it('never reads as negative', () => {
      expect(formatAge(-5)).toBe('0s');
    });
  });

  describe('formatServiceTime', () => {
    it('formats seconds into the service day as a clock time', () => {
      expect(formatServiceTime(25_200)).toBe('07:00');
    });

    it('wraps a service day that runs past midnight', () => {
      // 25:10 belongs to the previous service day but is displayed as the clock time it happens at.
      expect(formatServiceTime(90_600)).toBe('01:10');
    });
  });
});
