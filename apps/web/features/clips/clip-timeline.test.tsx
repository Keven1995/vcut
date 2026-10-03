import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ClipTimeline } from "./clip-timeline";

afterEach(cleanup);

function renderTimeline() {
  return render(
    <ClipTimeline
      durationSeconds={30}
      endSeconds={10}
      selectedHandle="start"
      startSeconds={4}
      onChange={vi.fn()}
      onSelectHandle={vi.fn()}
    />
  );
}

describe("ClipTimeline", () => {
  it.each([375, 1440])("renders keyboard controls at %spx", (width) => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: width });
    renderTimeline();
    expect(screen.getByRole("slider", { name: "Início do clip" })).toBeTruthy();
    expect(screen.getByRole("slider", { name: "Fim do clip" })).toBeTruthy();
  });

  it("moves the selected handle with the keyboard", () => {
    const onChange = vi.fn();
    render(
      <ClipTimeline
        durationSeconds={30}
        endSeconds={10}
        selectedHandle="start"
        startSeconds={4}
        onChange={onChange}
        onSelectHandle={vi.fn()}
      />
    );
    fireEvent.keyDown(screen.getByRole("slider", { name: "Início do clip" }), { key: "ArrowRight" });
    expect(onChange).toHaveBeenCalledWith({ startSeconds: 4.1, endSeconds: 10 });
  });
});
