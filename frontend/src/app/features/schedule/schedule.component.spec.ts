import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { StagedScheduleImport } from '../../core/telemetry-api.service';
import { ScheduleComponent } from './schedule.component';

/**
 * The schedule review screen, rendered.
 *
 * <p>What is worth testing here is not that Angular can draw a table. It is that the screen puts a
 * planner in a position to make the decision it is asking them to make: that additions and
 * overwrites are distinguishable, that the consequences the preview names are actually shown, and
 * that a rejected feed shows the server's own reasons rather than a generic failure.
 */
describe('ScheduleComponent', () => {
  let fixture: ComponentFixture<ScheduleComponent>;
  let controller: HttpTestingController;

  const staged: StagedScheduleImport = {
    id: 7,
    status: 'STAGED',
    uploadedBy: 'planner-a',
    uploadedAt: '2026-09-18T09:00:00Z',
    activatedBy: null,
    activatedAt: null,
    discardedBy: null,
    discardedAt: null,
    preview: {
      agencies: { added: 0, updated: 1 },
      routes: { added: 2, updated: 1 },
      stops: { added: 5, updated: 3 },
      calendars: { added: 1, updated: 0 },
      trips: { added: 40, updated: 12 },
      stopTimes: 260,
      shapePoints: 90,
      unchangedInFeed: ['route M42', 'stop M42-003'],
      notes: ['2 existing route(s) or stop(s) are not in this feed. They are left in place rather than removed.']
    },
    fileNames: ['agency.txt', 'routes.txt'],
    result: null
  };

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  function render(imports: StagedScheduleImport[], role: 'ADMIN' | 'PLANNER' = 'ADMIN'): void {
    signInAs(role);
    fixture = TestBed.createComponent(ScheduleComponent);
    fixture.detectChanges();
    controller.expectOne((request) => request.url.endsWith('/admin/schedule/imports')).flush(imports);
    fixture.detectChanges();
  }

  /**
   * Signs in by seeding the session the way a completed login would.
   *
   * <p>It has to happen before the component is created, because the auth service reads the stored
   * operator once when it is constructed.
   */
  function signInAs(role: 'ADMIN' | 'PLANNER'): void {
    sessionStorage.setItem(
      'metropulse.user',
      JSON.stringify({ id: 1, email: 'a@b.test', displayName: 'A', role })
    );
  }

  /** Buttons inside the import panel; the shell contributes its own, which are not this screen's. */
  function decisionButtons(): string[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.panel button'))
      .map((button) => button.textContent?.trim() ?? '');
  }

  function uploadControl(): Element | null {
    return (fixture.nativeElement as HTMLElement).querySelector('.upload input');
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('shows what is new separately from what would be written over', () => {
    render([staged]);

    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    const routes = Array.from(rows).find((row) => row.textContent?.includes('Routes'));

    // A planner approving "three routes" needs to know two are new and one is an overwrite.
    expect(routes?.textContent).toContain('2');
    expect(routes?.textContent).toContain('1');
  });

  it('shows the consequences the preview named', () => {
    render([staged]);

    expect(text()).toContain('left in place rather than removed');
  });

  it('offers a decision on a staged import to an administrator', () => {
    render([staged], 'ADMIN');

    expect(decisionButtons()).toEqual(['Put into service', 'Discard']);
  });

  it('lets a planner upload and read, but not decide', () => {
    render([staged], 'PLANNER');

    // The preview exists for planners to review. Deciding is the administrator's act, and the API
    // enforces that regardless of what this screen shows.
    expect(uploadControl()).not.toBeNull();
    expect(decisionButtons()).toEqual([]);
    expect(text()).toContain('An administrator decides');
  });

  it('shows what activation actually did once it has happened', () => {
    render([{
      ...staged,
      status: 'ACTIVATED',
      activatedBy: 'admin-a',
      activatedAt: '2026-09-18T09:05:00Z',
      result: { agencies: 1, routes: 3, stops: 8, calendars: 1, trips: 52, stopTimes: 260, shapePoints: 90 }
    }]);

    expect(text()).toContain('activated by admin-a');
    expect(text()).toContain('3 route(s)');
    expect(decisionButtons()).toEqual([]);
  });

  it('reports the server reasons for a rejected feed, not a generic failure', () => {
    render([]);

    const file = new File(['route_id\n'], 'routes.txt', { type: 'text/plain' });
    fixture.componentInstance['stage']({
      target: { files: [file], value: '' }
    } as unknown as Event);

    controller.expectOne((request) => request.method === 'POST').flush(
      { message: 'The feed was rejected.', details: ['routes.txt line 2: route_id is required.'] },
      { status: 422, statusText: 'Unprocessable Entity' }
    );
    fixture.detectChanges();

    // The line number is the only thing that helps a planner fix the file.
    expect(text()).toContain('routes.txt line 2');
  });

  it('says what a feed needs when nothing has been uploaded', () => {
    render([]);

    expect(text()).toContain('Nothing has been uploaded');
    expect(text()).toContain('shapes are optional');
  });
});
