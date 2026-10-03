"use client";

import { useEffect, useRef, useState, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent, type PointerEvent as ReactPointerEvent } from "react";
import {
  formatTimelineTime,
  moveTimelineHandle,
  timelinePercent,
  type ClipInterval,
  type TimelineHandle
} from "./clip-editor-utils";

type ClipTimelineProps = {
  readonly startSeconds: number;
  readonly endSeconds: number;
  readonly durationSeconds: number;
  readonly selectedHandle: TimelineHandle;
  readonly onSelectHandle: (handle: TimelineHandle) => void;
  readonly onChange: (interval: ClipInterval) => void;
};

const TICKS = [0, 0.25, 0.5, 0.75, 1];

export function ClipTimeline({
  startSeconds,
  endSeconds,
  durationSeconds,
  selectedHandle,
  onSelectHandle,
  onChange
}: ClipTimelineProps) {
  const trackRef = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState<TimelineHandle | null>(null);
  const safeDuration = Math.max(durationSeconds, 0.1);
  useEffect(() => {
    if (!dragging) {
      return;
    }
    const activeHandle = dragging;

    function updateFromPointer(event: PointerEvent): void {
      const track = trackRef.current;
      if (!track) {
        return;
      }
      const bounds = track.getBoundingClientRect();
      const percentage = bounds.width <= 0 ? 0 : (event.clientX - bounds.left) / bounds.width;
      const value = Math.max(0, Math.min(safeDuration, percentage * safeDuration));
       onChange(
         moveTimelineHandle(
           activeHandle,
           value,
           { startSeconds, endSeconds },
           safeDuration
         )
       );
    }

    function stopDragging(): void {
      setDragging(null);
    }

    window.addEventListener("pointermove", updateFromPointer);
    window.addEventListener("pointerup", stopDragging);
    return () => {
      window.removeEventListener("pointermove", updateFromPointer);
      window.removeEventListener("pointerup", stopDragging);
    };
  }, [dragging, endSeconds, onChange, safeDuration, startSeconds]);

  function startDragging(handle: TimelineHandle, event: ReactPointerEvent<HTMLButtonElement>): void {
    event.preventDefault();
    onSelectHandle(handle);
    setDragging(handle);
  }

  function moveWithKeyboard(handle: TimelineHandle, event: ReactKeyboardEvent<HTMLButtonElement>): void {
    const step = event.shiftKey ? 1 : 0.1;
    let nextValue: number | null = null;
    if (event.key === "ArrowLeft" || event.key === "ArrowDown") {
      nextValue = (handle === "start" ? startSeconds : endSeconds) - step;
    } else if (event.key === "ArrowRight" || event.key === "ArrowUp") {
      nextValue = (handle === "start" ? startSeconds : endSeconds) + step;
    } else if (event.key === "Home") {
      nextValue = handle === "start" ? 0 : startSeconds + 0.1;
    } else if (event.key === "End") {
      nextValue = handle === "end" ? safeDuration : endSeconds - 0.1;
    }
    if (nextValue === null) {
      return;
    }
    event.preventDefault();
    onSelectHandle(handle);
    onChange(
      moveTimelineHandle(handle, nextValue, { startSeconds, endSeconds }, safeDuration)
    );
  }

  const startPercent = timelinePercent(startSeconds, safeDuration);
  const endPercent = timelinePercent(endSeconds, safeDuration);
  const selectedStyle: CSSProperties = {
    left: `${startPercent}%`,
    width: `${Math.max(endPercent - startPercent, 0)}%`
  };

  return (
    <div className="clip-timeline" aria-label="Timeline do clip">
      <div className="clip-timeline-labels">
        <span>{formatTimelineTime(startSeconds)}</span>
        <strong>{formatTimelineTime(endSeconds - startSeconds)} de duração</strong>
        <span>{formatTimelineTime(endSeconds)}</span>
      </div>
      <div className="clip-timeline-track" ref={trackRef}>
        <div className="clip-timeline-selection" style={selectedStyle} />
        {TICKS.map((tick) => (
          <span
            aria-hidden="true"
            className="clip-timeline-tick"
            key={tick}
            style={{ left: `${tick * 100}%` }}
          >
            {formatTimelineTime(tick * safeDuration)}
          </span>
        ))}
        <TimelineHandleButton
          handle="start"
           value={startSeconds}
           percent={startPercent}
           maximum={safeDuration}
           selected={selectedHandle === "start"}
          onPointerDown={startDragging}
          onKeyDown={moveWithKeyboard}
          onSelect={onSelectHandle}
        />
        <TimelineHandleButton
          handle="end"
           value={endSeconds}
           percent={endPercent}
           maximum={safeDuration}
           selected={selectedHandle === "end"}
          onPointerDown={startDragging}
          onKeyDown={moveWithKeyboard}
          onSelect={onSelectHandle}
        />
      </div>
      <p className="clip-timeline-help">Use as setas para ajustar o marcador selecionado. Shift move em passos de 1 segundo.</p>
    </div>
  );
}

type TimelineHandleButtonProps = {
  readonly handle: TimelineHandle;
  readonly value: number;
  readonly percent: number;
  readonly maximum: number;
  readonly selected: boolean;
  readonly onPointerDown: (handle: TimelineHandle, event: ReactPointerEvent<HTMLButtonElement>) => void;
  readonly onKeyDown: (handle: TimelineHandle, event: ReactKeyboardEvent<HTMLButtonElement>) => void;
  readonly onSelect: (handle: TimelineHandle) => void;
};

function TimelineHandleButton({
  handle,
  value,
  percent,
  maximum,
  selected,
  onPointerDown,
  onKeyDown,
  onSelect
}: TimelineHandleButtonProps) {
  const label = handle === "start" ? "Início do clip" : "Fim do clip";
  return (
    <button
      aria-label={label}
       aria-valuemax={maximum}
      aria-valuemin={0}
      aria-valuenow={value}
      className={`clip-timeline-handle clip-timeline-handle-${handle}${selected ? " selected" : ""}`}
      role="slider"
      style={{ left: `${percent}%` }}
      type="button"
      onClick={() => onSelect(handle)}
      onKeyDown={(event) => onKeyDown(handle, event)}
      onPointerDown={(event) => onPointerDown(handle, event)}
    >
      <span aria-hidden="true" />
    </button>
  );
}
