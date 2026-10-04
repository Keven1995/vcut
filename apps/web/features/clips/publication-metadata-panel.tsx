"use client";

import { useState } from "react";
import { invalidateServerQuery, useServerQuery } from "../../lib/server-state";
import {
  fetchPublicationMetadata,
  generatePublicationMetadata,
  publicationMetadataQueryKey,
  reviewPublicationMetadata,
  updatePublicationMetadata,
  type PublicationMetadata,
  type PublicationPlatform
} from "./publication-metadata-api";

const PLATFORMS: readonly { readonly id: PublicationPlatform; readonly label: string }[] = [
  { id: "SHORTS", label: "YouTube Shorts" },
  { id: "REELS", label: "Instagram Reels" },
  { id: "TIKTOK", label: "TikTok" }
];

type MetadataDraft = {
  readonly platform: PublicationPlatform;
  readonly title: string;
  readonly description: string;
  readonly hashtagsText: string;
};

export function PublicationMetadataPanel({ clipId }: { readonly clipId: string }) {
  const [platform, setPlatform] = useState<PublicationPlatform>("SHORTS");
  const [draft, setDraft] = useState<MetadataDraft | null>(null);
  const [isDirty, setIsDirty] = useState(false);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const query = useServerQuery<readonly PublicationMetadata[]>(
    publicationMetadataQueryKey(clipId),
    () => fetchPublicationMetadata(clipId),
    true
  );
  const metadata = query.data?.find((item) => item.platform === platform) ?? null;
  const currentDraft =
    draft?.platform === platform
      ? draft
      : metadata
        ? {
            platform,
            title: metadata.title,
            description: metadata.description,
            hashtagsText: metadata.hashtags.join(", ")
          }
        : { platform, title: "", description: "", hashtagsText: "" };

  async function generate(): Promise<void> {
    await perform("generate", () => generatePublicationMetadata(clipId, platform));
  }

  async function save(): Promise<void> {
    const hashtags = currentDraft.hashtagsText
      .split(",")
      .map((tag) => tag.trim())
      .filter(Boolean)
      .map((tag) => (tag.startsWith("#") ? tag : `#${tag}`));
    await perform("save", () =>
      updatePublicationMetadata(clipId, platform, {
        title: currentDraft.title,
        description: currentDraft.description,
        hashtags
      })
    );
  }

  async function review(): Promise<void> {
    await perform("review", () => reviewPublicationMetadata(clipId, platform));
  }

  async function perform(
    action: string,
    operation: () => Promise<PublicationMetadata>
  ): Promise<void> {
    setBusyAction(action);
    setMessage(null);
    try {
      const result = await operation();
      setDraft({
        platform,
        title: result.title,
        description: result.description,
        hashtagsText: result.hashtags.join(", ")
      });
      setIsDirty(false);
      setMessage(
        action === "review"
          ? "Metadados revisados. Nada foi publicado."
          : "Rascunho salvo para revisao."
      );
      invalidateServerQuery(publicationMetadataQueryKey(clipId));
    } catch (caught) {
      setMessage(caught instanceof Error ? caught.message : "Nao foi possivel salvar metadados.");
    } finally {
      setBusyAction(null);
    }
  }

  function editDraft(patch: Partial<MetadataDraft>): void {
    setDraft({ ...currentDraft, ...patch, platform });
    setIsDirty(true);
  }

  return (
    <section className="publication-metadata-panel" aria-labelledby="publication-metadata-title">
      <div className="publication-metadata-heading">
        <div>
          <span className="step-label">05 / Preparacao de publicacao</span>
          <h3 id="publication-metadata-title">Metadados para cada plataforma</h3>
        </div>
        {metadata ? <span className="project-status">{metadata.status}</span> : null}
      </div>
      <p>Gere um rascunho, revise e salve. O Vcut nao publica automaticamente.</p>
      <div className="publication-platform-tabs" role="tablist" aria-label="Plataforma de publicacao">
        {PLATFORMS.map((item) => (
          <button
            aria-selected={platform === item.id}
            className={platform === item.id ? "selected" : ""}
            key={item.id}
            role="tab"
            type="button"
            onClick={() => {
              setPlatform(item.id);
              setDraft(null);
              setIsDirty(false);
              setMessage(null);
            }}
          >
            {item.label}
          </button>
        ))}
      </div>
      {!metadata ? (
        <button className="secondary-action" type="button" disabled={busyAction !== null} onClick={() => void generate()}>
          {busyAction === "generate" ? "Gerando rascunho..." : "Gerar rascunho"}
        </button>
      ) : (
        <div className="publication-metadata-fields">
          <label className="clip-control-label">
            Titulo
            <input maxLength={100} value={currentDraft.title} onChange={(event) => editDraft({ title: event.target.value })} />
          </label>
          <label className="clip-control-label">
            Descricao
            <textarea maxLength={2200} rows={4} value={currentDraft.description} onChange={(event) => editDraft({ description: event.target.value })} />
          </label>
          <label className="clip-control-label">
            Hashtags separadas por virgula
            <input value={currentDraft.hashtagsText} onChange={(event) => editDraft({ hashtagsText: event.target.value })} />
          </label>
          <div className="publication-metadata-actions">
            <button className="secondary-action" type="button" disabled={busyAction !== null || !isDirty} onClick={() => void save()}>
              {busyAction === "save" ? "Salvando..." : "Salvar rascunho"}
            </button>
            <button className="quiet-action" type="button" disabled={busyAction !== null || isDirty || metadata.status === "REVIEWED"} onClick={() => void review()}>
              {busyAction === "review" ? "Registrando revisao..." : "Marcar como revisado"}
            </button>
          </div>
        </div>
      )}
      {query.error ? <p className="form-error" role="alert">{query.error.message}</p> : null}
      {message ? <p className="clip-editor-message" role="status">{message}</p> : null}
    </section>
  );
}
