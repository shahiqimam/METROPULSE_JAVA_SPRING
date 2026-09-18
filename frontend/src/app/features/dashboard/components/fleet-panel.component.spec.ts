import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LatestVehicleTelemetry } from '../../../core/telemetry-api.service';
import { FleetPanelComponent } from './fleet-panel.component';

/**
 * The fleet list, rendered.
 *
 * <p>These assertions are about what the words mean rather than where they sit. "On time" printed
 * for a vehicle that has not yet reached a stop would be a measurement nobody made, and a bare
 * number of seconds without its direction tells a controller nothing they can act on.
 */
describe('FleetPanelComponent', () => {
  let fixture: ComponentFixture<FleetPanelComponent>;

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
    speedKph: 19,
    headingDegrees: 90,
    occupancyEstimate: 20,
    batteryPercent: 80,
    routeCode: 'M42',
    routeProgress: 0.5,
    routeDeviationMeters: 3,
    tripCode: 'M42-WKD-0900-EAST',
    scheduleDeviationSeconds: 0,
    nextStopName: 'Central Library',
    dwellingAtStopName: null,
    dwellSeconds: null,
    telemetryAgeSeconds: 2,
    connectivityState: 'ONLINE'
  };

  function render(vehicles: LatestVehicleTelemetry[]): void {
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(FleetPanelComponent);
    fixture.componentRef.setInput('vehicles', vehicles);
    fixture.componentRef.setInput('selectedVehicleId', null);
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('shows where a running vehicle is heading', () => {
    render([vehicle]);

    expect(text()).toContain('→ Central Library');
    expect(text()).toContain('on time');
  });

  it('shows where a vehicle is standing rather than where it is going', () => {
    render([{ ...vehicle, speedKph: 0, dwellingAtStopName: 'Hudson Exchange', dwellSeconds: 20 }]);

    expect(text()).toContain('at Hudson Exchange');
  });

  it('says a vehicle has not been measured rather than calling it on time', () => {
    render([{ ...vehicle, scheduleDeviationSeconds: null }]);

    // A vehicle that has not yet called anywhere has nothing to be measured against, and zero would
    // be a measurement nobody made.
    expect(text()).toContain('not yet measured');
    expect(text()).not.toContain('on time');
  });

  it('reports lateness in the direction it happened', () => {
    render([{ ...vehicle, scheduleDeviationSeconds: 380 }]);

    expect(text()).toContain('6 min late');
  });

  it('reports early running as early, not as a negative delay', () => {
    render([{ ...vehicle, scheduleDeviationSeconds: -180 }]);

    expect(text()).toContain('3 min early');
  });

  it('says nothing about a trip for a vehicle that is not running one', () => {
    render([{ ...vehicle, tripCode: null, nextStopName: null, scheduleDeviationSeconds: null }]);

    expect(text()).not.toContain('not yet measured');
    expect(text()).toContain('BUS-042');
  });
});
