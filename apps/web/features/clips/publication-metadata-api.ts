import { apiRequest } from "../../lib/api-client";

export type PublicationPlatform = "SHORTS" | "REELS" | "TIKTOK";
export type PublicationStatus = "DRAFT" | "REVIEWED";

export type PublicationMetadata = {
  readonly id: string;
  readonly clipId: string;
  readonly editVersion: number;
  readonly platform: PublicationPlatform;
  readonly title: string;
  readonly description: string;
  readonly hashtags: readonly string[];
  readonly status: PublicationStatus;
  readonly updatedAt: string;
  readonly reviewedAt: string | null;
};

export function publicationMetadataQueryKey(clipId: string): string {
  return `publication-metadata:${clipId}`;
}

export async function fetchPublicationMetadata(
  clipId: string
): Promise<readonly PublicationMetadata[]> {
  const value: unknown = await apiRequest<unknown>(
    `/api/clips/${clipId}/publication-metadata`
  );
  if (!Array.isArray(value)) {
    throw new Error("A API retornou metadados de publicacao invalidos.");
  }
  return value.map(parsePublicationMetadata);
}

export async function generatePublicationMetadata(
  clipId: string,
  platform: PublicationPlatform
): Promise<PublicationMetadata> {
  const value: unknown = await apiRequest<unknown>(
    `/api/clips/${clipId}/publication-metadata/${platform}/generate`,
    { method: "POST" }
  );
  return parsePublicationMetadata(value);
}

export async function updatePublicationMetadata(
  clipId: string,
  platform: PublicationPlatform,
  draft: { readonly title: string; readonly description: string; readonly hashtags: readonly string[] }
): Promise<PublicationMetadata> {
  const value: unknown = await apiRequest<unknown>(
    `/api/clips/${clipId}/publication-metadata/${platform}`,
    { method: "PUT", body: JSON.stringify(draft) }
  );
  return parsePublicationMetadata(value);
}

export async function reviewPublicationMetadata(
  clipId: string,
  platform: PublicationPlatform
): Promise<PublicationMetadata> {
  const value: unknown = await apiRequest<unknown>(
    `/api/clips/${clipId}/publication-metadata/${platform}/review`,
    { method: "POST" }
  );
  return parsePublicationMetadata(value);
}

function parsePublicationMetadata(value: unknown): PublicationMetadata {
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    typeof value.clipId !== "string" ||
    typeof value.editVersion !== "number" ||
    !isPlatform(value.platform) ||
    typeof value.title !== "string" ||
    typeof value.description !== "string" ||
    !isStringArray(value.hashtags) ||
    !isStatus(value.status) ||
    typeof value.updatedAt !== "string" ||
    !(typeof value.reviewedAt === "string" || value.reviewedAt === null)
  ) {
    throw new Error("A API retornou metadados de publicacao invalidos.");
  }
  return {
    id: value.id,
    clipId: value.clipId,
    editVersion: value.editVersion,
    platform: value.platform,
    title: value.title,
    description: value.description,
    hashtags: value.hashtags,
    status: value.status,
    updatedAt: value.updatedAt,
    reviewedAt: value.reviewedAt
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isPlatform(value: unknown): value is PublicationPlatform {
  return value === "SHORTS" || value === "REELS" || value === "TIKTOK";
}

function isStatus(value: unknown): value is PublicationStatus {
  return value === "DRAFT" || value === "REVIEWED";
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item: unknown) => typeof item === "string");
}
