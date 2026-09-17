import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface LatestVehicleTelemetry {
  vehicleId: string;
  vehicleType: string;
  propulsionType: string;
  capacity: number;
  status: string;
  sourceEventId: string;
  recordedAt: string;
  receivedAt: string;
  latitude: number;
  longitude: number;
  speedKph: number;
  headingDegrees: number;
  occupancyEstimate: number;
  batteryPercent: number | null;
  routeCode: string | null;
  routeProgress: number | null;
  routeDeviationMeters: number | null;
  telemetryAgeSeconds: number;
  connectivityState: 'ONLINE' | 'STALE' | 'OFFLINE';
}

export interface RouteSummary {
  code: string;
  shortName: string;
  longName: string;
  agencyName: string;
  active: boolean;
  stopCount: number;
  tripCount: number;
  routePointCount: number;
}

export interface RouteGeometryPoint {
  sequence: number;
  latitude: number;
  longitude: number;
}

export interface RouteStop {
  stopCode: string;
  stopName: string;
  stopSequence: number;
  plannedArrivalSeconds: number;
  plannedDepartureSeconds: number;
  latitude: number;
  longitude: number;
}

export interface HeadwayPairView {
  leaderVehicleId: string;
  followerVehicleId: string;
  gapMeters: number;
  headwaySeconds: number | null;
  ratioToTarget: number | null;
  classification: 'BUNCHING' | 'EXCESSIVE_GAP' | 'NOMINAL' | 'UNKNOWN';
}

export interface HeadwayCondition {
  fingerprint: string;
  routeCode: string;
  type: 'BUNCHING' | 'EXCESSIVE_GAP';
  leaderVehicleId: string;
  followerVehicleId: string;
  headwaySeconds: number | null;
  targetHeadwaySeconds: number;
  firstObservedAt: string;
  observedForSeconds: number;
  confirmed: boolean;
  ratioToTarget: number;
}

export interface RouteHeadwaySnapshot {
  routeCode: string;
  targetHeadwaySeconds: number;
  routeLengthMeters: number;
  vehiclesConsidered: number;
  pairs: HeadwayPairView[];
  conditions: HeadwayCondition[];
}

export type AlertType =
  | 'TELEMETRY_OFFLINE'
  | 'ROUTE_DEVIATION'
  | 'BUNCHING'
  | 'EXCESSIVE_GAP'
  | 'LOW_BATTERY'
  | 'OVER_CAPACITY';

export interface OperationalAlert {
  id: number;
  type: AlertType;
  fingerprint: string;
  severity: 'CRITICAL' | 'MAJOR' | 'MINOR';
  status: 'OPEN' | 'ACKNOWLEDGED' | 'CLOSED';
  vehicleId: string | null;
  routeCode: string | null;
  openedAt: string;
  lastObservedAt: string;
  recoveringSince: string | null;
  acknowledgedAt: string | null;
  acknowledgedBy: string | null;
  closedAt: string | null;
  closeReason: 'RECOVERED' | 'CLOSED_BY_CONTROLLER' | null;
  details: Record<string, unknown>;
}

@Injectable({ providedIn: 'root' })
export class TelemetryApiService {
  private readonly http = inject(HttpClient);

  /** Requests are authorised by the interceptor, so callers never handle credentials. */
  private readonly apiBase = '/api/v1';

  findLatestVehicleTelemetry(): Observable<LatestVehicleTelemetry[]> {
    return this.http.get<LatestVehicleTelemetry[]>(`${this.apiBase}/telemetry/vehicles/latest`, {});
  }

  findRoutes(): Observable<RouteSummary[]> {
    return this.http.get<RouteSummary[]>(`${this.apiBase}/routes`, {});
  }

  findRouteGeometry(routeCode: string
  ): Observable<RouteGeometryPoint[]> {
    return this.http.get<RouteGeometryPoint[]>(
      `${this.apiBase}/routes/${encodeURIComponent(routeCode)}/geometry`,
      {}
    );
  }

  findRouteStops(routeCode: string): Observable<RouteStop[]> {
    return this.http.get<RouteStop[]>(`${this.apiBase}/routes/${encodeURIComponent(routeCode)}/stops`, {});
  }

  findRouteHeadway(routeCode: string
  ): Observable<RouteHeadwaySnapshot> {
    return this.http.get<RouteHeadwaySnapshot>(
      `${this.apiBase}/routes/${encodeURIComponent(routeCode)}/headway`,
      {}
    );
  }

  findAlerts(): Observable<OperationalAlert[]> {
    return this.http.get<OperationalAlert[]>(`${this.apiBase}/alerts`, {});
  }

  acknowledgeAlert(alertId: number
  ): Observable<OperationalAlert> {
    return this.http.post<OperationalAlert>(`${this.apiBase}/alerts/${alertId}/acknowledge`, {}, {});
  }

  closeAlert(alertId: number
  ): Observable<OperationalAlert> {
    return this.http.post<OperationalAlert>(`${this.apiBase}/alerts/${alertId}/close`, {}, {});
  }

}
