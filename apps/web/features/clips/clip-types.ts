export type AspectRatio = "9:16" | "16:9";
export type CaptionPreset = "MINIMAL" | "BOLD" | "KARAOKE" | "PODCAST" | "GAMING" | "TIKTOK";
export type CaptionPosition = "TOP" | "CENTER" | "BOTTOM";
export type CaptionAnimation = "NONE" | "FADE" | "POP" | "KARAOKE";
export type ClipStatus = "QUEUED" | "PROCESSING" | "READY" | "FAILED";

export type CaptionStyle = {
  readonly fontFamily: string;
  readonly fontSize: number;
  readonly fontWeight: number;
  readonly textColor: string;
  readonly backgroundColor: string;
  readonly backgroundOpacity: number;
  readonly position: CaptionPosition;
  readonly animation: CaptionAnimation;
};

export type CropSettings = {
  readonly x: number;
  readonly y: number;
  readonly zoom: number;
};

export type CaptionCue = {
  readonly id: string;
  readonly text: string;
  readonly startSeconds: number;
  readonly endSeconds: number;
};

export type Clip = {
  readonly id: string;
  readonly videoId: string;
  readonly candidateId: string;
  readonly status: ClipStatus;
  readonly progress: number;
  readonly editVersion: number;
  readonly startSeconds: number;
  readonly endSeconds: number;
  readonly aspectRatio: AspectRatio;
  readonly crop: CropSettings;
  readonly captionPreset: CaptionPreset;
  readonly captionStyle: CaptionStyle;
  readonly captionCues: readonly CaptionCue[];
  readonly createdAt: string;
  readonly updatedAt: string;
};

export type ClipPage = {
  readonly content: readonly Clip[];
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
};

export type PreviewUrl = {
  readonly url: string;
  readonly expiresAt: string;
};

export type ClipCandidateForEditor = {
  readonly id: string;
  readonly startSeconds: number;
  readonly endSeconds: number;
  readonly title: string;
};

export function parseClipPage(value: unknown): ClipPage {
  if (!isRecord(value) || !Array.isArray(value.content)) {
    throw new Error("A API retornou uma página de clips inválida.");
  }
  return {
    content: value.content.map(parseClip),
    page: requiredNumber(value.page, "page"),
    size: requiredNumber(value.size, "size"),
    totalElements: requiredNumber(value.totalElements, "totalElements"),
    totalPages: requiredNumber(value.totalPages, "totalPages")
  };
}

export function parseClip(value: unknown): Clip {
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    typeof value.videoId !== "string" ||
    typeof value.candidateId !== "string" ||
    !isClipStatus(value.status) ||
    typeof value.progress !== "number" ||
    value.progress < 0 ||
    value.progress > 100 ||
    typeof value.editVersion !== "number" ||
    typeof value.startSeconds !== "number" ||
    typeof value.endSeconds !== "number" ||
    !isAspectRatio(value.aspectRatio) ||
    !isCaptionPreset(value.captionPreset) ||
    !isRecord(value.captionStyle) ||
    !Array.isArray(value.captionCues) ||
    typeof value.createdAt !== "string" ||
    typeof value.updatedAt !== "string"
  ) {
    throw new Error("A API retornou um clip inválido.");
  }
  return {
    id: value.id,
    videoId: value.videoId,
    candidateId: value.candidateId,
    status: value.status,
    progress: value.progress,
    editVersion: value.editVersion,
    startSeconds: value.startSeconds,
    endSeconds: value.endSeconds,
    aspectRatio: value.aspectRatio,
    crop: parseCropSettings(value.crop),
    captionPreset: value.captionPreset,
    captionStyle: parseCaptionStyle(value.captionStyle),
    captionCues: value.captionCues.map(parseCaptionCue),
    createdAt: value.createdAt,
    updatedAt: value.updatedAt
  };
}

export function parsePreviewUrl(value: unknown): PreviewUrl {
  if (!isRecord(value) || typeof value.url !== "string" || typeof value.expiresAt !== "string") {
    throw new Error("A API retornou uma URL de preview inválida.");
  }
  return { url: value.url, expiresAt: value.expiresAt };
}

function parseCaptionStyle(value: Record<string, unknown>): CaptionStyle {
  if (
    typeof value.fontFamily !== "string" ||
    typeof value.fontSize !== "number" ||
    typeof value.fontWeight !== "number" ||
    typeof value.textColor !== "string" ||
    typeof value.backgroundColor !== "string" ||
    typeof value.backgroundOpacity !== "number" ||
    !isCaptionPosition(value.position) ||
    !isCaptionAnimation(value.animation)
  ) {
    throw new Error("A API retornou um estilo de legenda inválido.");
  }
  return {
    fontFamily: value.fontFamily,
    fontSize: value.fontSize,
    fontWeight: value.fontWeight,
    textColor: value.textColor,
    backgroundColor: value.backgroundColor,
    backgroundOpacity: value.backgroundOpacity,
    position: value.position,
    animation: value.animation
  };
}

function parseCropSettings(value: unknown): CropSettings {
  if (
    !isRecord(value) ||
    typeof value.x !== "number" ||
    typeof value.y !== "number" ||
    typeof value.zoom !== "number" ||
    value.x < 0 ||
    value.x > 1 ||
    value.y < 0 ||
    value.y > 1 ||
    value.zoom < 1 ||
    value.zoom > 3
  ) {
    throw new Error("A API retornou configurações de crop inválidas.");
  }
  return { x: value.x, y: value.y, zoom: value.zoom };
}

function parseCaptionCue(value: unknown): CaptionCue {
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    typeof value.text !== "string" ||
    typeof value.startSeconds !== "number" ||
    typeof value.endSeconds !== "number"
  ) {
    throw new Error("A API retornou uma legenda inválida.");
  }
  return {
    id: value.id,
    text: value.text,
    startSeconds: value.startSeconds,
    endSeconds: value.endSeconds
  };
}

function requiredNumber(value: unknown, name: string): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new Error(`A API retornou ${name} inválido.`);
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isAspectRatio(value: unknown): value is AspectRatio {
  return value === "9:16" || value === "16:9";
}

function isCaptionPreset(value: unknown): value is CaptionPreset {
  return value === "MINIMAL" || value === "BOLD" || value === "KARAOKE" || value === "PODCAST" || value === "GAMING" || value === "TIKTOK";
}

function isCaptionPosition(value: unknown): value is CaptionPosition {
  return value === "TOP" || value === "CENTER" || value === "BOTTOM";
}

function isCaptionAnimation(value: unknown): value is CaptionAnimation {
  return value === "NONE" || value === "FADE" || value === "POP" || value === "KARAOKE";
}

function isClipStatus(value: unknown): value is ClipStatus {
  return value === "QUEUED" || value === "PROCESSING" || value === "READY" || value === "FAILED";
}
