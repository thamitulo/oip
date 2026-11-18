// API contract types. These mirror the backend DTOs under /api/v1.

export type TenantRole =
  | 'TENANT_OWNER' | 'TENANT_ADMIN' | 'PROCUREMENT_MANAGER' | 'BID_MANAGER'
  | 'BID_RESEARCHER' | 'STANDARD_USER' | 'READ_ONLY_USER';

export const TENANT_ROLES: TenantRole[] = [
  'TENANT_OWNER', 'TENANT_ADMIN', 'PROCUREMENT_MANAGER', 'BID_MANAGER',
  'BID_RESEARCHER', 'STANDARD_USER', 'READ_ONLY_USER',
];

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Problem {
  title?: string;
  status?: number;
  detail?: string;
  errors?: { field: string; message: string }[];
}

export interface Membership { tenantId: string; tenantName: string; role: TenantRole; }

export interface CurrentUser {
  userId: string;
  email: string;
  fullName: string;
  timezone: string;
  tenantId: string;
  tenantName: string;
  role: TenantRole;
  platformAdmin: boolean;
  permissions: string[];
  memberships: Membership[];
  emailVerified: boolean;
}

export interface AuthResponse {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  user: CurrentUser;
}

export type Sector = 'GOVERNMENT' | 'MINING' | 'PRIVATE' | 'AGGREGATOR';
export type EventType =
  | 'TENDER' | 'RFQ' | 'RFP' | 'RFI' | 'EOI' | 'ESD_PROGRAMME' | 'SUPPLIER_INVITATION'
  | 'PROCUREMENT_PLAN' | 'EXPRESSION_OF_INTEREST' | 'LAND_LEASE' | 'OTHER';
export type OpportunityStatus = 'OPEN' | 'CLOSED' | 'AWARDED' | 'CANCELLED' | 'EXPIRED' | 'UNKNOWN';
export type ProcurementCategory = 'GOODS' | 'SERVICES' | 'WORKS' | 'CONSULTING_SERVICES' | 'DISPOSAL' | 'NOT_SPECIFIED';
export type Urgency = 'CLOSED' | 'CLOSING_TODAY' | 'URGENT' | 'SOON' | 'OPEN' | 'UNKNOWN';

export interface OpportunitySummary {
  id: string;
  title: string;
  referenceNumber: string | null;
  eventType: EventType;
  procurementCategory: ProcurementCategory;
  status: OpportunityStatus;
  statusInferred: boolean;
  organisationId: string | null;
  organisationName: string | null;
  sector: Sector;
  province: string | null;
  locality: string | null;
  closingDate: string | null;
  closingAt: string | null;
  closingTimeSpecified: boolean;
  daysUntilClosing: number | null;
  urgency: Urgency;
  publishedAt: string | null;
  firstDiscoveredAt: string;
  sourceId: string;
  sourceName: string;
  sourceVerified: boolean;
  sourceUrl: string;
  documentCount: number;
  submissionMethod: SubmissionMethod;
  miningRelated: boolean;
  briefingCompulsory: boolean | null;
}

export interface OpportunityDocument {
  id: string; title: string | null; url: string; documentType: string; fileFormat: string | null;
  publishedAt: string | null; firstSeenAt: string; removedAt: string | null;
  storedDocumentId: string | null; processingStatus: string; processingError: string | null; pageCount: number | null;
  originalStored: boolean;
}

export interface ChangeEvent {
  id: string; fieldName: string; oldValue: string | null; newValue: string | null; material: boolean; detectedAt: string;
}

export interface Evidence {
  sourceName: string; sourceUrl: string; evidenceUrl: string | null; sourceStatus: IntegrationStatus;
  sourceLastVerifiedAt: string | null; firstDiscoveredAt: string; lastCheckedAt: string; lastSeenAt: string;
  lastChangedAt: string | null; closingRaw: string | null; contentHash: string; licenceNotes: string | null;
}

