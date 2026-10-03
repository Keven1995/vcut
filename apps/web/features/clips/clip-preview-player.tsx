"use client";

type ClipPreviewPlayerProps = {
  readonly src: string;
  readonly caption: string;
  readonly onTimeUpdate: (seconds: number) => void;
};

export function ClipPreviewPlayer({ src, caption, onTimeUpdate }: ClipPreviewPlayerProps) {
  return (
    <div className="clip-preview-player">
      <video
        controls
        key={src}
        src={src}
        aria-label="Preview do clip"
        onTimeUpdate={(event) => onTimeUpdate(event.currentTarget.currentTime)}
      />
      <span className="clip-preview-live-caption" aria-live="polite">
        {caption}
      </span>
    </div>
  );
}
