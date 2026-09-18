import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { EvAnalytics, IncidentAnalytics, Punctuality, ServiceRegularity } from '../../core/telemetry-api.service';
import { AnalyticsComponent } from './analytics.component';

/**
 * The analytics screen, rendered.
 *
 * <p>Most of what could go wrong on this page is a number that reads as the opposite of what it
 * means. A deviation without its sign, a "worst early" column showing the best call on the route, or
 * punctuality and regularity blended into one figure would all render perfectly well and mislead
 * whoever read them.
 */
describe('AnalyticsComponent', () => {
  let fixture: ComponentFixture<AnalyticsComponent>;
  let controller: HttpTestingController;

  const punctuality: Punctuality = {
    routeCode: 'M42',
    measuredCalls: 40,
    onTime: 34,
    late: 5,
    early: 1,
    onTimePercent: 85,
    averageDeviationSeconds: 47,
    worstLateSeconds: 297,
    worstEarlySeconds: -120
  };

  const regularity: ServiceRegularity = {
    routeCode: 'M42',
    targetHeadwaySeconds: 83,
    conditionsNow: 0,
    bunchingNow: 0,
    excessiveGapsNow: 0,
    bunchingAlertsInWindow: 2,
    excessiveGapAlertsInWindow: 0,
    averageAlertSeconds: 90
  };

  const incidents: IncidentAnalytics = {
    total: 3,
    live: 1,
    resolved: 2,
    cancelled: 0,
    critical: 0,
    averageSecondsToAcknowledge: 120,
    averageSecondsToResolve: 900
  };

  const ev: EvAnalytics = {
    chargersTotal: 6,
    chargersAvailable: 5,
    chargersOccupied: 0,
    chargersOutOfService: 1,
    sessionsInWindow: 0,
    sessionsActive: 0,
    averageSessionSeconds: 0,
    averageBatteryPercentGained: 0,
    averageFleetBatteryPercent: 75,
    lowBatteryVehicles: 0
  };

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  function render(overrides: Partial<Punctuality>[] = [punctuality]): void {
    fixture = TestBed.createComponent(AnalyticsComponent);
    fixture.detectChanges();

    flush('/analytics/punctuality', overrides);
    flush('/analytics/service-regularity', [regularity]);
    flush('/analytics/alerts', { LONG_DWELL: 6, VEHICLE_LATE: 2 });
    flush('/analytics/incidents', incidents);
    flush('/analytics/ev', ev);

    fixture.detectChanges();
  }

  function flush(path: string, body: object): void {
    controller.expectOne((request) => request.url.includes(path)).flush(body);
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('keeps the sign on a deviation, because the sign is the meaning', () => {
    render();

    // "+297s" and "-297s" are opposite operational problems; a bare "297s" is unreadable.
    expect(text()).toContain('+297s late');
    expect(text()).toContain('-120s early');
  });

  it('says there was no early running rather than printing the best call as the worst', () => {
    render([{ ...punctuality, early: 0, worstEarlySeconds: 2 }]);

    // The smallest deviation on a route where nothing ran early is still a late one.
    expect(text()).not.toContain('+2s late');
    expect(text()).toContain('none');
  });

  it('reports punctuality and regularity as separate questions', () => {
    render();

    expect(text()).toContain('Whether the service ran to its timetable');
    expect(text()).toContain('How evenly spaced the service ran');
    expect(text()).toContain('a different question from whether it ran on time');
  });

  it('names the alert types a controller would recognise', () => {
    render();

    expect(text()).toContain('Long dwell');
    expect(text()).toContain('Running late');
  });

  it('says when nothing was measured instead of showing an empty table', () => {
    render([]);

    expect(text()).toContain('No stop arrivals recorded in this window');
  });

  it('reloads every metric when the window changes', () => {
    render();

    fixture.componentInstance['windowHours'] = 1;
    fixture.componentInstance['load']();

    // A metric without a period is not a metric, so all five move together.
    ['punctuality', 'service-regularity', 'alerts', 'incidents', 'ev'].forEach((path) => {
      const request = controller.expectOne((candidate) => candidate.url.includes(`/analytics/${path}`));
      expect(request.request.url).toContain('windowHours=1');
      const body: object = path === 'alerts' ? {} : path === 'incidents' ? incidents : path === 'ev' ? ev : [];
      request.flush(body);
    });
  });
});
