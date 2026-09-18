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
  /** The scheduled trip the vehicle says it is running, or null when it is out of service. */
  tripCode: string | null;
  /** Positive is late, negative is early, null until the vehicle has called at a stop. */
  scheduleDeviationSeconds: number | null;
  nextStopName: string | null;
  /** The stop it is standing at right now, with how long it has been there. */
  dwellingAtStopName: string | null;
  dwellSeconds: number | null;
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
  | 'OVER_CAPACITY'
  | 'VEHICLE_LATE'
  | 'VEHICLE_EARLY'
  | 'LONG_DWELL';

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

export type IncidentStatus = 'OPEN' | 'ACKNOWLEDGED' | 'MITIGATING' | 'RESOLVED' | 'CANCELLED';
export type IncidentSeverity = 'CRITICAL' | 'MAJOR' | 'MINOR';
export type IncidentType =
  | 'VEHICLE_BREAKDOWN'
  | 'ROAD_BLOCKAGE'
  | 'SERVICE_DISRUPTION'
  | 'PASSENGER_INCIDENT'
  | 'DEPOT_ISSUE'
  | 'OTHER';

export interface IncidentTimelineEntry {
  id: number;
  entryType: 'TRANSITION' | 'NOTE';
  fromStatus: IncidentStatus | null;
  toStatus: IncidentStatus | null;
  note: string | null;
  actor: string;
  recordedAt: string;
}

export interface Incident {
  id: number;
  incidentNumber: string;
  type: IncidentType;
  severity: IncidentSeverity;
  status: IncidentStatus;
  title: string;
  description: string | null;
  vehicleId: string | null;
  routeCode: string | null;
  openedBy: string;
  assignedController: string | null;
  startedAt: string;
  acknowledgedAt: string | null;
  mitigatingAt: string | null;
  resolvedAt: string | null;
  cancelledAt: string | null;
  timeline: IncidentTimelineEntry[];
}

export interface OpenIncidentRequest {
  type: IncidentType;
  severity: IncidentSeverity;
  title: string;
  description?: string | null;
  vehicleId?: string | null;
  routeCode?: string | null;
}

export interface Charger {
  id: number;
  code: string;
  depotCode: string;
  depotName: string;
  powerKw: number;
  status: 'AVAILABLE' | 'OCCUPIED' | 'OFFLINE' | 'MAINTENANCE';
  occupyingVehicleId: string | null;
}

export interface ChargingSession {
  id: number;
  vehicleId: string;
  chargerCode: string;
  depotCode: string;
  status: 'ACTIVE' | 'COMPLETED' | 'INTERRUPTED';
  startedAt: string;
  endedAt: string | null;
  startBatteryPercent: number | null;
  endBatteryPercent: number | null;
  startedBy: string;
}

export interface ScheduleChange {
  added: number;
  updated: number;
}

export interface SchedulePreview {
  agencies: ScheduleChange;
  routes: ScheduleChange;
  stops: ScheduleChange;
  calendars: ScheduleChange;
  trips: ScheduleChange;
  stopTimes: number;
  shapePoints: number;
  unchangedInFeed: string[];
  notes: string[];
}

export interface ScheduleImportResult {
  agencies: number;
  routes: number;
  stops: number;
  calendars: number;
  trips: number;
  stopTimes: number;
  shapePoints: number;
}

export interface StagedScheduleImport {
  id: number;
  status: 'STAGED' | 'ACTIVATED' | 'DISCARDED';
  uploadedBy: string;
  uploadedAt: string;
  activatedBy: string | null;
  activatedAt: string | null;
  discardedBy: string | null;
  discardedAt: string | null;
  preview: SchedulePreview;
  fileNames: string[];
  result: ScheduleImportResult | null;
}

export interface Punctuality {
  routeCode: string;
  measuredCalls: number;
  onTime: number;
  late: number;
  early: number;
  onTimePercent: number;
  averageDeviationSeconds: number;
  worstLateSeconds: number;
  worstEarlySeconds: number;
}

export interface ServiceRegularity {
  routeCode: string;
  targetHeadwaySeconds: number;
  conditionsNow: number;
  bunchingNow: number;
  excessiveGapsNow: number;
  bunchingAlertsInWindow: number;
  excessiveGapAlertsInWindow: number;
  averageAlertSeconds: number;
}

export interface IncidentAnalytics {
  total: number;
  live: number;
  resolved: number;
  cancelled: number;
  critical: number;
  averageSecondsToAcknowledge: number;
  averageSecondsToResolve: number;
}

export interface EvAnalytics {
  chargersTotal: number;
  chargersAvailable: number;
  chargersOccupied: number;
  chargersOutOfService: number;
  sessionsInWindow: number;
  sessionsActive: number;
  averageSessionSeconds: number;
  averageBatteryPercentGained: number;
  averageFleetBatteryPercent: number;
  lowBatteryVehicles: number;
}

