"use client";

import { useEffect, useState } from "react";
import { invalidateServerQuery, useServerQuery } from "../../lib/server-state";
import {
  clipQueryKey,
  clipsQueryKey,
  createClip,
  fetchClip,
  fetchClips,
  fetchPreviewUrl,
  generateClip,
  previewQueryKey,
  updateClip,
  type UpdateClipInput
} from "./clip-api";
import {
  type AspectRatio,
  type CaptionAnimation,
  type CaptionPreset,
  type CaptionPosition,
  type Clip,
  type ClipCandidateForEditor,
  type PreviewUrl
} from "./clip-types";
import {
  captionAtTime,
  formatTimelineTime,
  type TimelineHandle
} from "./clip-editor-utils";
import { ClipTimeline } from "./clip-timeline";
import { ClipPreviewPlayer } from "./clip-preview-player";

type ClipEditorProps = {
  readonly videoId: string;
  readonly candidate: ClipCandidateForEditor;
};

type EditorDraft = UpdateClipInput;

const defaultPreset: CaptionPreset = "MINIMAL";
const defaultRatio: AspectRatio = "9:16";

export function ClipEditor({ videoId, candidate }: ClipEditorProps) {
  const [clipId, setClipId] = useState<string | null>(null);
  const [draft, setDraft] = useState<EditorDraft>(() => initialDraft(candidate));
  const [action, setAction] = useState<"create" | "save" | "generate" | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [selectedHandle, setSelectedHandle] = useState<TimelineHandle>("start");
  const [playbackSeconds, setPlaybackSeconds] = useState(0);

  const clipQuery = useServerQuery<Clip>(
    clipQueryKey(clipId ?? "new"),
    () => fetchClip(clipId ?? ""),
    clipId !== null
  );
  const clipsQuery = useServerQuery(
    clipsQueryKey(videoId),
    () => fetchClips(videoId),
    true
  );
  const previewQuery = useServerQuery<PreviewUrl>(
    previewQueryKey(clipId ?? "new"),
    () => fetchPreviewUrl(clipId ?? ""),
    clipId !== null && clipQuery.data?.status === "READY"
  );

  useEffect(() => {
    const status = clipQuery.data?.status;
    if (!clipId || (status !== "QUEUED" && status !== "PROCESSING")) {
      return;
    }
    const timer = window.setTimeout(() => invalidateServerQuery(clipQueryKey(clipId)), 1_500);
    return () => window.clearTimeout(timer);
  }, [clipId, clipQuery.data?.status]);

  useEffect(() => {
    if (clipId || !clipsQuery.data) {
      return;
    }
    const existing = clipsQuery.data.content.find((clip) => clip.candidateId === candidate.id);
    if (existing) {
      const timer = window.setTimeout(() => {
        setClipId(existing.id);
        setDraft(draftFromClip(existing));
        setMessage("Versão editável restaurada. Você pode continuar de onde parou.");
      }, 0);
      return () => window.clearTimeout(timer);
    }
  }, [candidate.id, clipId, clipsQuery.data]);

  async function createEditableClip(): Promise<void> {
    setAction("create");
    try {
      const clip = await createClip(videoId, candidate.id, draft.aspectRatio, draft.captionPreset);
      setClipId(clip.id);
      setDraft(draftFromClip(clip));
      setMessage("Clip criado. Ajuste o intervalo e a legenda antes de gerar o preview.");
      invalidateServerQuery(clipsQueryKey(videoId));
    } catch (caught: unknown) {
      setMessage(caught instanceof Error ? caught.message : "Não foi possível criar o clip.");
    } finally {
      setAction(null);
    }
  }

  async function saveDraft(): Promise<void> {
    if (!clipId) {
      return;
    }
    setAction("save");
    try {
       const currentClip = clipQuery.data;
       if (!currentClip) {
         setMessage("A versão atual ainda não foi carregada. Tente novamente.");
         return;
       }
       const savedClip = await updateClip(clipId, draft, currentClip.editVersion);
       setDraft(draftFromClip(savedClip));
       invalidateServerQuery(clipQueryKey(clipId));
      invalidateServerQuery(clipsQueryKey(videoId));
      invalidateServerQuery(previewQueryKey(clipId));
       setMessage(`Alteração salva como a versão ${savedClip.editVersion}. Preview anterior invalidado.`);
    } catch (caught: unknown) {
      setMessage(caught instanceof Error ? caught.message : "Não foi possível salvar o clip.");
    } finally {
      setAction(null);
    }
  }

  async function requestGeneration(): Promise<void> {
    if (!clipId) {
      return;
    }
    setAction("generate");
    try {
      await generateClip(clipId);
      invalidateServerQuery(clipQueryKey(clipId));
      invalidateServerQuery(clipsQueryKey(videoId));
      setMessage("Geração enfileirada. O status será atualizado automaticamente.");
    } catch (caught: unknown) {
      setMessage(caught instanceof Error ? caught.message : "Não foi possível gerar o preview.");
    } finally {
      setAction(null);
    }
  }

  function updateDraft<K extends keyof EditorDraft>(key: K, value: EditorDraft[K]): void {
    setDraft((current) => ({ ...current, [key]: value }));
  }

  const maxSeconds = Math.max(90, Math.ceil(Math.max(candidate.endSeconds, draft.endSeconds) + 1));
  const currentStatus = clipQuery.data?.status ?? "DRAFT";
  const isWorking = action !== null;
  const activeCaption = captionAtTime(clipQuery.data?.captionCues ?? [], playbackSeconds);

  return (
    <section className="clip-editor" aria-labelledby="clip-editor-title">
      <div className="clip-editor-heading">
        <div>
          <p className="eyebrow">Editor / {candidate.title}</p>
          <h2 id="clip-editor-title">Dê forma ao primeiro corte.</h2>
           <p>Use a timeline e o enquadramento manual para preparar este clip. Smart crop automatizado entra depois sem alterar esta versão.</p>
        </div>
        <div className="clip-editor-status">
           <span className="project-status">{currentStatus}</span>
           {clipQuery.data ? <span>Versão {clipQuery.data.editVersion}</span> : null}
           <span>{clipsQuery.data?.totalElements ?? 0} clips neste vídeo</span>
        </div>
      </div>

      <div className="clip-editor-layout">
        <div className="clip-preview-column">
          <div className={`clip-preview clip-preview-${draft.aspectRatio === "9:16" ? "portrait" : "landscape"}`}>
            {previewQuery.data && currentStatus === "READY" ? (
              <ClipPreviewPlayer
                caption={activeCaption?.text ?? draft.captionText}
                src={previewQuery.data.url}
                onTimeUpdate={setPlaybackSeconds}
              />
            ) : (
              <div className="clip-preview-art">
                <span className="clip-preview-grid" aria-hidden="true" />
                <span
                  className={`clip-preview-caption clip-preview-caption-${draft.position.toLowerCase()}`}
                  style={{
                    color: draft.textColor,
                    backgroundColor: draft.backgroundColor,
                    opacity: draft.backgroundOpacity,
                    fontFamily: draft.fontFamily,
                    fontSize: `${Math.min(draft.fontSize, 42)}px`,
                    fontWeight: draft.fontWeight
                  }}
                >
                  {draft.captionText || "Sua legenda aparece aqui"}
                </span>
              </div>
            )}
            <span className="clip-preview-ratio">{draft.aspectRatio}</span>
          </div>
          <ClipTimeline
            durationSeconds={maxSeconds}
            endSeconds={draft.endSeconds}
            selectedHandle={selectedHandle}
            startSeconds={draft.startSeconds}
            onChange={(interval) => {
              updateDraft("startSeconds", interval.startSeconds);
              updateDraft("endSeconds", interval.endSeconds);
            }}
            onSelectHandle={setSelectedHandle}
           />
          {clipQuery.data?.captionCues.length ? (
            <div className="clip-transcript" aria-label="Transcrição visível do clip">
              <span className="step-label">03 / Transcrição visível</span>
              <div className="clip-transcript-cues">
                {clipQuery.data.captionCues.map((cue) => (
                  <span className={cue.id === activeCaption?.id ? "active" : ""} key={cue.id}>
                    <small>{formatTimelineTime(cue.startSeconds)}</small>
                    {cue.text}
                  </span>
                ))}
              </div>
            </div>
          ) : null}
          {currentStatus !== "READY" && clipId ? (
            <div className="clip-preview-progress" role="status" aria-live="polite">
              <span>Preview {currentStatus === "FAILED" ? "falhou" : "em processamento"}</span>
              <strong>{clipQuery.data?.progress ?? 0}%</strong>
              <div className="clip-progress-bar" aria-hidden="true">
                <span style={{ width: `${clipQuery.data?.progress ?? 0}%` }} />
              </div>
            </div>
          ) : null}
        </div>

        <div className="clip-controls">
          <div className="clip-control-group">
            <span className="step-label">01 / Proporção</span>
            <div className="ratio-actions">
              {(["9:16", "16:9"] as const).map((ratio) => (
                <button
                  className={draft.aspectRatio === ratio ? "selected" : ""}
                  key={ratio}
                  type="button"
                  onClick={() => updateDraft("aspectRatio", ratio)}
                >
                  {ratio}
                </button>
              ))}
            </div>
          </div>

          <label className="clip-control-label">
            Preset de legenda
            <select value={draft.captionPreset} onChange={(event) => updateDraft("captionPreset", event.target.value as CaptionPreset)}>
              <option value="MINIMAL">Minimal</option>
              <option value="BOLD">Bold</option>
              <option value="KARAOKE">Karaoke</option>
              <option value="PODCAST">Podcast</option>
              <option value="GAMING">Gaming</option>
              <option value="TIKTOK">TikTok</option>
            </select>
          </label>

          <label className="clip-control-label">
            Texto da legenda
            <textarea value={draft.captionText} onChange={(event) => updateDraft("captionText", event.target.value)} rows={3} />
          </label>

          <div className="clip-style-grid">
            <label className="clip-control-label">
              Fonte
              <input value={draft.fontFamily} onChange={(event) => updateDraft("fontFamily", event.target.value)} />
            </label>
            <label className="clip-control-label">
              Tamanho
              <input type="number" min="8" max="144" step="1" value={draft.fontSize} onChange={(event) => updateDraft("fontSize", Number(event.target.value))} />
            </label>
            <label className="clip-control-label">
              Peso
              <select value={draft.fontWeight} onChange={(event) => updateDraft("fontWeight", Number(event.target.value))}>
                <option value="400">Regular</option>
                <option value="600">Semibold</option>
                <option value="700">Bold</option>
                <option value="800">Black</option>
              </select>
            </label>
            <label className="clip-control-label">
              Posição
              <select value={draft.position} onChange={(event) => updateDraft("position", event.target.value as CaptionPosition)}>
                <option value="TOP">Topo</option>
                <option value="CENTER">Centro</option>
                <option value="BOTTOM">Base</option>
              </select>
            </label>
            <label className="clip-control-label color-control">
              Texto
              <input type="color" value={draft.textColor} onChange={(event) => updateDraft("textColor", event.target.value)} />
            </label>
            <label className="clip-control-label color-control">
              Fundo
              <input type="color" value={draft.backgroundColor} onChange={(event) => updateDraft("backgroundColor", event.target.value)} />
            </label>
            <label className="clip-control-label">
              Opacidade do fundo
              <input type="range" min="0" max="1" step="0.05" value={draft.backgroundOpacity} onChange={(event) => updateDraft("backgroundOpacity", Number(event.target.value))} />
            </label>
          </div>

          <div className="clip-control-group clip-crop-controls">
            <span className="step-label">02 / Enquadramento manual</span>
            <p className="clip-control-hint">A posição usa coordenadas normalizadas: esquerda/topo são 0 e direita/base são 1.</p>
            <label className="clip-control-label">
              Posição horizontal
              <input type="range" min="0" max="1" step="0.01" value={draft.cropX} onChange={(event) => updateDraft("cropX", Number(event.target.value))} />
              <output>{draft.cropX.toFixed(2)}</output>
            </label>
            <label className="clip-control-label">
              Posição vertical
              <input type="range" min="0" max="1" step="0.01" value={draft.cropY} onChange={(event) => updateDraft("cropY", Number(event.target.value))} />
              <output>{draft.cropY.toFixed(2)}</output>
            </label>
            <label className="clip-control-label">
              Zoom
              <input type="range" min="1" max="3" step="0.05" value={draft.cropZoom} onChange={(event) => updateDraft("cropZoom", Number(event.target.value))} />
              <output>{draft.cropZoom.toFixed(2)}x</output>
            </label>
          </div>

          <label className="clip-control-label">
            Animação
            <select value={draft.animation} onChange={(event) => updateDraft("animation", event.target.value as CaptionAnimation)}>
              <option value="NONE">Nenhuma</option>
              <option value="FADE">Fade</option>
              <option value="POP">Pop</option>
              <option value="KARAOKE">Karaoke</option>
            </select>
          </label>

          {clipId ? (
            <div className="clip-editor-actions">
              <button className="secondary-action" type="button" onClick={() => void saveDraft()} disabled={isWorking}>
                {action === "save" ? "Salvando..." : "Salvar versão"}
              </button>
              <button className="primary-action" type="button" onClick={() => void requestGeneration()} disabled={isWorking}>
                {action === "generate" ? "Enfileirando..." : "Gerar preview"}
              </button>
            </div>
          ) : (
            <button className="primary-action" type="button" onClick={() => void createEditableClip()} disabled={isWorking}>
              {action === "create" ? "Criando..." : "Abrir este clip no editor"}
            </button>
          )}
          {message ? <p className="clip-editor-message" aria-live="polite">{message}</p> : null}
          {clipQuery.error ? <p className="form-error" role="alert">{clipQuery.error.message}</p> : null}
          {clipQuery.data?.status === "FAILED" ? <p className="form-error" role="alert">A geração falhou. Salve os ajustes e tente gerar novamente.</p> : null}
        </div>
      </div>
    </section>
  );
}

