import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ExternalVideoImportPanel } from "./external-video-import-panel";
import { cancelVideoImport, importVideo, type ImportedVideo } from "./video-api";

vi.mock("./video-api", () => ({
  cancelVideoImport: vi.fn(),
  importVideo: vi.fn()
}));

const imported: ImportedVideo = {
  video: {
    id: "video-1",
    originalFilename: "sample-video.mp4",
    declaredSizeBytes: 30,
    actualSizeBytes: 30,
    status: "UPLOADED",
    durationSeconds: null,
    width: null,
    height: null,
    hasAudio: null
  },
  provenance: {
    providerId: "fixture-local",
    sourceOrigin: "fixture://sample-video",
    externalAssetId: "sample-video",
    consentPolicyVersion: "test-consent-v1",
    consentAcceptedAt: "2026-10-03T12:00:00Z"
  }
};

describe("ExternalVideoImportPanel", () => {
  afterEach(cleanup);
  beforeEach(() => vi.clearAllMocks());

  it("requires rights consent before importing and shows recorded provenance", async () => {
    const onImported = vi.fn();
    vi.mocked(importVideo).mockResolvedValue(imported);
    render(<ExternalVideoImportPanel projectId="project-1" onImported={onImported} />);

    const submit = screen.getByRole("button", { name: "Importar fixture" });
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("checkbox"));
    expect((submit as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(submit);

    await waitFor(() => expect(importVideo).toHaveBeenCalledWith("project-1", {
      importId: expect.any(String),
      providerId: "fixture-local",
      sourceUrl: "fixture://sample-video",
      externalAssetId: "sample-video",
      consentPolicyVersion: "test-consent-v1",
      rightsConfirmed: true
    }));
    expect(onImported).toHaveBeenCalledWith(imported);
    expect(screen.getByText("fixture://sample-video")).toBeTruthy();
    expect(screen.getByText("test-consent-v1")).toBeTruthy();
  });

  it("shows unsupported-source errors without importing", async () => {
    vi.mocked(importVideo).mockRejectedValue(new Error("Fonte nao suportada."));
    render(<ExternalVideoImportPanel projectId="project-1" onImported={vi.fn()} />);
    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "Importar fixture" }));

    expect((await screen.findByRole("alert")).textContent).toContain("Fonte nao suportada.");
  });

  it("allows the user to request cancellation during an active import", async () => {
    vi.mocked(importVideo).mockImplementation(() => new Promise(() => undefined));
    vi.mocked(cancelVideoImport).mockResolvedValue();
    render(<ExternalVideoImportPanel projectId="project-1" onImported={vi.fn()} />);
    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "Importar fixture" }));
    const cancel = await screen.findByRole("button", { name: "Cancelar importacao" });
    fireEvent.click(cancel);

    await waitFor(() => expect(cancelVideoImport).toHaveBeenCalledWith(expect.any(String)));
  });
});