export interface OpportunityDetail {
  summary: OpportunitySummary;
  description: string | null;
  sourceCategoryLabel: string | null;
  estimatedValue: number | null;
  currency: string | null;
  contractDuration: string | null;
  briefingDetails: string | null;
  briefingCompulsory: boolean | null;
  contactName: string | null;
  contactEmail: string | null;
  contactPhone: string | null;
  mineSite: string | null;
  duplicateOfId: string | null;
  documents: OpportunityDocument[];
  categories: { categoryType: string; code: string; provenance: string; confidence: number | null }[];
  evidence: Evidence;
  submission: Submission;
  briefingAt: string | null;
  briefingVenue: string | null;
  specialConditions: string | null;
  miningEvidence: string | null;
}

export interface OpportunityQuery {
  q?: string;
  sector?: Sector;
  eventType?: EventType;
  procurementCategory?: ProcurementCategory;
  province?: string;
  organisationId?: string;
  closingFrom?: string;
  closingTo?: string;
  activeOnly?: boolean;
  sort?: 'CLOSING_SOONEST' | 'NEWEST' | 'RELEVANCE' | 'PUBLISHED';
  submissionMethod?: SubmissionMethod;
  compulsoryBriefing?: boolean;
  page?: number;
  size?: number;
}

export type IntegrationStatus =
  | 'ACTIVE_PUBLIC' | 'ACTIVE_API' | 'AUTHORISED_INTEGRATION' | 'LOGIN_REQUIRED' | 'REGISTRATION_REQUIRED'
  | 'PENDING_VERIFICATION' | 'TEMPORARILY_UNAVAILABLE' | 'DISABLED' | 'UNSUPPORTED';

export interface Source {
  id: string; name: string; organisationId: string | null; organisationName: string | null; sourceUrl: string | null;
  sourceType: string; sector: Sector; country: string; industry: string | null; accessMethod: string;
  integrationStatus: IntegrationStatus; enabled: boolean; authenticationRequired: boolean | null;
  publiclyAccessible: boolean | null; robotsPolicy: string; termsReviewStatus: string; licenceNotes: string | null;
  rateLimitPerMinute: number; adapterType: string | null; parserConfig: string | null; reliability: string;
  priority: number; verificationNotes: string | null; lastVerifiedAt: string | null;
  lastAttemptedCrawlAt: string | null; lastSuccessfulCrawlAt: string | null; crawlIntervalMinutes: number | null;
  nextCrawlAt: string | null; consecutiveFailures: number | null; crawlBlockers: string[]; updatedAt: string;
}

export interface CrawlRun {
  id: string; jobId: string; sourceId: string; sourceName: string; trigger: 'SCHEDULED' | 'MANUAL' | null;
  startedAt: string; finishedAt: string | null; status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'; outcome: string | null;
  recordsFound: number; recordsDiscovered: number; recordsUpdated: number; recordsUnchanged: number;
  recordsRejected: number; httpStatus: number | null; contentHash: string | null; message: string | null;
}

export interface CrawlError {
  id: string; runId: string; sourceId: string; sourceName: string; errorType: string; message: string;
  url: string | null; itemReference: string | null; occurredAt: string;
}

export interface CrawlRunDetail {
  run: CrawlRun;
  errors: CrawlError[];
  observations: { id: string; url: string; httpStatus: number | null; contentType: string | null; contentHash: string | null; bytes: number | null; fetchedAt: string; excerpt: string | null }[];
  executionLog: string | null;
}

export interface SourceHealth {
  sourceId: string; sourceName: string; integrationStatus: IntegrationStatus; enabled: boolean;
  lastAttemptedCrawlAt: string | null; lastSuccessfulCrawlAt: string | null; lastOutcome: string | null;
  consecutiveFailures: number | null; nextCrawlAt: string | null; opportunityCount: number; health: string;
}

export interface DryRunResult {
  outcome: string; message: string | null; httpStatus: number | null; recordsFound: number;
  preview: { referenceNumber: string | null; title: string; eventType: EventType; status: OpportunityStatus; closingDate: string | null; sourceUrl: string }[];
  itemErrors: { errorType: string; message: string; url: string | null; itemReference: string | null }[];
  log: string[];
}

export interface Bucket { key: string; label: string; count: number; }

