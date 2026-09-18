import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { PlaybackFrame, PlaybackSession, RouteSummary } from '../../core/telemetry-api.service';
import { PlaybackComponent } from './playback.component';

/**
 * The playback screen, rendered.
 *
 * <p>Replay is the one screen that shows vehicles where they are not. The thing worth protecting is
 * that it never claims otherwise: a replayed frame carries a position and nothing derived from
 * comparing that position against today's schedule, because an hour-old position measured against
 * the current timetable is a different measurement wearing the same name.
 */
describe('PlaybackComponent', () => {
  let fixture: ComponentFixture<PlaybackComponent>;
  let controller: HttpTestingController;

  const route: RouteSummary = {
    code: 'M42',
    shortName: 'M42 Crosstown',
    longName: 'MetroPulse 42nd Street Crosstown',
    agencyName: 'MetroPulse Transit Authority',
    active: true,
    stopCount: 5,
    tripCount: 541,
    routePointCount: 5
  };

  const session: PlaybackSession = {
    id: 3,
    vehicleId: 'BUS-042',
    routeCode: null,
    from: '2026-09-18T09:00:00Z',
    to: '2026-09-18T09:15:00Z',
    speed: 5,
    frameCount: 2,
    createdAt: '2026-09-18T09:20:00Z',
    createdBy: 'controller-a'
  };

  const frames: PlaybackFrame[] = [
    {
      vehicleId: 'BUS-042',
      recordedAt: '2026-09-18T09:00:00Z',
      latitude: 40.7128,
      longitude: -74.006,
      speedKph: 0,
      headingDegrees: 90,
      occupancyEstimate: 12,
      batteryPercent: 80
    },
    {
      vehicleId: 'BUS-042',
      recordedAt: '2026-09-18T09:00:30Z',
      latitude: 40.714,
      longitude: -74.002,
      speedKph: 19,
      headingDegrees: 90,
      occupancyEstimate: 14,
      batteryPercent: 79
    }
  ] as PlaybackFrame[];

  beforeEach(() => {
    sessionStorage.clear();
    sessionStorage.setItem(
      'metropulse.user',
      JSON.stringify({ id: 1, email: 'a@b.test', displayName: 'A', role: 'CONTROLLER' })
    );
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    controller = TestBed.inject(HttpTestingController);

    fixture = TestBed.createComponent(PlaybackComponent);
    fixture.detectChanges();
    controller.expectOne((request) => request.url.includes('/routes')).flush([route]);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.componentInstance['stop']();
    drainRouteShape();
    controller.verify();
  });

  /**
   * Flushes the route's geometry and stops.
   *
   * <p>The screen draws the route behind the replay, so picking a route fetches its shape. That is
   * scenery rather than the behaviour under test here, and it is fetched whenever the selection
   * changes.
   */
  function drainRouteShape(): void {
    controller.match((request) => request.url.includes('/geometry') || request.url.includes('/stops'))
      .forEach((request) => request.flush([]));
  }

  function loadReplay(): void {
    fixture.componentInstance['vehicleId'] = 'BUS-042';
    fixture.componentInstance['loadSession']();

    drainRouteShape();
    controller.expectOne((request) => request.method === 'POST' && request.url.includes('/playback/sessions'))
      .flush(session);
    controller.expectOne((request) => request.url.includes('/frames')).flush(frames);
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('replays frames as positions without inventing a schedule comparison', () => {
    loadReplay();

    const vehicles = fixture.componentInstance['currentVehicles']();
    expect(vehicles.length).toBe(1);
    expect(vehicles[0].latitude).toBe(40.7128);
    expect(vehicles[0].scheduleDeviationSeconds).toBeNull();
    expect(vehicles[0].nextStopName).toBeNull();
    expect(vehicles[0].routeProgress).toBeNull();
  });

  it('steps through the replay a moment at a time', () => {
    loadReplay();

    expect(fixture.componentInstance['index']()).toBe(0);
    fixture.componentInstance['step'](1);
    expect(fixture.componentInstance['index']()).toBe(1);
    expect(fixture.componentInstance['currentVehicles']()[0].speedKph).toBe(19);
  });

  it('does not step past the end of what was replayed', () => {
    loadReplay();

    fixture.componentInstance['step'](1);
    fixture.componentInstance['step'](1);
    fixture.componentInstance['step'](1);

    expect(fixture.componentInstance['index']()).toBe(1);
  });

  it('says plainly when the vehicle does not exist', () => {
    fixture.componentInstance['vehicleId'] = 'BUS-ghost';
    fixture.componentInstance['loadSession']();

    controller.expectOne((request) => request.method === 'POST')
      .flush({ message: 'No vehicle BUS-ghost.' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(text()).toContain('That vehicle does not exist');
  });

  it('reports a window the server refused in the server words', () => {
    fixture.componentInstance['loadSession']();

    controller.expectOne((request) => request.method === 'POST')
      .flush({ message: 'The window is longer than the cap.' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(text()).toContain('longer than the cap');
  });
});
