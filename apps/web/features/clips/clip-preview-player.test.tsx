import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ClipPreviewPlayer } from "./clip-preview-player";

afterEach(cleanup);

describe("ClipPreviewPlayer", () => {
  it("keeps the caption overlay synchronized with the media event", () => {
    const onTimeUpdate = vi.fn();
    render(<ClipPreviewPlayer caption="Legenda atual" src="/preview.mp4" onTimeUpdate={onTimeUpdate} />);
    expect(screen.getByText("Legenda atual")).toBeTruthy();
    const video = screen.getByLabelText("Preview do clip");
    Object.defineProperty(video, "currentTime", { configurable: true, value: 2.5 });
    fireEvent.timeUpdate(video);
    expect(onTimeUpdate).toHaveBeenCalledWith(2.5);
  });
});