export interface Dashboard {
  activeOpportunities: number; newToday: number; closingWithin7Days: number; miningActive: number;
  governmentActive: number; sourcesEnabled: number; sourcesHealthy: number; lastSuccessfulCrawlAt: string | null;
  byProvince: Bucket[]; byCategory: Bucket[]; byEventType: Bucket[]; bySubmissionMethod: Bucket[]; topOrganisations: Bucket[];
  recentSourceUpdates: { sourceId: string; sourceName: string; finishedAt: string; outcome: string; recordsDiscovered: number; recordsUpdated: number }[];
}

export interface OrganisationSummary {
  id: string; name: string; shortName: string | null; organisationType: string; sector: Sector;
  website: string | null; websiteVerification: 'UNVERIFIED' | 'VERIFIED' | 'UNREACHABLE' | 'BLOCKED'; province: string | null;
}

export interface OrganisationRequest {
  name: string; shortName: string | null; organisationType: string; sector: Sector; website: string | null;
  websiteVerification: string; province: string | null; notes: string | null; primaryCommodities: string | null;
}
export interface ChannelRequest {
  channelType: string; name: string; url: string | null; supplierPlatformId: string | null; description: string | null;
  verificationStatus: string;
}
export interface SupplierPlatformSummary { id: string; name: string; }
export interface MiningOperation { id: string; name: string; province: string | null; locality: string | null; commodity: string | null; }
export type MiningOperationRequest = Omit<MiningOperation, 'id'>;

export interface OrganisationDetail {
  organisation: OrganisationSummary;
  notes: string | null;
  primaryCommodities: string | null;
  channels: { id: string; channelType: string; name: string; url: string | null; supplierPlatform: string | null; description: string | null; verificationStatus: string; lastVerifiedAt: string | null; supplierPlatformId: string | null }[];
  sources: { id: string; name: string; sourceUrl: string | null; integrationStatus: IntegrationStatus; enabled: boolean; lastSuccessfulCrawlAt: string | null; lastVerifiedAt: string | null; verificationNotes: string | null }[];
  /** Mines, plants and sites; only mining-sector organisations have them. */
  operations: MiningOperation[];
}

export type VerificationStatus = 'SELF_DECLARED' | 'DOCUMENT_PROVIDED' | 'VERIFIED' | 'EXPIRED';

export interface CompanyProfile {
  id?: string;
  updatedAt?: string;
  legalName: string;
  tradingName: string | null;
  registrationNumber: string | null;
  vatNumber: string | null;
  website: string | null;
  description: string | null;
  province: string | null;
  city: string | null;
  businessSize: string | null;
  annualTurnoverBand: string | null;
  bbbeeLevel: string | null;
  blackOwnershipPercentage: number | null;
  enterpriseClassification: string | null;
  cidbGrading: string | null;
  miningSectorExperience: string | null;
  governmentSectorExperience: string | null;
  contactName: string | null;
  contactEmail: string | null;
  contactPhone: string | null;
  industries: string[];
  services: string[];
  products: string[];
  commodities: string[];
  operatingRegions: string[];
  certifications: { name: string; issuingBody: string | null; certificateNumber: string | null; validUntil: string | null; verificationStatus: VerificationStatus }[];
  registrations: { registrationType: string; registrationBody: string; registrationNumber: string | null; validUntil: string | null; verificationStatus: VerificationStatus }[];
  capabilities: { name: string; description: string | null; yearsExperience: number | null }[];
  references: { clientName: string; projectTitle: string; description: string | null; sector: string | null; contractValue: number | null; yearCompleted: number | null }[];
  // Details used to pre-fill tender forms
  csdSupplierNumber: string | null;
  incomeTaxNumber: string | null;
  uifReferenceNumber: string | null;
  compensationFundNumber: string | null;
  cidbCrsNumber: string | null;
  physicalAddress: string | null;
  postalAddress: string | null;
  bbbeeCertificateNumber: string | null;
  bbbeeCertificateExpiry: string | null;
  bbbeeVerificationAgency: string | null;
}

