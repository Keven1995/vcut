"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiClientError, apiRequest } from "../../../../../../lib/api-client";

type Word = {
  text: string;
  start: number;
  end: number;
  confidence: number | null;
};

type Segment = {
  text: string;
  start: number;
  end: number;
  confidence: number | null;
  words: Word[];
};

type Transcription = {
  id: string;
  videoId: string;
  pipelineVersion: number;
  provider: string;
  language: string;
  text: string;
  durationSeconds: number;
  confidence: number | null;
  status: "QUEUED" | "PROCESSING" | "RETRYING" | "COMPLETED" | "FAILED";
  segments: Segment[];
  errorCode: string | null;
  errorMessage: string | null;
};

type AudioUrl = { url: string; expiresAt: string };

export default function TranscriptionPage() {
  const params = useParams<{ projectId: string; videoId: string }>();
  const audioRef = useRef<HTMLAudioElement>(null);
  const [transcription, setTranscription] = useState<Transcription | null>(null);
  const [audioUrl, setAudioUrl] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [selectedTime, setSelectedTime] = useState<number | null>(null);
  const [pending, setPending] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const transcriptionStatus = transcription?.status;

  const loadTranscription = useCallback(async (): Promise<void> => {
    try {
      const response = await apiRequest<unknown>(`/api/videos/${params.videoId}/transcription`);
      const parsed = parseTranscription(response);
      setTranscription(parsed);
      setError(null);
      if (parsed.status === "COMPLETED") {
        const audio = await apiRequest<AudioUrl>(`/api/videos/${params.videoId}/transcription/audio-url`);
        setAudioUrl(audio.url);
      }
    } catch (caught) {
      if (caught instanceof ApiClientError && caught.status === 404) {
        setTranscription(null);
        setError(null);
      } else {
        setError(caught instanceof Error ? caught.message : "Nao foi possivel consultar a transcricao.");
      }
    } finally {
      setPending(false);
    }
  }, [params.videoId]);

  useEffect(() => {
    const timer = window.setTimeout(() => void loadTranscription(), 0);
    return () => window.clearTimeout(timer);
  }, [loadTranscription]);

  useEffect(() => {
    if (!transcriptionStatus || transcriptionStatus === "COMPLETED") {
      return;
    }
    const timer = window.setTimeout(() => void loadTranscription(), 1_500);
    return () => window.clearTimeout(timer);
  }, [loadTranscription, transcriptionStatus]);

  async function requestTranscription(): Promise<void> {
    setPending(true);
    try {
      const response = await apiRequest<unknown>(`/api/videos/${params.videoId}/transcription`, {
        method: "POST",
        body: JSON.stringify({})
      });
      setTranscription(parseTranscription(response));
      setError(null);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Nao foi possivel solicitar a transcricao.");
    } finally {
      setPending(false);
    }
  }

  function selectWord(word: Word): void {
    setSelectedTime(word.start);
    if (audioRef.current) {
      audioRef.current.currentTime = word.start;
      void audioRef.current.play().catch(() => undefined);
    }
  }

  const normalizedSearch = search.trim().toLocaleLowerCase();
  const visibleSegments = transcription?.segments.filter((segment) =>
    !normalizedSearch || segment.text.toLocaleLowerCase().includes(normalizedSearch)
  ) ?? [];
  const hasCompletedTranscript = transcription?.status === "COMPLETED";

  return (
    <main className="dashboard-shell transcription-shell">
      <nav className="nav dashboard-nav" aria-label="Navegacao da transcricao">
        <Link className="brand" href={`/dashboard/projects/${params.projectId}`}>
          <span className="brand-mark">V</span>
          <span>vcut</span>
        </Link>
        <Link className="quiet-action" href={`/dashboard/projects/${params.projectId}`}>Voltar ao projeto</Link>
      </nav>
      <section className="transcription-heading" aria-labelledby="transcription-title">
        <p className="eyebrow">Transcricao / {params.videoId.slice(0, 8)}</p>
        <h1 id="transcription-title">Encontre a frase. Marque o momento.</h1>
        <p>Busque no texto e selecione uma palavra para mover o player ao instante exato.</p>
      </section>
      {error ? <p className="form-error" role="alert">{error}</p> : null}
      <section className="transcription-panel" aria-label="Painel de transcricao">
        <div className="transcription-toolbar">
          <div>
            <span className="step-label">01 / Voz</span>
            <strong>{transcription ? `${transcription.language} / ${transcription.provider}` : "Nenhuma versao"}</strong>
          </div>
          {!transcription || transcription.status === "FAILED" ? (
            <button className="primary-action" type="button" onClick={() => void requestTranscription()} disabled={pending}>
              {transcription ? "Tentar novamente" : "Gerar transcricao"}
            </button>
          ) : null}
        </div>
        {transcription && transcription.status !== "COMPLETED" ? (
          <div className="transcription-state" aria-live="polite">
            <span className="project-status">{transcription.status}</span>
            <p>{transcription.errorMessage ?? "A transcricao esta sendo preparada em segundo plano."}</p>
          </div>
        ) : null}
        {hasCompletedTranscript ? (
          <>
            <div className="transcription-player">
              {audioUrl ? <audio ref={audioRef} controls src={audioUrl} /> : <span>Player aguardando o audio intermediario.</span>}
              {selectedTime !== null ? <strong>{formatTime(selectedTime)}</strong> : null}
            </div>
            <label className="transcription-search" htmlFor="transcription-search">
              Buscar no texto
              <input id="transcription-search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Ex.: momento importante" />
            </label>
            <div className="transcription-segments" aria-live="polite">
              {visibleSegments.map((segment) => (
                <article className="transcription-segment" key={`${segment.start}-${segment.end}`}>
                  <div className="segment-meta"><span>{formatTime(segment.start)} - {formatTime(segment.end)}</span><span>{segment.confidence === null ? "sem confianca" : `${Math.round(segment.confidence * 100)}%`}</span></div>
                  <p>{segment.text}</p>
                  <div className="word-list">
                    {segment.words.map((word) => (
                      <button className={selectedTime === word.start ? "word-button selected" : "word-button"} key={`${word.start}-${word.end}-${word.text}`} type="button" onClick={() => selectWord(word)}>
                        {word.text}
                      </button>
                    ))}
                  </div>
                </article>
              ))}
              {visibleSegments.length === 0 ? <p className="empty-state">Nenhum segmento corresponde a busca.</p> : null}
            </div>
          </>
        ) : null}
      </section>
    </main>
  );
}

