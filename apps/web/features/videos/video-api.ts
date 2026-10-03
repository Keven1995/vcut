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

export function videoQueryKey(videoId: string): string {
  return `video:${videoId}`;
}

export async function fetchVideo(videoId: string): Promise<Video> {
  const value: unknown = await apiRequest<unknown>(`/api/videos/${videoId}`);
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
