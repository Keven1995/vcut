import { describe, expect, it } from "vitest";
import { captionAtTime, moveTimelineHandle, timelinePercent } from "./clip-editor-utils";

describe("clip editor timeline rules", () => {
  it("keeps the selected interval inside the source duration", () => {
    expect(
      moveTimelineHandle("start", 20, { startSeconds: 4, endSeconds: 10 }, 30)
    ).toEqual({ startSeconds: 9.9, endSeconds: 10 });
    expect(
      moveTimelineHandle("end", 40, { startSeconds: 4, endSeconds: 10 }, 30)
    ).toEqual({ startSeconds: 4, endSeconds: 30 });
  });

  it("maps time to a bounded timeline percentage", () => {
    expect(timelinePercent(15, 30)).toBe(50);
    expect(timelinePercent(50, 30)).toBe(100);
    expect(timelinePercent(-1, 30)).toBe(0);
  });

  it("selects the caption active at the player time", () => {
    const cue = { id: "cue-1", text: "Olá", startSeconds: 1, endSeconds: 2 };
    expect(captionAtTime([cue], 1.5)).toEqual(cue);
    expect(captionAtTime([cue], 2.5)).toBeNull();
  });
});