function initialDraft(candidate: ClipCandidateForEditor): EditorDraft {
  return {
    startSeconds: candidate.startSeconds,
    endSeconds: candidate.endSeconds,
    aspectRatio: defaultRatio,
    cropX: 0.5,
    cropY: 0.5,
    cropZoom: 1,
    captionPreset: defaultPreset,
    captionText: candidate.title,
    fontFamily: "Inter",
    fontSize: 32,
    fontWeight: 400,
    textColor: "#FFFFFF",
    backgroundColor: "#000000",
    backgroundOpacity: 0.35,
    position: "BOTTOM",
    animation: "NONE"
  };
}

function draftFromClip(clip: Clip): EditorDraft {
  return {
    startSeconds: clip.startSeconds,
    endSeconds: clip.endSeconds,
    aspectRatio: clip.aspectRatio,
    cropX: clip.crop.x,
    cropY: clip.crop.y,
    cropZoom: clip.crop.zoom,
    captionPreset: clip.captionPreset,
    captionText: clip.captionCues.map((cue) => cue.text).join(" "),
    fontFamily: clip.captionStyle.fontFamily,
    fontSize: clip.captionStyle.fontSize,
    fontWeight: clip.captionStyle.fontWeight,
    textColor: clip.captionStyle.textColor,
    backgroundColor: clip.captionStyle.backgroundColor,
    backgroundOpacity: clip.captionStyle.backgroundOpacity,
    position: clip.captionStyle.position,
    animation: clip.captionStyle.animation
  };
}
