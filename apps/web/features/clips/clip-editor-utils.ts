import type { CaptionCue } from "./clip-types";

export const MIN_CLIP_DURATION_SECONDS = 0.1;

export type TimelineHandle = "start" | "end";

export type ClipInterval = {
  readonly startSeconds: number;
  readonly endSeconds: number;
};

export function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(Math.max(value, minimum), maximum);
}

export function moveTimelineHandle(
  handle: TimelineHandle,
  value: number,
  interval: ClipInterval,
  durationSeconds: number
): ClipInterval {
  const duration = Math.max(durationSeconds, MIN_CLIP_DURATION_SECONDS);
  if (handle === "start") {
    return {
      startSeconds: clamp(value, 0, interval.endSeconds - MIN_CLIP_DURATION_SECONDS),
      endSeconds: interval.endSeconds
    };
  }
  return {
    startSeconds: interval.startSeconds,
    endSeconds: clamp(
      value,
      interval.startSeconds + MIN_CLIP_DURATION_SECONDS,
      duration
    )
  };
}

export function timelinePercent(value: number, durationSeconds: number): number {
  if (durationSeconds <= 0 || !Number.isFinite(durationSeconds)) {
    return 0;
  }
  return clamp((value / durationSeconds) * 100, 0, 100);
}

export function captionAtTime(
  cues: readonly CaptionCue[],
  timeSeconds: number
): CaptionCue | null {
  return cues.find(
    (cue) => timeSeconds >= cue.startSeconds && timeSeconds <= cue.endSeconds
  ) ?? null;
}

export function formatTimelineTime(seconds: number): string {
  const safeSeconds = Math.max(0, seconds);
  const minutes = Math.floor(safeSeconds / 60);
  const remainder = Math.floor(safeSeconds % 60).toString().padStart(2, "0");
  return `${minutes}:${remainder}`;
}
