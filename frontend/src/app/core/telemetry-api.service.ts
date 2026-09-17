import { HttpClient, HttpHeaders } from '@angular/common/http';
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

export interface RouteStop {
  stopCode: string;
  stopName: string;
  stopSequence: number;
  plannedArrivalSeconds: number;
  plannedDepartureSeconds: number;
  latitude: number;
  longitude: number;
}

@Injectable({ providedIn: 'root' })
export class TelemetryApiService {
  private readonly http = inject(HttpClient);

  findLatestVehicleTelemetry(
    apiBase: string,
    username: string,
    password: string
  ): Observable<LatestVehicleTelemetry[]> {
    return this.http.get<LatestVehicleTelemetry[]>(`${apiBase}/telemetry/vehicles/latest`, {
      headers: this.authHeaders(username, password)
    });
  }

  findRoutes(apiBase: string, username: string, password: string): Observable<RouteSummary[]> {
    return this.http.get<RouteSummary[]>(`${apiBase}/routes`, {
      headers: this.authHeaders(username, password)
    });
  }

  findRouteStops(apiBase: string, username: string, password: string, routeCode: string): Observable<RouteStop[]> {
    return this.http.get<RouteStop[]>(`${apiBase}/routes/${encodeURIComponent(routeCode)}/stops`, {
      headers: this.authHeaders(username, password)
    });
  }

  private authHeaders(username: string, password: string): HttpHeaders {
    if (!username || !password) {
      return new HttpHeaders();
    }

    return new HttpHeaders({
      Authorization: `Basic ${btoa(`${username}:${password}`)}`
    });
  }
}