export type DirectorRole = 'DIRECTOR' | 'MEMBER' | 'SHAREHOLDER' | 'TRUSTEE';
/** Identity numbers are never sent back in full: only `idNumberMasked`. */
export interface Director {
  id: string; fullName: string; role: DirectorRole; idType: 'SA_ID' | 'PASSPORT' | null; idNumberMasked: string | null;
  incomeTaxNumber: string | null; shareholdingPercentage: number | null; inServiceOfState: boolean;
}
/** `idNumber`: omit/null keeps the stored number, '' removes it. */
export interface DirectorRequest {
  fullName: string; role: DirectorRole; idType: 'SA_ID' | 'PASSPORT' | null; idNumber?: string | null;
  incomeTaxNumber: string | null; shareholdingPercentage: number | null; inServiceOfState: boolean;
}

export interface BidDocument { id: string; fileName: string; contentType: string; sizeBytes: number; uploadedByName: string | null; uploadedAt: string; }

export interface Placement {
  id: string; page: number; x: number; y: number; width: number; height: number; fontSize: number;
  field: string; fieldLabel: string | null; value: string; source: 'LABEL' | 'TABLE' | 'ACROFORM' | 'MANUAL';
  confidence: number; labelText: string | null; acroField: string | null; missing: boolean; ref: string | null;
}
export interface FormFill {
  id: string; opportunityId: string; fileName: string; status: 'DRAFT' | 'GENERATED'; pages: { width: number; height: number }[];
  placements: Placement[]; warnings: string[]; ocrPages: number; bidDocumentId: string | null; idNumbersWithheld: boolean;
}
export interface Fillable {
  documents: { opportunityDocumentId: string; title: string; zipEntry: string | null; status: 'READY' | 'NOT_DOWNLOADED' }[];
  fills: { id: string; fileName: string; status: 'DRAFT' | 'GENERATED'; updatedAt: string; bidDocumentId: string | null }[];
  ocrAvailable: boolean;
}

export interface Member {
  id: string; email: string; fullName: string; role: TenantRole; status: 'ACTIVE' | 'DISABLED';
  lastLoginAt: string | null; memberSince: string; invitationPending: boolean;
}

export interface AddMemberResult { member: Member; invitationEmailed: boolean; invitationUrl: string | null; }

export interface TokenInfo { purpose: 'PASSWORD_RESET' | 'INVITATION' | 'EMAIL_VERIFICATION'; fullName: string; tenantName: string | null; }

export const PROVINCES: { code: string; label: string }[] = [
  { code: 'EASTERN_CAPE', label: 'Eastern Cape' },
  { code: 'FREE_STATE', label: 'Free State' },
  { code: 'GAUTENG', label: 'Gauteng' },
  { code: 'KWAZULU_NATAL', label: 'KwaZulu-Natal' },
  { code: 'LIMPOPO', label: 'Limpopo' },
  { code: 'MPUMALANGA', label: 'Mpumalanga' },
  { code: 'NORTH_WEST', label: 'North West' },
  { code: 'NORTHERN_CAPE', label: 'Northern Cape' },
  { code: 'WESTERN_CAPE', label: 'Western Cape' },
];

export const EVENT_TYPES: EventType[] = ['TENDER', 'RFQ', 'RFP', 'RFI', 'EOI', 'ESD_PROGRAMME', 'SUPPLIER_INVITATION', 'PROCUREMENT_PLAN', 'EXPRESSION_OF_INTEREST', 'LAND_LEASE', 'OTHER'];
export const CATEGORIES: ProcurementCategory[] = ['GOODS', 'SERVICES', 'WORKS', 'CONSULTING_SERVICES', 'DISPOSAL', 'NOT_SPECIFIED'];

// ---- Phases 5-8 ----------------------------------------------------------
export type SubmissionMethod = 'ELECTRONIC' | 'PHYSICAL' | 'ELECTRONIC_OR_PHYSICAL' | 'NOT_SPECIFIED';
export type Provenance = 'SOURCE' | 'SOURCE_POLICY' | 'DOCUMENT' | 'AI' | 'HUMAN';

export interface Submission {
  method: SubmissionMethod; channel: string | null; address: string | null; twoEnvelope: boolean | null;
  provenance: Provenance | null; evidence: string | null;
}

export interface Requirement {
  id: string; type: string; value: string | null; mandatory: boolean | null; provenance: Provenance;
  evidence: string | null; documentTitle: string | null;
}

export type MatchLabel = 'STRONG_MATCH' | 'POSSIBLE_MATCH' | 'WEAK_MATCH' | 'REQUIRES_MANUAL_REVIEW';

