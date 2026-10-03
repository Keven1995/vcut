import { apiRequest } from "../../lib/api-client";

export type JobStatus = "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED" | "CANCELLED";

export type Job = {
  readonly id: string;
  readonly videoId: string;
  readonly status: JobStatus;
  readonly stage: string;
  readonly progress: number;
  readonly errorCode: string | null;
  readonly errorMessage: string | null;
};

export function jobQueryKey(jobId: string): string {
  return `job:${jobId}`;
}

export async function fetchJob(jobId: string): Promise<Job> {
  return parseJob(await apiRequest<unknown>(`/api/jobs/${jobId}`));
}

export function parseJob(value: unknown): Job {
  if (!isRecord(value) || typeof value.id !== "string" || typeof value.videoId !== "string" || typeof value.stage !== "string" || typeof value.progress !== "number" || !isJobStatus(value.status)) {
    throw new Error("A API retornou um job inválido.");
  }
  return {
    id: value.id,
    videoId: value.videoId,
    status: value.status,
    stage: value.stage,
    progress: value.progress,
    errorCode: nullableString(value.errorCode),
    errorMessage: nullableString(value.errorMessage)
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function isJobStatus(value: unknown): value is JobStatus {
  return value === "QUEUED" || value === "PROCESSING" || value === "COMPLETED" || value === "FAILED" || value === "CANCELLED";
}
