import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Incident } from '../../core/telemetry-api.service';
import { IncidentsComponent } from './incidents.component';

/**
 * The incidents screen, rendered.
 *
 * <p>The screen offers only the transitions the backend would accept from an incident's current
 * state. That is a convenience, not a rule — the rule lives in the transition table on the server,
 * and these tests pin the convenience to it. Offering a button the server refuses is worse than
 * offering none: it teaches a controller that the screen cannot be trusted mid-incident.
 */
describe('IncidentsComponent', () => {
  let fixture: ComponentFixture<IncidentsComponent>;
  let controller: HttpTestingController;

  const incident: Incident = {
    id: 5,
    incidentNumber: 'INC-1001',
    type: 'VEHICLE_BREAKDOWN',
    severity: 'MAJOR',
    status: 'OPEN',
    title: 'BUS-042 stopped at Hudson Exchange',
    description: 'Doors will not close.',
    vehicleId: 'BUS-042',
    routeCode: 'M42',
    openedBy: 'controller-a',
    assignedController: null,
    startedAt: '2026-09-18T09:00:00Z',
    acknowledgedAt: null,
    mitigatingAt: null,
    resolvedAt: null,
    cancelledAt: null,
    timeline: [
      {
        id: 1,
        entryType: 'TRANSITION',
        fromStatus: null,
        toStatus: 'OPEN',
        note: null,
        actor: 'controller-a',
        recordedAt: '2026-09-18T09:00:00Z'
      }
    ]
  };

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  function render(incidents: Incident[], role: 'CONTROLLER' | 'VIEWER' = 'CONTROLLER'): void {
    sessionStorage.setItem(
      'metropulse.user',
      JSON.stringify({ id: 1, email: 'a@b.test', displayName: 'A', role })
    );
    fixture = TestBed.createComponent(IncidentsComponent);
    fixture.detectChanges();
    controller.expectOne((request) => request.url.includes('/incidents')).flush(incidents);
    fixture.detectChanges();
  }

  /** The transition buttons on the incident itself, not the shell's or the report form's. */
  function buttonLabels(): string[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.incident__actions button'))
      .map((button) => button.textContent?.trim() ?? '');
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('offers every transition the backend allows from OPEN', () => {
    render([incident]);

    expect(buttonLabels()).toContain('Acknowledge');
    expect(buttonLabels()).toContain('Start mitigation');
    expect(buttonLabels()).toContain('Resolve');
    expect(buttonLabels()).toContain('Cancel');
  });

  it('does not offer cancelling once mitigation has started', () => {
    render([{ ...incident, status: 'MITIGATING', mitigatingAt: '2026-09-18T09:10:00Z' }]);

    // The server refuses MITIGATING to CANCELLED: work has begun, so it is resolved, not cancelled.
    expect(buttonLabels()).toContain('Resolve');
    expect(buttonLabels()).not.toContain('Cancel');
  });

  it('offers nothing on an incident that has finished', () => {
    render([{ ...incident, status: 'RESOLVED', resolvedAt: '2026-09-18T09:20:00Z' }]);

    expect(buttonLabels()).toEqual([]);
  });

  it('offers nothing to someone who cannot act', () => {
    render([incident], 'VIEWER');

    expect(buttonLabels()).toEqual([]);
  });

  it('reloads after a refused action, because a refusal means the screen was stale', () => {
    render([incident]);

    fixture.componentInstance['act'](incident, 'acknowledge');
    controller.expectOne((request) => request.method === 'POST')
      .flush({ message: 'Already acknowledged.' }, { status: 409, statusText: 'Conflict' });

    // Showing the real state beats showing an error about a state that has moved on.
    controller.expectOne((request) => request.method === 'GET' && request.url.includes('/incidents'))
      .flush([{ ...incident, status: 'ACKNOWLEDGED', acknowledgedAt: '2026-09-18T09:05:00Z' }]);
    fixture.detectChanges();

    expect(text()).toContain('Acknowledged');
  });

  it('shows the incident number a controller would quote', () => {
    render([incident]);

    expect(text()).toContain('INC-1001');
  });
});