export interface Match {
  opportunityId: string; score: number; label: MatchLabel; dimensions: Record<string, number>;
  matched: string[]; missing: string[]; disqualifiers: string[];
  aiExplanation: { summary: string; recommendation: string; strengths: string[]; gaps: string[]; questionsToClarify: string[];
    model: string; generatedAt: string; disclaimer: string } | null;
  computedAt: string;
}

export interface Recommended { opportunity: OpportunitySummary; score: number; label: MatchLabel; disqualifierCount: number; }

export type Stage = 'DISCOVERED' | 'UNDER_REVIEW' | 'SHORTLISTED' | 'GO_NO_GO' | 'DOCUMENTS_BEING_PREPARED' | 'BID_IN_PROGRESS'
  | 'SUBMITTED' | 'AWARDED' | 'UNSUCCESSFUL' | 'WITHDRAWN' | 'EXPIRED';
export const STAGES: Stage[] = ['SHORTLISTED', 'UNDER_REVIEW', 'GO_NO_GO', 'DOCUMENTS_BEING_PREPARED', 'BID_IN_PROGRESS',
  'SUBMITTED', 'AWARDED', 'UNSUCCESSFUL', 'WITHDRAWN', 'EXPIRED', 'DISCOVERED'];

export interface PipelineItem {
  id: string; opportunityId: string; stage: Stage; priority: 'LOW' | 'MEDIUM' | 'HIGH'; assignedTo: string | null;
  assignedToName: string | null; submittedAt: string | null; bidValue: number | null; outcomeNotes: string | null;
  openTasks: number; matchScore: number | null; updatedAt: string; opportunity: OpportunitySummary | null;
}

export interface Task {
  id: string; opportunityId: string; opportunityTitle: string; title: string; dueAt: string | null; assigneeId: string | null;
  assigneeName: string | null; status: 'OPEN' | 'DONE' | 'CANCELLED'; remindAt: string | null; createdAt: string; completedAt: string | null;
}

export interface PipelineDetail {
  item: PipelineItem;
  notes: { id: string; body: string; authorName: string; createdAt: string }[];
  tasks: Task[];
  decisions: { id: string; decision: 'GO' | 'NO_GO'; rationale: string | null; decidedByName: string; decidedAt: string }[];
  history: { eventType: string; fromValue: string | null; toValue: string | null; comment: string | null; actorName: string | null; occurredAt: string }[];
}

export interface AppNotification {
  id: string; type: string; title: string; body: string | null; link: string | null; opportunityId: string | null;
  readAt: string | null; createdAt: string;
}

export interface NotificationPreferences {
  inAppEnabled: boolean; emailEnabled: boolean; emailFrequency: 'IMMEDIATE' | 'DAILY_DIGEST' | 'OFF'; minMatchScore: number;
  urgentAlerts: boolean; closingReminderDays: number; provinces: string | null; eventTypes: string | null; sectors: string | null;
}

export interface MatchSettings {
  weightServices: number; weightIndustry: number; weightCapability: number; weightGeography: number;
  weightRegistrations: number; weightOther: number; strongThreshold: number; possibleThreshold: number;
}

export interface AiStatus {
  configured: boolean; enabled: boolean; assistantAvailable: boolean; matchExplanationAvailable: boolean;
  classificationAvailable: boolean; model: string;
}

export interface AiSettings {
  enabled: boolean; model: string; effort: string; assistantEnabled: boolean; classificationEnabled: boolean;
  classificationDailyLimit: number; assistantDailyLimitPerTenant: number; matchExplanationEnabled: boolean; serverSideFallback: boolean;
}

export interface AssistantMessage { id: string; role: 'USER' | 'ASSISTANT'; content: string; createdAt: string; }
export interface AssistantReply { conversationId: string; message: AssistantMessage; toolsUsed: string[]; }
export interface Conversation { id: string; title: string; createdAt: string; updatedAt: string; }

export interface CompanyDocument {
  id: string; documentType: string; fileName: string; contentType: string; sizeBytes: number; checksumSha256: string;
  description: string | null; extractionStatus: string; uploadedAt: string;
}
