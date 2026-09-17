import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'login',
    loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent)
  },
  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () => import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent)
  },
  {
    path: 'incidents',
    canActivate: [authGuard],
    loadComponent: () => import('./features/incidents/incidents.component').then((m) => m.IncidentsComponent)
  },
  {
    path: 'ev',
    canActivate: [authGuard],
    loadComponent: () => import('./features/ev/ev.component').then((m) => m.EvComponent)
  },
  {
    path: 'analytics',
    canActivate: [authGuard],
    loadComponent: () => import('./features/analytics/analytics.component').then((m) => m.AnalyticsComponent)
  },
  {
    path: 'playback',
    canActivate: [authGuard],
    loadComponent: () => import('./features/playback/playback.component').then((m) => m.PlaybackComponent)
  },
  { path: '**', redirectTo: 'dashboard' }
];
