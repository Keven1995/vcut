import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ClipEditor } from "./clip-editor";

afterEach(cleanup);

vi.mock("../../lib/server-state", () => ({
  invalidateServerQuery: vi.fn(),
  useServerQuery: (key: string) => ({
    data: key.startsWith("clips:") ? { content: [], totalElements: 0 } : null,
    error: null,
    isFetching: false,
    isLoading: false,
    refetch: vi.fn()
  })
}));

vi.mock("./clip-api", () => ({
  clipQueryKey: (id: string) => `clip:${id}`,
  clipsQueryKey: (id: string) => `clips:${id}`,
  createClip: vi.fn(),
  fetchClip: vi.fn(),
  fetchClips: vi.fn(),
  fetchRenderDownloadUrl: vi.fn(),
  fetchRenderThumbnailUrl: vi.fn(),
  fetchPreviewUrl: vi.fn(),
  fetchRenders: vi.fn(),
  generateClip: vi.fn(),
  previewQueryKey: (id: string) => `preview:${id}`,
  renderThumbnailQueryKey: (id: string) => `thumbnail:${id}`,
  rendersQueryKey: (id: string) => `renders:${id}`,
  requestFinalRender: vi.fn(),
  retryRender: vi.fn(),
  updateClip: vi.fn()
}));

describe("ClipEditor", () => {
  it("exposes the editor controls, timeline and manual crop before creation", () => {
    render(
      <ClipEditor
        videoId="video-1"
        candidate={{ id: "candidate-1", startSeconds: 2, endSeconds: 10, title: "Momento" }}
      />
    );
    expect(screen.getByRole("heading", { name: "Dê forma ao primeiro corte." })).toBeTruthy();
    expect(screen.getByRole("slider", { name: "Início do clip" })).toBeTruthy();
    expect(screen.getByText(/Enquadramento manual/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Abrir este clip no editor" })).toBeTruthy();
  });
});