function formatTime(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const remainder = Math.floor(seconds % 60).toString().padStart(2, "0");
  return `${minutes}:${remainder}`;
}

function parseTranscription(value: unknown): Transcription {
  if (typeof value !== "object" || value === null) {
    throw new Error("A API retornou uma transcricao invalida.");
  }
  const record = value as Record<string, unknown>;
  const status = record.status;
  if (typeof record.id !== "string" || typeof record.videoId !== "string" || typeof record.language !== "string" || typeof record.provider !== "string" || typeof record.text !== "string" || typeof record.durationSeconds !== "number" || !isTranscriptionStatus(status) || !Array.isArray(record.segments)) {
    throw new Error("A API retornou uma transcricao invalida.");
  }
  return {
    id: record.id,
    videoId: record.videoId,
    pipelineVersion: typeof record.pipelineVersion === "number" ? record.pipelineVersion : 1,
    provider: record.provider,
    language: record.language,
    text: record.text,
    durationSeconds: record.durationSeconds,
    confidence: typeof record.confidence === "number" ? record.confidence : null,
    status,
    segments: record.segments.map(parseSegment),
    errorCode: typeof record.errorCode === "string" ? record.errorCode : null,
    errorMessage: typeof record.errorMessage === "string" ? record.errorMessage : null
  };
}

function parseSegment(value: unknown): Segment {
  if (typeof value !== "object" || value === null) {
    throw new Error("A API retornou um segmento invalido.");
  }
  const record = value as Record<string, unknown>;
  if (typeof record.text !== "string" || typeof record.start !== "number" || typeof record.end !== "number" || !Array.isArray(record.words)) {
    throw new Error("A API retornou um segmento invalido.");
  }
  return {
    text: record.text,
    start: record.start,
    end: record.end,
    confidence: typeof record.confidence === "number" ? record.confidence : null,
    words: record.words.map(parseWord)
  };
}

function parseWord(value: unknown): Word {
  if (typeof value !== "object" || value === null) {
    throw new Error("A API retornou uma palavra invalida.");
  }
  const record = value as Record<string, unknown>;
  if (typeof record.text !== "string" || typeof record.start !== "number" || typeof record.end !== "number") {
    throw new Error("A API retornou uma palavra invalida.");
  }
  return {
    text: record.text,
    start: record.start,
    end: record.end,
    confidence: typeof record.confidence === "number" ? record.confidence : null
  };
}

function isTranscriptionStatus(value: unknown): value is Transcription["status"] {
  return value === "QUEUED" || value === "PROCESSING" || value === "RETRYING" || value === "COMPLETED" || value === "FAILED";
}
