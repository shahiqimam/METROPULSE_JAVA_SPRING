import { Component } from '@angular/core';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  template: `
    <main class="dashboard-shell">
      <section class="network-strip">
        <h1>MetroPulse Control Center</h1>
        <p>Foundation build for realtime transit operations.</p>
      </section>
    </main>
  `,
  styles: [`
    .dashboard-shell {
      min-height: 100vh;
      padding: 24px;
      font-family: Arial, sans-serif;
      background: #101820;
      color: #f4f7fb;
    }

    .network-strip {
      max-width: 1120px;
      margin: 0 auto;
      border-left: 4px solid #39b5a7;
      padding-left: 20px;
    }

    h1 {
      margin: 0 0 8px;
      font-size: 32px;
      letter-spacing: 0;
    }

    p {
      margin: 0;
      color: #b8c7d9;
    }
  `]
})
export class DashboardComponent {}
