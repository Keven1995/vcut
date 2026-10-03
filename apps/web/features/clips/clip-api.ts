import { apiRequest } from "../../lib/api-client";
import { parseClip, parseClipPage, parsePreviewUrl, type AspectRatio, type CaptionAnimation, type CaptionPreset, type CaptionPosition, type Clip, type ClipPage, type PreviewUrl } from "./clip-types";

export function clipsQueryKey(videoId: string): string {
  return `clips:${videoId}`;
}

export function clipQueryKey(clipId: string): string {
  return `clip:${clipId}`;
}

export function previewQueryKey(clipId: string): string {
  return `clip-preview:${clipId}`;
}

export async function fetchClips(videoId: string): Promise<ClipPage> {
  return parseClipPage(await apiRequest<unknown>(`/api/videos/${videoId}/clips`));
}

export async function fetchClip(clipId: string): Promise<Clip> {
  return parseClip(await apiRequest<unknown>(`/api/clips/${clipId}`));
}

export async function createClip(videoId: string, candidateId: string, aspectRatio: AspectRatio, captionPreset: CaptionPreset): Promise<Clip> {
  return parseClip(await apiRequest<unknown>(`/api/videos/${videoId}/clips`, {
    method: "POST",
    body: JSON.stringify({ candidateId, aspectRatio, captionPreset })
  }));
}

export type UpdateClipInput = {
  readonly startSeconds: number;
  readonly endSeconds: number;
  readonly aspectRatio: AspectRatio;
  readonly captionPreset: CaptionPreset;
  readonly captionText: string;
  readonly fontFamily: string;
  readonly fontSize: number;
  readonly fontWeight: number;
  readonly textColor: string;
  readonly backgroundColor: string;
  readonly backgroundOpacity: number;
  readonly position: CaptionPosition;
  readonly animation: CaptionAnimation;
};

export async function updateClip(clipId: string, input: UpdateClipInput): Promise<Clip> {
  return parseClip(await apiRequest<unknown>(`/api/clips/${clipId}`, {
    method: "PATCH",
    body: JSON.stringify(input)
  }));
}

export async function generateClip(clipId: string): Promise<Clip> {
  return parseClip(await apiRequest<unknown>(`/api/clips/${clipId}/generate`, { method: "POST" }));
}

export async function fetchPreviewUrl(clipId: string): Promise<PreviewUrl> {
  return parsePreviewUrl(await apiRequest<unknown>(`/api/clips/${clipId}/preview-url`));
}
