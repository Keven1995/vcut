import { apiRequest } from "../../lib/api-client";

export type Video = {
  readonly id: string;
  readonly originalFilename: string;
  readonly declaredSizeBytes: number;
  readonly actualSizeBytes: number | null;
  readonly status: "UPLOADING" | "UPLOADED" | "VALIDATING" | "READY" | "REJECTED" | "FAILED";
  readonly durationSeconds: number | null;
  readonly width: number | null;
  readonly height: number | null;
  readonly hasAudio: boolean | null;
  readonly uploadUrl?: string | null;
};

export type ImportVideoRequest = {
  readonly importId: string;
  readonly providerId: string;
  readonly sourceUrl: string;
  readonly externalAssetId: string;
  readonly consentPolicyVersion: string;
  readonly rightsConfirmed: boolean;
};

export type ImportedVideo = {
  readonly video: Video;
  readonly provenance: {
    readonly providerId: string;
    readonly sourceOrigin: string;
    readonly externalAssetId: string | null;
    readonly consentPolicyVersion: string;
    readonly consentAcceptedAt: string;
  };
};

export function videoQueryKey(videoId: string): string {
  return `video:${videoId}`;
}

export async function cancelVideoImport(importId: string): Promise<void> {
  await apiRequest<void>(`/api/video-imports/${importId}/cancel`, { method: "POST" });
}

export async function fetchVideo(videoId: string): Promise<Video> {
  const value: unknown = await apiRequest<unknown>(`/api/videos/${videoId}`);
  return parseVideo(value);
}

export async function importVideo(
  projectId: string,
  request: ImportVideoRequest
): Promise<ImportedVideo> {
  const value: unknown = await apiRequest<unknown>(`/api/projects/${projectId}/videos/import`, {
    method: "POST",
    body: JSON.stringify(request)
  });
  if (!isRecord(value) || !isRecord(value.provenance)) {
    throw new Error("A API retornou procedencia de importacao invalida.");
  }
  const provenance = value.provenance;
  if (
    typeof provenance.providerId !== "string" ||
    typeof provenance.sourceOrigin !== "string" ||
    !(typeof provenance.externalAssetId === "string" || provenance.externalAssetId === null) ||
    typeof provenance.consentPolicyVersion !== "string" ||
    typeof provenance.consentAcceptedAt !== "string"
  ) {
    throw new Error("A API retornou procedencia de importacao invalida.");
  }
  return {
    video: parseVideo(value.video),
    provenance: {
      providerId: provenance.providerId,
      sourceOrigin: provenance.sourceOrigin,
      externalAssetId: provenance.externalAssetId,
      consentPolicyVersion: provenance.consentPolicyVersion,
      consentAcceptedAt: provenance.consentAcceptedAt
    }
  };
}

function parseVideo(value: unknown): Video {
  if (!isRecord(value) || typeof value.id !== "string" || typeof value.originalFilename !== "string" || typeof value.declaredSizeBytes !== "number" || !isVideoStatus(value.status)) {
    throw new Error("A API retornou um vídeo inválido.");
  }
  return {
    id: value.id,
    originalFilename: value.originalFilename,
    declaredSizeBytes: value.declaredSizeBytes,
    actualSizeBytes: nullableNumber(value.actualSizeBytes),
    status: value.status,
    durationSeconds: nullableNumber(value.durationSeconds),
    width: nullableNumber(value.width),
    height: nullableNumber(value.height),
    hasAudio: typeof value.hasAudio === "boolean" ? value.hasAudio : null,
    uploadUrl: typeof value.uploadUrl === "string" ? value.uploadUrl : null
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function nullableNumber(value: unknown): number | null {
  return typeof value === "number" ? value : null;
}

function isVideoStatus(value: unknown): value is Video["status"] {
  return value === "UPLOADING" || value === "UPLOADED" || value === "VALIDATING" || value === "READY" || value === "REJECTED" || value === "FAILED";
}
