import type { JsonRecord } from "./api";

export interface Chapter {
  id: string;
  chapterNumber: number;
  title: string;
  summary: string;
}

export type ResourceLicenseScope = "PUBLIC" | "TEAM_ONLY" | "CLASSROOM_ONLY";

export interface Resource {
  id: string;
  chapterId: string;
  type: string;
  title: string;
  description: string;
  sourceName: string;
  versionLabel: string;
  reviewStatus: "PUBLISHED";
  licenseScope: ResourceLicenseScope;
  contentUrl: string | null;
}

export type KnowledgeResultKind = "answer" | "textbook";

export interface KnowledgeSearchResult {
  id: string;
  chapterId: string | null;
  title: string;
  lessonNumber: string | null;
  kind: KnowledgeResultKind;
  source: string;
  pageLabel: string | null;
  sourceLabel: string;
  locationLabel: string;
  /** The contract currently emits the localized review label. */
  reviewStatus: string;
  publicationStatus: "PUBLISHED";
  excerpt: string;
  score: number;
}

export interface KnowledgeSearchResponse {
  ok: true;
  query: string;
  results: KnowledgeSearchResult[];
}

export type ResourceMetadata = JsonRecord;
