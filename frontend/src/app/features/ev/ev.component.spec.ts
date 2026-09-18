import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Charger, ChargingSession, LatestVehicleTelemetry } from '../../core/telemetry-api.service';
import { EvComponent } from './ev.component';

/**
 * The EV screen, rendered.
 *
 * <p>The behaviour worth pinning is what the screen does when the server refuses: a charger is
 * claimed under a lock, so two controllers racing for the last one is an ordinary Tuesday rather
 * than an edge case. The one who loses must be told, in the server's words, not shown a silent
 * failure or a screen that still claims the charger is free.
 */
describe('EvComponent', () => {
  let fixture: ComponentFixture<EvComponent>;
  let controller: HttpTestingController;

  const charger: Charger = {
    id: 1,
    code: 'CHG-01',
    depotCode: 'DEP-1',
    depotName: 'River Depot',
    powerKw: 150,
    status: 'AVAILABLE',
    occupyingVehicleId: null
  };

  const vehicle: LatestVehicleTelemetry = {
    vehicleId: 'BUS-042',
    vehicleType: 'STANDARD_BUS',
    propulsionType: 'BATTERY_ELECTRIC',
    capacity: 80,
    status: 'ACTIVE',
    sourceEventId: 'evt-1',
    recordedAt: new Date().toISOString(),
    receivedAt: new Date().toISOString(),
    latitude: 40.7152,
    longitude: -73.998,
    speedKph: 0,
    headingDegrees: 90,
    occupancyEstimate: 0,
    batteryPercent: 18,
    routeCode: 'M42',
    routeProgress: 0,
    routeDeviationMeters: 0,
    tripCode: null,
    scheduleDeviationSeconds: null,
    nextStopName: null,
    dwellingAtStopName: null,
    dwellSeconds: null,
    telemetryAgeSeconds: 3,
    connectivityState: 'ONLINE'
  };

  const session: ChargingSession = {
    id: 9,
    vehicleId: 'BUS-101',
    chargerCode: 'CHG-02',
    depotCode: 'DEP-1',
    status: 'ACTIVE',
    startedAt: new Date().toISOString(),
    endedAt: null,
    startBatteryPercent: 22,
    endBatteryPercent: null,
    startedBy: 'controller-a'
  };

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  function render(
    chargers: Charger[] = [charger],
    sessions: ChargingSession[] = [],
    role: 'CONTROLLER' | 'VIEWER' = 'CONTROLLER'
  ): void {
    sessionStorage.setItem(
      'metropulse.user',
      JSON.stringify({ id: 1, email: 'a@b.test', displayName: 'A', role })
    );
    fixture = TestBed.createComponent(EvComponent);
    fixture.detectChanges();

    controller.expectOne((request) => request.url.endsWith('/chargers')).flush(chargers);
    controller.expectOne((request) => request.url.includes('/charging-sessions')).flush(sessions);
    controller.expectOne((request) => request.url.includes('/telemetry/vehicles/latest')).flush([vehicle]);
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('shows each charger with its depot and state', () => {
    render();

    expect(text()).toContain('CHG-01');
    expect(text()).toContain('River Depot');
    expect(text()).toContain('Available');
  });

  it('tells the controller who lost the race, in the server words', () => {
    render();

    fixture.componentInstance['selectedVehicle']['CHG-01'] = 'BUS-042';
    fixture.componentInstance['startSession'](charger);

    controller.expectOne((request) => request.method === 'POST').flush(
      { message: 'Charger CHG-01 is not available.' },
      { status: 409, statusText: 'Conflict' }
    );

    // A refusal means this screen was out of date, so it reloads rather than leaving the charger
    // looking free.
    controller.expectOne((request) => request.url.endsWith('/chargers')).flush([
      { ...charger, status: 'OCCUPIED', occupyingVehicleId: 'BUS-101' }
    ]);
    controller.expectOne((request) => request.url.includes('/charging-sessions')).flush([session]);
    controller.expectOne((request) => request.url.includes('/telemetry/vehicles/latest')).flush([vehicle]);
    fixture.detectChanges();

    // Two controllers claiming the last charger is ordinary, and the lock means one of them loses.
    expect(text()).toContain('not available');
    expect(text()).toContain('Occupied');
  });

  it('does not offer a vehicle that is already plugged in somewhere', () => {
    render([charger], [{ ...session, vehicleId: 'BUS-042', chargerCode: 'CHG-02' }]);

    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('option')
    ).map((option) => option.textContent?.trim());

    expect(options).not.toContain('BUS-042');
  });

  it('offers no charger controls to someone who cannot act', () => {
    render([charger], [], 'VIEWER');

    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.charger__action').length).toBe(0);
  });

  it('says there are no sessions rather than showing an empty table', () => {
    render();

    expect(text()).toContain('No charging sessions yet');
  });
});
