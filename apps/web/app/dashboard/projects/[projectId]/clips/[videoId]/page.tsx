"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { type FormEvent, useCallback, useEffect, useState } from "react";
import { ApiClientError, apiRequest } from "../../../../../../lib/api-client";
import { ClipEditor } from "../../../../../../features/clips/clip-editor";

type RunStatus = "QUEUED" | "PROCESSING" | "RETRYING" | "COMPLETED" | "FAILED";
type DurationPreference = "AUTO" | "SHORT" | "MEDIUM" | "LONG" | "CUSTOM";
type CandidateVariant = "SHORT" | "COMPLETE" | "CONTEXTUAL";
type CandidateStatus = "SUGGESTED" | "ACCEPTED" | "DISCARDED" | "SELECTED";

type ClipAnalysisRun = {
  id: string;
  videoId: string;
  pipelineVersion: number;
  durationPreference: DurationPreference;
  customDurationSeconds: number | null;
  durationSeconds: number | null;
  language: string;
  status: RunStatus;
  errorCode: string | null;
  errorMessage: string | null;
};

type ClipCandidate = {
  id: string;
  analysisRunId: string;
  videoId: string;
  variant: CandidateVariant;
  status: CandidateStatus;
  startSeconds: number;
  endSeconds: number;
  group: string;
  title: string;
  description: string;
  justification: string;
  createdAt: string;
  updatedAt: string;
};

const activeStatuses: RunStatus[] = ["QUEUED", "PROCESSING", "RETRYING"];

