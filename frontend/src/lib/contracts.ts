export type Role = "ADMIN" | "ENGINEER" | "VIEWER";
export type Organization = {
  id: string;
  name: string;
  role: Role;
  createdAt: string;
};
export type Identity = {
  subject: string;
  username: string | null;
  issuer: string;
};
export type Session = {
  identity: Identity;
  expiresAt: number;
  maxFileBytes: number;
  maxQuestionBytes: number;
};
export const incidentStatuses = ["OPEN", "INVESTIGATING", "RESOLVED"] as const;
export type IncidentStatus = (typeof incidentStatuses)[number];
export type Incident = {
  id: string;
  organizationId: string;
  title: string;
  description: string;
  status: IncidentStatus;
  version: number;
  createdAt: string;
};
export type IncidentPage = {
  items: Incident[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};
export type Revision = {
  id: string;
  organizationId: string;
  documentId: string;
  revisionNumber: number;
  originalFilename: string;
  mediaType: string;
  fileSize: number;
  checksum: string;
  uploadedBy: string;
  status: "UPLOADING" | "AVAILABLE" | "FAILED";
  createdAt: string;
};
export type DocumentStatus = "UPLOADING" | "AVAILABLE" | "FAILED" | "DELETED";
export type Document = {
  id: string;
  organizationId: string;
  title: string;
  createdBy: string;
  status: DocumentStatus;
  version: number;
  createdAt: string;
  modifiedAt: string;
  currentRevision: Revision | null;
};
export type DocumentPage<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};
export type Ingestion = {
  status:
    | "NOT_SCHEDULED"
    | "PENDING"
    | "RUNNING"
    | "RETRY"
    | "VERIFIED"
    | "FAILED"
    | "CANCELLED";
  attempts: number;
  errorCode: string | null;
  verifiedAt: string | null;
  processingMs: number | null;
};
export type Indexing = {
  status:
    | "NOT_SCHEDULED"
    | "PENDING_EXTRACTION"
    | "EXTRACTING"
    | "EXTRACTED"
    | "INDEXING"
    | "INDEXED"
    | "RETRY"
    | "FAILED"
    | "CANCELLED";
  phase: string | null;
  attempts: number;
  errorCode: string | null;
  processingMs: number | null;
  publishedAt: string | null;
  configuration: string | null;
  chunkCount: number | null;
  published: boolean;
};
export type SearchMode = "keyword" | "semantic" | "hybrid";
export type Source = {
  documentId: string;
  revisionId: string;
  chunkId: string;
  generationId: string;
  title: string;
  excerpt: string;
  section: string | null;
  pageStart: number | null;
  pageEnd: number | null;
  ordinal: number;
  configuration: string;
  model: string;
  dimensions: number;
  score: number;
};
export type SearchResult = {
  source: Source;
  keywordRank: number | null;
  semanticRank: number | null;
  score: number;
};
export type SearchResponse = {
  requestedMode: SearchMode;
  effectiveMode: SearchMode;
  degradationCode: string | null;
  returnedCount: number;
  results: SearchResult[];
};
export type Conversation = {
  id: string;
  organizationId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
};
export type ConversationPage = {
  items: Conversation[];
  totalElements: number;
  page: number;
  size: number;
};
export type Citation = {
  reference: string;
  documentId: string;
  revisionId: string;
  chunkId: string;
  generationId: string;
  title: string;
  section: string | null;
  pageStart: number | null;
  pageEnd: number | null;
  excerpt: string;
};
export type TurnStatus =
  | "GENERATING"
  | "ANSWERED"
  | "INSUFFICIENT_EVIDENCE"
  | "FAILED"
  | "REDACTED";
export type Message = {
  id: string;
  turnId: string;
  sequence: number;
  role: "USER" | "ASSISTANT";
  content: string | null;
  status: TurnStatus;
  errorCode: string | null;
  citations: Citation[];
  createdAt: string;
  completedAt: string | null;
};
export type History = {
  conversation: Conversation;
  messages: Message[];
  totalElements: number;
  page: number;
  size: number;
};
export type Turn = {
  conversationId: string;
  userMessageId: string;
  assistantMessageId: string;
  answer: string | null;
  citations: Citation[];
  status: TurnStatus;
  errorCode: string | null;
  createdAt: string;
  completedAt: string | null;
};
