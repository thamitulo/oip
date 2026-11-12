import { Routes } from '@angular/router';
import { adminGuard, authGuard, guestGuard } from './core/guards';
import { ShellComponent } from './layout/shell.component';

export const routes: Routes = [
  { path: 'login', canActivate: [guestGuard], loadComponent: () => import('./features/auth/login.component').then((m) => m.LoginComponent) },
  { path: 'register', canActivate: [guestGuard], loadComponent: () => import('./features/auth/register.component').then((m) => m.RegisterComponent) },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      { path: 'dashboard', title: 'Dashboard', loadComponent: () => import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent) },
      { path: 'opportunities', title: 'Opportunities', loadComponent: () => import('./features/opportunities/opportunity-list.component').then((m) => m.OpportunityListComponent) },
      { path: 'mining', title: 'Mining opportunities', data: { sector: 'MINING' }, loadComponent: () => import('./features/opportunities/opportunity-list.component').then((m) => m.OpportunityListComponent) },
      { path: 'government', title: 'Government tenders', data: { sector: 'GOVERNMENT' }, loadComponent: () => import('./features/opportunities/opportunity-list.component').then((m) => m.OpportunityListComponent) },
      { path: 'opportunities/:id', title: 'Opportunity', loadComponent: () => import('./features/opportunities/opportunity-detail.component').then((m) => m.OpportunityDetailComponent) },
      { path: 'recommended', title: 'Recommended', loadComponent: () => import('./features/work/recommended.component').then((m) => m.RecommendedComponent) },
      { path: 'focus', title: 'My focus', loadComponent: () => import('./features/focus/focus.component').then((m) => m.FocusComponent) },
      { path: 'pipeline', title: 'Bid pipeline', loadComponent: () => import('./features/work/pipeline.component').then((m) => m.PipelineComponent) },
      { path: 'tasks', title: 'Tasks', loadComponent: () => import('./features/work/tasks.component').then((m) => m.TasksComponent) },
      { path: 'notifications', title: 'Notifications', loadComponent: () => import('./features/work/notifications.component').then((m) => m.NotificationsComponent) },
      { path: 'reports', title: 'Reports', loadComponent: () => import('./features/work/reports.component').then((m) => m.ReportsComponent) },
      { path: 'settings', title: 'Settings', loadComponent: () => import('./features/work/settings.component').then((m) => m.SettingsComponent) },
      { path: 'organisations', title: 'Organisations', loadComponent: () => import('./features/organisations/organisation-list.component').then((m) => m.OrganisationListComponent) },
      { path: 'organisations/:id', title: 'Organisation', loadComponent: () => import('./features/organisations/organisation-detail.component').then((m) => m.OrganisationDetailComponent) },
      { path: 'profile', title: 'Company profile', loadComponent: () => import('./features/profile/company-profile.component').then((m) => m.CompanyProfileComponent) },
      { path: 'team', title: 'Team', loadComponent: () => import('./features/team/team.component').then((m) => m.TeamComponent) },
      {
        path: 'admin',
        canActivate: [adminGuard],
        children: [
          { path: 'sources', title: 'Source management', loadComponent: () => import('./features/admin/source-list.component').then((m) => m.SourceListComponent) },
          { path: 'sources/new', title: 'New source', loadComponent: () => import('./features/admin/source-edit.component').then((m) => m.SourceEditComponent) },
          { path: 'sources/:id', title: 'Source', loadComponent: () => import('./features/admin/source-edit.component').then((m) => m.SourceEditComponent) },
          { path: 'crawls', title: 'Crawl monitoring', loadComponent: () => import('./features/admin/crawl-monitor.component').then((m) => m.CrawlMonitorComponent) },
          { path: 'ai', title: 'AI settings', loadComponent: () => import('./features/admin/ai-settings.component').then((m) => m.AiSettingsComponent) },
          { path: 'crawls/:id', title: 'Crawl run', loadComponent: () => import('./features/admin/crawl-run.component').then((m) => m.CrawlRunComponent) },
        ],
      },
    ],
  },
  { path: '**', redirectTo: 'dashboard' },
];
