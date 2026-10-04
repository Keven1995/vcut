import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PublicationMetadataPanel } from "./publication-metadata-panel";
import {
  fetchPublicationMetadata,
  generatePublicationMetadata,
  reviewPublicationMetadata,
  updatePublicationMetadata,
  type PublicationMetadata
} from "./publication-metadata-api";

vi.mock("./publication-metadata-api", () => ({
  fetchPublicationMetadata: vi.fn(),
  generatePublicationMetadata: vi.fn(),
  publicationMetadataQueryKey: (clipId: string) => `publication-metadata:${clipId}`,
  reviewPublicationMetadata: vi.fn(),
  updatePublicationMetadata: vi.fn()
}));

const baseDraft: PublicationMetadata = {
  id: "metadata-1",
  clipId: "clip-1",
  editVersion: 2,
  platform: "SHORTS",
  title: "Generated title",
  description: "Generated description",
  hashtags: ["#moment", "#shorts"],
  status: "DRAFT",
  updatedAt: "2026-10-03T12:00:00Z",
  reviewedAt: null
};

describe("PublicationMetadataPanel", () => {
  const records: PublicationMetadata[] = [];

  beforeEach(() => {
    records.splice(0, records.length);
    vi.clearAllMocks();
    vi.mocked(fetchPublicationMetadata).mockImplementation(async () => [...records]);
    vi.mocked(generatePublicationMetadata).mockImplementation(async (_clipId, platform) => {
      const draft = { ...baseDraft, platform };
      records.push(draft);
      return draft;
    });
    vi.mocked(updatePublicationMetadata).mockImplementation(async (_clipId, platform, update) => {
      const updated = { ...baseDraft, ...update, platform, status: "DRAFT" as const };
      records.splice(0, records.length, updated);
      return updated;
    });
    vi.mocked(reviewPublicationMetadata).mockImplementation(async (_clipId, platform) => {
      const reviewed = {
        ...(records[0] ?? baseDraft),
        platform,
        status: "REVIEWED" as const,
        reviewedAt: "2026-10-03T12:01:00Z"
      };
      records.splice(0, records.length, reviewed);
      return reviewed;
    });
  });

  afterEach(cleanup);

  it("generates an editable draft and requires an explicit review action", async () => {
    render(<PublicationMetadataPanel clipId="clip-1" />);
    expect(screen.getByText(/O Vcut nao publica automaticamente/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Gerar rascunho" }));

    await screen.findByLabelText("Titulo");
    const title = screen.getByLabelText("Titulo");
    fireEvent.change(title, { target: { value: "Titulo revisado" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar rascunho" }));

    await waitFor(() => expect(updatePublicationMetadata).toHaveBeenCalled());
    expect(reviewPublicationMetadata).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole("button", { name: "Marcar como revisado" }));
    await waitFor(() => expect(reviewPublicationMetadata).toHaveBeenCalledWith("clip-1", "SHORTS"));
    expect(await screen.findByText("Metadados revisados. Nada foi publicado.")).toBeTruthy();
  });
});