export default function ClipCandidatesPage() {
  const params = useParams<{ projectId: string; videoId: string }>();
  const [preference, setPreference] = useState<DurationPreference>("AUTO");
  const [customDuration, setCustomDuration] = useState("30");
  const [run, setRun] = useState<ClipAnalysisRun | null>(null);
  const [candidates, setCandidates] = useState<ClipCandidate[]>([]);
  const [pending, setPending] = useState(true);
  const [actionPending, setActionPending] = useState<string | null>(null);
  const [selectedCandidate, setSelectedCandidate] = useState<ClipCandidate | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadAnalysis = useCallback(async (): Promise<void> => {
    try {
      const loadedRun = parseRun(
        await apiRequest<unknown>(`/api/videos/${params.videoId}/clip-analysis/run`)
      );
      const loadedCandidates = parseCandidates(
        await apiRequest<unknown>(`/api/videos/${params.videoId}/clip-analysis/candidates`)
      );
      setRun(loadedRun);
      setCandidates(loadedCandidates);
      setError(null);
    } catch (caught) {
      if (caught instanceof ApiClientError && caught.status === 404) {
        setRun(null);
        setCandidates([]);
        setError(null);
      } else {
        setError(caught instanceof Error ? caught.message : "Nao foi possivel consultar os candidatos.");
      }
    } finally {
      setPending(false);
    }
  }, [params.videoId]);

  useEffect(() => {
    const timer = window.setTimeout(() => void loadAnalysis(), 0);
    return () => window.clearTimeout(timer);
  }, [loadAnalysis]);

  useEffect(() => {
    if (!run || !activeStatuses.includes(run.status)) {
      return;
    }
    const timer = window.setTimeout(() => void loadAnalysis(), 1_500);
    return () => window.clearTimeout(timer);
  }, [loadAnalysis, run]);

  async function requestAnalysis(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (preference === "CUSTOM" && (!Number.isFinite(Number(customDuration)) || Number(customDuration) <= 0 || Number(customDuration) > 90)) {
      setError("A duracao personalizada deve estar entre 0 e 90 segundos.");
      return;
    }
    setPending(true);
    try {
      const body: { durationPreference: DurationPreference; customDurationSeconds?: number } = {
        durationPreference: preference
      };
      if (preference === "CUSTOM") {
        body.customDurationSeconds = Number(customDuration);
      }
      const createdRun = parseRun(
        await apiRequest<unknown>(`/api/videos/${params.videoId}/clip-analysis`, {
          method: "POST",
          body: JSON.stringify(body)
        })
      );
      setRun(createdRun);
      setCandidates([]);
      setError(null);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Nao foi possivel iniciar a analise.");
    } finally {
      setPending(false);
    }
  }

  async function updateCandidate(candidate: ClipCandidate, action: "accept" | "discard" | "select"): Promise<void> {
    setActionPending(`${candidate.id}:${action}`);
    try {
      await apiRequest<null>(`/api/clip-candidates/${candidate.id}/${action}`, { method: "POST" });
      const nextStatus: CandidateStatus = action === "accept" ? "ACCEPTED" : action === "discard" ? "DISCARDED" : "SELECTED";
      setCandidates((current) => current.map((item) => item.id === candidate.id ? { ...item, status: nextStatus } : item));
      if (action === "select") {
        setSelectedCandidate(candidate);
      }
      setError(null);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Nao foi possivel atualizar o candidato.");
    } finally {
      setActionPending(null);
    }
  }

  return (
    <main className="dashboard-shell clips-shell">
      <nav className="nav dashboard-nav" aria-label="Navegacao de candidatos">
        <Link className="brand" href={`/dashboard/projects/${params.projectId}`}>
          <span className="brand-mark">V</span>
          <span>vcut</span>
        </Link>
        <Link className="quiet-action" href={`/dashboard/projects/${params.projectId}/transcription/${params.videoId}`}>
          Voltar a transcricao
        </Link>
      </nav>

      <section className="clips-heading" aria-labelledby="clips-title">
        <p className="eyebrow">Cortes / {params.videoId.slice(0, 8)}</p>
        <h1 id="clips-title">Tres maneiras de contar o mesmo momento.</h1>
        <p>Compare trechos curtos, completos e contextuais. A escolha fica com voce; a analise apenas organiza o primeiro corte.</p>
      </section>

      {error ? <p className="form-error" role="alert">{error}</p> : null}

      <section className="clips-panel" aria-label="Gerar candidatos de corte">
        <form className="clips-form" onSubmit={(event) => void requestAnalysis(event)}>
          <div>
            <span className="step-label">02 / Ideias de corte</span>
            <strong>Como deve ser a primeira versao?</strong>
          </div>
          <label>
            Duracao orientativa
            <select value={preference} onChange={(event) => setPreference(event.target.value as DurationPreference)}>
              <option value="AUTO">Automatica</option>
              <option value="SHORT">Curta · 15s</option>
              <option value="MEDIUM">Media · 30s</option>
              <option value="LONG">Longa · 60s</option>
              <option value="CUSTOM">Personalizada</option>
            </select>
          </label>
          {preference === "CUSTOM" ? (
            <label>
              Segundos
              <input type="number" min="1" max="90" step="1" value={customDuration} onChange={(event) => setCustomDuration(event.target.value)} />
            </label>
          ) : null}
          <button className="primary-action" type="submit" disabled={pending}>
            {run && activeStatuses.includes(run.status) ? "Analisando..." : "Gerar candidatos"}
          </button>
        </form>
        {run ? (
          <div className="clips-run-status" aria-live="polite">
            <span className="project-status">{run.status}</span>
            <p>{run.errorMessage ?? statusMessage(run.status, candidates.length)}</p>
          </div>
        ) : (
          <p className="clips-run-status">Ainda nao ha uma analise para este video.</p>
        )}
      </section>

      <section className="candidate-section" aria-labelledby="candidate-title">
        <div className="candidate-section-heading">
          <div>
            <p className="eyebrow">Resultado</p>
            <h2 id="candidate-title">Candidatos que merecem um play.</h2>
          </div>
          <span className="candidate-count">{candidates.length.toString().padStart(2, "0")} sugestoes</span>
        </div>
        {pending && candidates.length === 0 ? <p className="empty-state">Consultando o ultimo processamento...</p> : null}
        {!pending && candidates.length === 0 ? <p className="empty-state">Quando a analise terminar, os trechos aparecem aqui.</p> : null}
        {candidates.length > 0 ? (
          <div className="candidate-grid">
            {candidates.map((candidate) => (
              <article className={`candidate-card candidate-${candidate.status.toLowerCase()}`} key={candidate.id}>
                <div className="candidate-card-topline">
                  <span>{candidate.variant}</span>
                  <span>{formatTime(candidate.startSeconds)} - {formatTime(candidate.endSeconds)}</span>
                </div>
                <h3>{candidate.title}</h3>
                <p>{candidate.description}</p>
                <p className="candidate-justification">{candidate.justification}</p>
                <div className="candidate-card-footer">
                  <span className="project-status">{candidate.status}</span>
                  <div className="candidate-actions">
                    <button type="button" onClick={() => void updateCandidate(candidate, "discard")} disabled={actionPending !== null}>
                      Descartar
                    </button>
                    <button type="button" onClick={() => void updateCandidate(candidate, "accept")} disabled={actionPending !== null}>
                      Aceitar
                    </button>
                    <button className="candidate-select" type="button" onClick={() => void updateCandidate(candidate, "select")} disabled={actionPending !== null}>
                      Selecionar
                    </button>
                  </div>
                </div>
              </article>
            ))}
          </div>
        ) : null}
      </section>

      {selectedCandidate ? (
        <ClipEditor videoId={params.videoId} candidate={selectedCandidate} />
      ) : null}
    </main>
  );
}

