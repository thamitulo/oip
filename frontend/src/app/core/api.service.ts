import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AiSettings, AiStatus, AppNotification, AssistantMessage, AssistantReply, CompanyDocument, Conversation, Match,
  MatchSettings, NotificationPreferences, PipelineDetail, PipelineItem, Recommended, Requirement, Stage, Task,
  AddMemberResult, TokenInfo, OrganisationRequest, ChannelRequest, MiningOperationRequest, SupplierPlatformSummary, ChangeEvent, CompanyProfile, CrawlError, CrawlRun, CrawlRunDetail, Dashboard, DryRunResult, Member,
  OpportunityDetail, OpportunityQuery, OpportunitySummary, OrganisationDetail, OrganisationSummary, Page,
  Problem, Sector, Source, SourceHealth, Director, DirectorRequest, Fillable, FormFill, Placement, BidDocument,
} from './models';

/** Typed access to the OIP REST API. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  // Opportunities
  opportunities(query: OpportunityQuery): Observable<Page<OpportunitySummary>> {
    return this.http.get<Page<OpportunitySummary>>('/api/v1/opportunities', { params: toParams(query) });
  }
  closingSoon(days: number, sector?: Sector, size = 10): Observable<Page<OpportunitySummary>> {
    return this.http.get<Page<OpportunitySummary>>('/api/v1/opportunities/closing-soon', { params: toParams({ days, sector, size }) });
  }
  opportunity(id: string): Observable<OpportunityDetail> {
    return this.http.get<OpportunityDetail>(`/api/v1/opportunities/${id}`);
  }
  opportunityHistory(id: string): Observable<ChangeEvent[]> {
    return this.http.get<ChangeEvent[]>(`/api/v1/opportunities/${id}/history`);
  }
  dashboard(): Observable<Dashboard> {
    return this.http.get<Dashboard>('/api/v1/analytics/dashboard');
  }

  // Organisations
  organisations(params: { sector?: Sector; q?: string; page?: number; size?: number }): Observable<Page<OrganisationSummary>> {
    return this.http.get<Page<OrganisationSummary>>('/api/v1/organisations', { params: toParams(params) });
  }
  supplierPlatforms(): Observable<SupplierPlatformSummary[]> {
    return this.http.get<SupplierPlatformSummary[]>('/api/v1/supplier-platforms');
  }
  createOrganisation(body: OrganisationRequest): Observable<OrganisationDetail> {
    return this.http.post<OrganisationDetail>('/api/v1/admin/organisations', body);
  }
  updateOrganisation(id: string, body: OrganisationRequest): Observable<OrganisationDetail> {
    return this.http.put<OrganisationDetail>(`/api/v1/admin/organisations/${id}`, body);
  }
  addChannel(id: string, body: ChannelRequest): Observable<OrganisationDetail> {
    return this.http.post<OrganisationDetail>(`/api/v1/admin/organisations/${id}/channels`, body);
  }
  updateChannel(id: string, channelId: string, body: ChannelRequest): Observable<OrganisationDetail> {
    return this.http.put<OrganisationDetail>(`/api/v1/admin/organisations/${id}/channels/${channelId}`, body);
  }
  deleteChannel(id: string, channelId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/admin/organisations/${id}/channels/${channelId}`);
  }
  addOperation(id: string, body: MiningOperationRequest): Observable<OrganisationDetail> {
    return this.http.post<OrganisationDetail>(`/api/v1/admin/organisations/${id}/operations`, body);
  }
  updateOperation(id: string, operationId: string, body: MiningOperationRequest): Observable<OrganisationDetail> {
    return this.http.put<OrganisationDetail>(`/api/v1/admin/organisations/${id}/operations/${operationId}`, body);
  }
  deleteOperation(id: string, operationId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/admin/organisations/${id}/operations/${operationId}`);
  }
  organisation(id: string): Observable<OrganisationDetail> {
    return this.http.get<OrganisationDetail>(`/api/v1/organisations/${id}`);
  }

  // Company profile & team
  profile(): Observable<CompanyProfile> {
    return this.http.get<CompanyProfile>('/api/v1/companies/profile');
  }
  saveProfile(profile: CompanyProfile): Observable<CompanyProfile> {
    return this.http.put<CompanyProfile>('/api/v1/companies/profile', profile);
  }
  members(): Observable<Member[]> {
    return this.http.get<Member[]>('/api/v1/users');
  }
  addMember(body: { email: string; fullName: string; role: string }): Observable<AddMemberResult> {
    return this.http.post<AddMemberResult>('/api/v1/users', body);
  }
  resendInvitation(id: string): Observable<AddMemberResult> {
    return this.http.post<AddMemberResult>(`/api/v1/users/${id}/resend-invitation`, {});
  }

  // Account e-mails (no sign-in needed except resend)
  forgotPassword(email: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/forgot-password', { email });
  }
  tokenInfo(token: string): Observable<TokenInfo> {
    return this.http.post<TokenInfo>('/api/v1/auth/token-info', { token });
  }
  setPassword(token: string, password: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/set-password', { token, password });
  }
  verifyEmail(token: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/verify-email', { token });
  }
  resendVerification(): Observable<void> {
    return this.http.post<void>('/api/v1/auth/verify-email/resend', {});
  }
  updateMember(id: string, body: { role?: string; status?: string }): Observable<Member> {
    return this.http.put<Member>(`/api/v1/users/${id}`, body);
  }

  // Administration
  sources(params: { sector?: Sector; status?: string; enabled?: boolean; q?: string; page?: number; size?: number }): Observable<Page<Source>> {
    return this.http.get<Page<Source>>('/api/v1/admin/sources', { params: toParams(params) });
  }
  source(id: string): Observable<Source> {
    return this.http.get<Source>(`/api/v1/admin/sources/${id}`);
  }
  createSource(body: unknown): Observable<Source> {
    return this.http.post<Source>('/api/v1/admin/sources', body);
  }
  updateSource(id: string, body: unknown): Observable<Source> {
    return this.http.put<Source>(`/api/v1/admin/sources/${id}`, body);
  }
  crawlSource(id: string): Observable<{ jobId: string }> {
    return this.http.post<{ jobId: string }>(`/api/v1/admin/sources/${id}/crawl`, null);
  }
  testSource(id: string): Observable<DryRunResult> {
    return this.http.post<DryRunResult>(`/api/v1/admin/sources/${id}/test`, null);
  }
  crawlRuns(params: { sourceId?: string; status?: string; page?: number; size?: number }): Observable<Page<CrawlRun>> {
    return this.http.get<Page<CrawlRun>>('/api/v1/admin/crawls', { params: toParams(params) });
  }
  crawlRun(id: string): Observable<CrawlRunDetail> {
    return this.http.get<CrawlRunDetail>(`/api/v1/admin/crawls/${id}`);
  }
  sourceErrors(params: { sourceId?: string; page?: number; size?: number }): Observable<Page<CrawlError>> {
    return this.http.get<Page<CrawlError>>('/api/v1/admin/source-errors', { params: toParams(params) });
  }
  sourceHealth(): Observable<SourceHealth[]> {
    return this.http.get<SourceHealth[]>('/api/v1/admin/source-health');
  }

  // Documents & requirements
  requirements(id: string): Observable<Requirement[]> {
    return this.http.get<Requirement[]>(`/api/v1/opportunities/${id}/requirements`);
  }
  processDocuments(id: string): Observable<void> {
    return this.http.post<void>(`/api/v1/opportunities/${id}/documents/process`, null);
  }
  companyDocuments(): Observable<CompanyDocument[]> {
    return this.http.get<CompanyDocument[]>('/api/v1/companies/documents');
  }
  uploadCompanyDocument(file: File, documentType: string, description: string): Observable<CompanyDocument> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<CompanyDocument>('/api/v1/companies/documents', form,
      { params: toParams({ documentType, description }) });
  }
  deleteCompanyDocument(id: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/companies/documents/${id}`);
  }
  download(url: string): Observable<Blob> {
    return this.http.get(url, { responseType: 'blob' });
  }

  // Directors / members (personal information; profile editors only)
  directors(): Observable<Director[]> {
    return this.http.get<Director[]>('/api/v1/companies/profile/directors');
  }
  addDirector(body: DirectorRequest): Observable<Director> {
    return this.http.post<Director>('/api/v1/companies/profile/directors', body);
  }
  updateDirector(id: string, body: DirectorRequest): Observable<Director> {
    return this.http.put<Director>(`/api/v1/companies/profile/directors/${id}`, body);
  }
  deleteDirector(id: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/companies/profile/directors/${id}`);
  }

  // Tender form filling and bid documents (per pipeline item)
  fillable(opportunityId: string): Observable<Fillable> {
    return this.http.get<Fillable>(`/api/v1/tenant/opportunities/${opportunityId}/form-fills`);
  }
  startFormFill(opportunityId: string, opportunityDocumentId: string, zipEntry: string | null): Observable<FormFill> {
    return this.http.post<FormFill>(`/api/v1/tenant/opportunities/${opportunityId}/form-fills`, { opportunityDocumentId, zipEntry });
  }
  uploadFormFill(opportunityId: string, file: File): Observable<FormFill> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<FormFill>(`/api/v1/tenant/opportunities/${opportunityId}/form-fills/upload`, form);
  }
  formFill(opportunityId: string, fillId: string): Observable<FormFill> {
    return this.http.get<FormFill>(`/api/v1/tenant/opportunities/${opportunityId}/form-fills/${fillId}`);
  }
  saveFormFill(opportunityId: string, fillId: string, placements: Placement[]): Observable<FormFill> {
    return this.http.put<FormFill>(`/api/v1/tenant/opportunities/${opportunityId}/form-fills/${fillId}`, placements);
  }
  generateFormFill(opportunityId: string, fillId: string): Observable<FormFill> {
    return this.http.post<FormFill>(`/api/v1/tenant/opportunities/${opportunityId}/form-fills/${fillId}/generate`, null);
  }
  deleteFormFill(opportunityId: string, fillId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/tenant/opportunities/${opportunityId}/form-fills/${fillId}`);
  }
  formFillPageUrl(opportunityId: string, fillId: string, page: number): string {
    return `/api/v1/tenant/opportunities/${opportunityId}/form-fills/${fillId}/pages/${page}`;
  }
  bidDocuments(opportunityId: string): Observable<BidDocument[]> {
    return this.http.get<BidDocument[]>(`/api/v1/tenant/opportunities/${opportunityId}/bid-documents`);
  }
  uploadBidDocument(opportunityId: string, file: File): Observable<BidDocument> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<BidDocument>(`/api/v1/tenant/opportunities/${opportunityId}/bid-documents`, form);
  }
  bidDocumentUrl(opportunityId: string, documentId: string): string {
    return `/api/v1/tenant/opportunities/${opportunityId}/bid-documents/${documentId}`;
  }
  deleteBidDocument(opportunityId: string, documentId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/tenant/opportunities/${opportunityId}/bid-documents/${documentId}`);
  }

  // Matching
  match(id: string): Observable<Match> {
    return this.http.get<Match>(`/api/v1/opportunities/${id}/match`);
  }
  explainMatch(id: string): Observable<Match['aiExplanation']> {
    return this.http.post<Match['aiExplanation']>(`/api/v1/opportunities/${id}/match/explain`, null);
  }
  classify(id: string): Observable<unknown> {
    return this.http.post(`/api/v1/opportunities/${id}/classify`, null);
  }
  recommended(minScore: number, page: number, size = 20): Observable<Page<Recommended>> {
    return this.http.get<Page<Recommended>>('/api/v1/opportunities/recommended', { params: toParams({ minScore, page, size }) });
  }
  matchSettings(): Observable<MatchSettings> {
    return this.http.get<MatchSettings>('/api/v1/companies/match-settings');
  }
  saveMatchSettings(s: MatchSettings): Observable<MatchSettings> {
    return this.http.put<MatchSettings>('/api/v1/companies/match-settings', s);
  }

  // Pipeline
  pipeline(): Observable<PipelineItem[]> {
    return this.http.get<PipelineItem[]>('/api/v1/tenant/pipeline');
  }
  pipelineDetail(opportunityId: string): Observable<PipelineDetail> {
    return this.http.get<PipelineDetail>(`/api/v1/tenant/opportunities/${opportunityId}`);
  }
  shortlist(opportunityId: string, comment?: string): Observable<PipelineItem> {
    return this.http.post<PipelineItem>(`/api/v1/tenant/opportunities/${opportunityId}/shortlist`, { comment });
  }
  setStage(opportunityId: string, stage: Stage, comment?: string): Observable<PipelineItem> {
    return this.http.put<PipelineItem>(`/api/v1/tenant/opportunities/${opportunityId}/status`, { stage, comment });
  }
  assign(opportunityId: string, userId: string | null): Observable<PipelineItem> {
    return this.http.post<PipelineItem>(`/api/v1/tenant/opportunities/${opportunityId}/assign`, { userId });
  }
  updatePipelineDetails(opportunityId: string, body: { priority?: string; bidValue?: number | null; outcomeNotes?: string | null }): Observable<PipelineItem> {
    return this.http.put<PipelineItem>(`/api/v1/tenant/opportunities/${opportunityId}/details`, body);
  }
  addNote(opportunityId: string, body: string): Observable<unknown> {
    return this.http.post(`/api/v1/tenant/opportunities/${opportunityId}/notes`, { body });
  }
  addTask(opportunityId: string, body: { title: string; dueAt?: string | null; assigneeId?: string | null }): Observable<Task> {
    return this.http.post<Task>(`/api/v1/tenant/opportunities/${opportunityId}/tasks`, body);
  }
  decide(opportunityId: string, decision: 'GO' | 'NO_GO', rationale: string): Observable<unknown> {
    return this.http.post(`/api/v1/tenant/opportunities/${opportunityId}/decisions`, { decision, rationale });
  }
  tasks(mine: boolean): Observable<Task[]> {
    return this.http.get<Task[]>('/api/v1/tenant/tasks', { params: toParams({ mine }) });
  }
  updateTask(id: string, status: string): Observable<Task> {
    return this.http.put<Task>(`/api/v1/tenant/tasks/${id}`, { status });
  }

  // Notifications
  notifications(unreadOnly = false): Observable<AppNotification[]> {
    return this.http.get<AppNotification[]>('/api/v1/notifications', { params: toParams({ unreadOnly }) });
  }
  unreadCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>('/api/v1/notifications/unread-count');
  }
  markRead(id: string): Observable<void> {
    return this.http.post<void>(`/api/v1/notifications/${id}/read`, null);
  }
  markAllRead(): Observable<void> {
    return this.http.post<void>('/api/v1/notifications/read-all', null);
  }
  notificationPreferences(): Observable<NotificationPreferences> {
    return this.http.get<NotificationPreferences>('/api/v1/notifications/preferences');
  }
  saveNotificationPreferences(p: NotificationPreferences): Observable<NotificationPreferences> {
    return this.http.put<NotificationPreferences>('/api/v1/notifications/preferences', p);
  }

  // Reports & privacy
  pipelineReport(): Observable<{ byStage: { stage: string; count: number; value: number }[]; outcomes: Record<string, number | null>;
    decisions: { decision: string; count: number }[]; tasks: Record<string, number> }> {
    return this.http.get<any>('/api/v1/analytics/pipeline');
  }
  weeklyOpportunities(weeks = 12): Observable<{ week: string; government: number; mining: number; total: number }[]> {
    return this.http.get<any>('/api/v1/analytics/opportunities', { params: toParams({ weeks }) });
  }
  categoryReport(): Observable<{ category: string; count: number }[]> {
    return this.http.get<any>('/api/v1/analytics/categories');
  }
  exportMyData(): Observable<unknown> {
    return this.http.get('/api/v1/users/me/export');
  }
  deleteMyAccount(password: string): Observable<void> {
    return this.http.post<void>('/api/v1/users/me/delete', { password });
  }

  // AI
  aiStatus(): Observable<AiStatus> {
    return this.http.get<AiStatus>('/api/v1/ai/status');
  }
  ask(message: string, conversationId: string | null): Observable<AssistantReply> {
    return this.http.post<AssistantReply>('/api/v1/ai/assistant/messages', { message, conversationId });
  }
  conversations(): Observable<Conversation[]> {
    return this.http.get<Conversation[]>('/api/v1/ai/assistant/conversations');
  }
  conversation(id: string): Observable<AssistantMessage[]> {
    return this.http.get<AssistantMessage[]>(`/api/v1/ai/assistant/conversations/${id}`);
  }
  deleteConversation(id: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/ai/assistant/conversations/${id}`);
  }
  aiAdminSettings(): Observable<{ settings: AiSettings; providerConfigured: boolean; provider: string }> {
    return this.http.get<any>('/api/v1/admin/ai/settings');
  }
  saveAiSettings(s: AiSettings): Observable<AiSettings> {
    return this.http.put<AiSettings>('/api/v1/admin/ai/settings', s);
  }
  aiUsage(): Observable<{ day: string; feature: string; status: string; calls: number; input_tokens: number; output_tokens: number }[]> {
    return this.http.get<any>('/api/v1/admin/ai/usage');
  }
}

export function toParams(values: object): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== null && value !== '') {
      params = params.set(key, String(value));
    }
  }
  return params;
}

/** Human-readable message from an API error (RFC 9457 problem details). */
export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const problem = error.error as Problem | null;
    if (problem?.errors?.length) {
      return problem.errors.map((e) => `${e.field}: ${e.message}`).join('; ');
    }
    if (problem?.detail) return problem.detail;
    if (error.status === 0) return 'The server could not be reached.';
    return `Request failed (${error.status}).`;
  }
  return 'Something went wrong.';
}