export interface PlaybackSession {
  id: number;
  vehicleId: string | null;
  routeCode: string | null;
  from: string;
  to: string;
  speed: number;
  frameCount: number;
  createdAt: string;
  createdBy: string;
}

export interface PlaybackFrame {
  vehicleId: string;
  recordedAt: string;
  latitude: number;
  longitude: number;
  speedKph: number;
  headingDegrees: number;
  occupancyEstimate: number;
  batteryPercent: number | null;
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

  // Incidents

  findIncidents(includeClosed = false): Observable<Incident[]> {
    return this.http.get<Incident[]>(`${this.apiBase}/incidents?includeClosed=${includeClosed}`);
  }

  openIncident(request: OpenIncidentRequest): Observable<Incident> {
    return this.http.post<Incident>(`${this.apiBase}/incidents`, request);
  }

  incidentAction(id: number, action: string, note?: string): Observable<Incident> {
    return this.http.post<Incident>(`${this.apiBase}/incidents/${id}/${action}`, { note: note ?? null });
  }

  addIncidentNote(id: number, note: string): Observable<Incident> {
    return this.http.post<Incident>(`${this.apiBase}/incidents/${id}/notes`, { note });
  }

  // EV

  findChargers(): Observable<Charger[]> {
    return this.http.get<Charger[]>(`${this.apiBase}/chargers`);
  }

  findChargingSessions(activeOnly = false): Observable<ChargingSession[]> {
    return this.http.get<ChargingSession[]>(`${this.apiBase}/charging-sessions?activeOnly=${activeOnly}`);
  }

  startChargingSession(chargerCode: string, vehicleId: string): Observable<ChargingSession> {
    return this.http.post<ChargingSession>(`${this.apiBase}/charging-sessions`, { chargerCode, vehicleId });
  }

  completeChargingSession(id: number): Observable<ChargingSession> {
    return this.http.post<ChargingSession>(`${this.apiBase}/charging-sessions/${id}/complete`, {});
  }

  // Analytics

  // Schedule imports

  findScheduleImports(): Observable<StagedScheduleImport[]> {
    return this.http.get<StagedScheduleImport[]>(`${this.apiBase}/admin/schedule/imports`);
  }

  stageScheduleImport(files: File[]): Observable<StagedScheduleImport> {
    const form = new FormData();
    for (const file of files) {
      form.append('files', file, file.name);
    }
    return this.http.post<StagedScheduleImport>(`${this.apiBase}/admin/schedule/imports`, form);
  }

  activateScheduleImport(id: number): Observable<StagedScheduleImport> {
    return this.http.post<StagedScheduleImport>(`${this.apiBase}/admin/schedule/imports/${id}/activate`, {});
  }

  discardScheduleImport(id: number): Observable<StagedScheduleImport> {
    return this.http.post<StagedScheduleImport>(`${this.apiBase}/admin/schedule/imports/${id}/discard`, {});
  }

  findPunctuality(windowHours = 24): Observable<Punctuality[]> {
    return this.http.get<Punctuality[]>(`${this.apiBase}/analytics/punctuality?windowHours=${windowHours}`);
  }

  findServiceRegularity(windowHours = 24): Observable<ServiceRegularity[]> {
    return this.http.get<ServiceRegularity[]>(`${this.apiBase}/analytics/service-regularity?windowHours=${windowHours}`);
  }

  findAlertAnalytics(windowHours = 24): Observable<Record<string, number>> {
    return this.http.get<Record<string, number>>(`${this.apiBase}/analytics/alerts?windowHours=${windowHours}`);
  }

  findIncidentAnalytics(windowHours = 24): Observable<IncidentAnalytics> {
    return this.http.get<IncidentAnalytics>(`${this.apiBase}/analytics/incidents?windowHours=${windowHours}`);
  }

  findEvAnalytics(windowHours = 24): Observable<EvAnalytics> {
    return this.http.get<EvAnalytics>(`${this.apiBase}/analytics/ev?windowHours=${windowHours}`);
  }

  // Playback

  createPlaybackSession(
    from: string,
    to: string,
    speed: number,
    vehicleId?: string | null,
    routeCode?: string | null
  ): Observable<PlaybackSession> {
    return this.http.post<PlaybackSession>(`${this.apiBase}/playback/sessions`, {
      from,
      to,
      speed,
      vehicleId: vehicleId ?? null,
      routeCode: routeCode ?? null
    });
  }

  findPlaybackFrames(sessionId: number, offset = 0, limit = 500): Observable<PlaybackFrame[]> {
    return this.http.get<PlaybackFrame[]>(
      `${this.apiBase}/playback/sessions/${sessionId}/frames?offset=${offset}&limit=${limit}`
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