function statusMessage(status: RunStatus, count: number): string {
  if (status === "COMPLETED" && count === 0) {
    return "A analise terminou sem encontrar um trecho confiavel.";
  }
  if (status === "COMPLETED") {
    return "Analise concluida. Compare as opcoes e escolha um ponto de partida.";
  }
  if (status === "FAILED") {
    return "A analise falhou. Tente novamente com outra preferencia.";
  }
  return "A transcricao esta sendo organizada em possibilidades de corte.";
}

function formatTime(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const remainder = Math.floor(seconds % 60).toString().padStart(2, "0");
  return `${minutes}:${remainder}`;
}

function parseRun(value: unknown): ClipAnalysisRun {
  if (!isRecord(value) || typeof value.id !== "string" || typeof value.videoId !== "string" || typeof value.pipelineVersion !== "number" || typeof value.durationPreference !== "string" || typeof value.language !== "string" || !isRunStatus(value.status)) {
    throw new Error("A API retornou um processamento de corte invalido.");
  }
  if (!isDurationPreference(value.durationPreference)) {
    throw new Error("A API retornou uma preferencia de duracao invalida.");
  }
  return {
    id: value.id,
    videoId: value.videoId,
    pipelineVersion: value.pipelineVersion,
    durationPreference: value.durationPreference,
    customDurationSeconds: nullableNumber(value.customDurationSeconds),
    durationSeconds: nullableNumber(value.durationSeconds),
    language: value.language,
    status: value.status,
    errorCode: nullableString(value.errorCode),
    errorMessage: nullableString(value.errorMessage)
  };
}

function parseCandidates(value: unknown): ClipCandidate[] {
  if (!Array.isArray(value)) {
    throw new Error("A API retornou uma lista de candidatos invalida.");
  }
  return value.map(parseCandidate);
}

function parseCandidate(value: unknown): ClipCandidate {
  if (!isRecord(value) || typeof value.id !== "string" || typeof value.analysisRunId !== "string" || typeof value.videoId !== "string" || !isCandidateVariant(value.variant) || !isCandidateStatus(value.status) || typeof value.startSeconds !== "number" || typeof value.endSeconds !== "number" || value.endSeconds <= value.startSeconds || typeof value.group !== "string" || typeof value.title !== "string" || typeof value.description !== "string" || typeof value.justification !== "string" || typeof value.createdAt !== "string" || typeof value.updatedAt !== "string") {
    throw new Error("A API retornou um candidato invalido.");
  }
  return {
    id: value.id,
    analysisRunId: value.analysisRunId,
    videoId: value.videoId,
    variant: value.variant,
    status: value.status,
    startSeconds: value.startSeconds,
    endSeconds: value.endSeconds,
    group: value.group,
    title: value.title,
    description: value.description,
    justification: value.justification,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function nullableNumber(value: unknown): number | null {
  return typeof value === "number" ? value : null;
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function isRunStatus(value: unknown): value is RunStatus {
  return value === "QUEUED" || value === "PROCESSING" || value === "RETRYING" || value === "COMPLETED" || value === "FAILED";
}

function isDurationPreference(value: string): value is DurationPreference {
  return value === "AUTO" || value === "SHORT" || value === "MEDIUM" || value === "LONG" || value === "CUSTOM";
}

function isCandidateVariant(value: unknown): value is CandidateVariant {
  return value === "SHORT" || value === "COMPLETE" || value === "CONTEXTUAL";
}

function isCandidateStatus(value: unknown): value is CandidateStatus {
  return value === "SUGGESTED" || value === "ACCEPTED" || value === "DISCARDED" || value === "SELECTED";
}
