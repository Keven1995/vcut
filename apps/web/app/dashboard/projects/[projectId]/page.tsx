"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { type ChangeEvent, type FormEvent, useEffect, useRef, useState } from "react";
import { apiRequest } from "../../../../lib/api-client";

const MAX_UPLOAD_BYTES = 512 * 1024 * 1024;

type UploadPhase = "idle" | "creating" | "uploading" | "confirming" | "ready" | "processing" | "failed" | "cancelled";

type Video = {
  id: string;
  originalFilename: string;
  declaredSizeBytes: number;
  actualSizeBytes: number | null;
  status: "UPLOADING" | "UPLOADED" | "VALIDATING" | "READY" | "REJECTED" | "FAILED";
  durationSeconds: number | null;
  width: number | null;
  height: number | null;
  hasAudio: boolean | null;
  uploadUrl?: string | null;
};

type JobStatus = "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED" | "CANCELLED";

type Job = {
  id: string;
  videoId: string;
  status: JobStatus;
  stage: string;
  progress: number;
  errorCode: string | null;
  errorMessage: string | null;
};

type UploadState = {
  phase: UploadPhase;
  progress: number;
  video: Video | null;
  error: string | null;
  job: Job | null;
};

class UploadCancelledError extends Error {}

type UploadHandle = {
  promise: Promise<void>;
  cancel: () => void;
};

function uploadDirectly(file: File, url: string, onProgress: (progress: number) => void): UploadHandle {
  const request = new XMLHttpRequest();
  const promise = new Promise<void>((resolve, reject) => {
    request.open("PUT", url);
    request.setRequestHeader("Content-Type", file.type || "video/mp4");
    request.upload.addEventListener("progress", (event) => {
      if (event.lengthComputable) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    });
    request.addEventListener("load", () => {
      if (request.status >= 200 && request.status < 300) {
        onProgress(100);
        resolve();
      } else {
        reject(new Error(`O storage recusou o upload (HTTP ${request.status}).`));
      }
    });
    request.addEventListener("error", () => reject(new Error("Nao foi possivel conectar ao storage.")));
    request.addEventListener("abort", () => reject(new UploadCancelledError()));
    request.send(file);
  });
  return { promise, cancel: () => request.abort() };
}

function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) {
    return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function phaseLabel(phase: UploadPhase): string {
  switch (phase) {
    case "creating":
      return "Preparando um destino seguro";
    case "uploading":
      return "Enviando direto para o storage";
    case "confirming":
      return "Confirmando o arquivo";
    case "ready":
      return "Arquivo pronto para processamento";
    case "processing":
      return "Processando o video em segundo plano";
    case "cancelled":
      return "Upload cancelado";
    default:
      return "Escolha um arquivo para comecar";
  }
}

export default function ProjectUploadPage() {
  const params = useParams<{ projectId: string }>();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const uploadRef = useRef<UploadHandle | null>(null);
  const videoIdRef = useRef<string | null>(null);
  const fileRef = useRef<File | null>(null);
  const cancelledRef = useRef(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [state, setState] = useState<UploadState>({ phase: "idle", progress: 0, video: null, error: null, job: null });
  const pollingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (pollingTimerRef.current) {
        clearTimeout(pollingTimerRef.current);
      }
    };
  }, []);

  function selectFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    const isMp4 = file.type === "video/mp4" || file.name.toLowerCase().endsWith(".mp4");
    if (!isMp4) {
      setState({ phase: "failed", progress: 0, video: null, error: "Escolha um arquivo MP4.", job: null });
      setSelectedFile(null);
      return;
    }
    if (file.size <= 0 || file.size > MAX_UPLOAD_BYTES) {
      setState({ phase: "failed", progress: 0, video: null, error: "O arquivo precisa ter entre 1 byte e 512 MB.", job: null });
      setSelectedFile(null);
      return;
    }
    setSelectedFile(file);
    fileRef.current = file;
    videoIdRef.current = null;
    setState({ phase: "idle", progress: 0, video: null, error: null, job: null });
  }

  async function deleteVideo(videoId: string): Promise<void> {
    try {
      await apiRequest<void>(`/api/videos/${videoId}`, { method: "DELETE" });
    } catch {
      // The next upload gets a new object key, so a cleanup failure must not block retry.
    }
  }

  async function startUpload(file: File): Promise<void> {
    cancelledRef.current = false;
    setState({ phase: "creating", progress: 0, video: null, error: null, job: null });
    try {
      const intent = await apiRequest<Video>(`/api/projects/${params.projectId}/videos`, {
        method: "POST",
        body: JSON.stringify({ filename: file.name, contentType: "video/mp4", sizeBytes: file.size })
      });
      if (!intent.uploadUrl) {
        throw new Error("A API nao retornou uma URL de upload.");
      }
      videoIdRef.current = intent.id;
      setState({ phase: "uploading", progress: 0, video: intent, error: null, job: null });
      const upload = uploadDirectly(file, intent.uploadUrl, (progress) => {
        setState((current) => ({ ...current, progress }));
      });
      uploadRef.current = upload;
      await upload.promise;
      uploadRef.current = null;
      setState((current) => ({ ...current, phase: "confirming", progress: 100 }));
      const confirmed = await apiRequest<Video>(`/api/videos/${intent.id}/confirm`, {
        method: "POST",
        body: JSON.stringify({})
      });
      setState({ phase: "ready", progress: 100, video: confirmed, error: null, job: null });
    } catch (caught) {
      uploadRef.current = null;
      if (caught instanceof UploadCancelledError || cancelledRef.current) {
        return;
      }
      const message = caught instanceof Error ? caught.message : "Nao foi possivel concluir o upload.";
      setState((current) => ({ ...current, phase: "failed", error: message }));
    }
  }

  async function pollJob(jobId: string, attempt = 0): Promise<void> {
    const job = parseJob(await apiRequest<unknown>(`/api/jobs/${jobId}`));
    setState((current) => ({
      ...current,
      phase: job.status === "COMPLETED" ? "ready" : job.status === "FAILED" || job.status === "CANCELLED" ? "failed" : "processing",
      progress: job.progress,
      job,
      error: job.errorMessage
    }));
    if (job.status === "COMPLETED" || job.status === "FAILED" || job.status === "CANCELLED") {
      return;
    }
    const delay = Math.min(8_000, 1_000 * 2 ** Math.min(attempt, 3));
    pollingTimerRef.current = setTimeout(() => {
      void pollJob(jobId, attempt + 1).catch((caught: unknown) => {
        const message = caught instanceof Error ? caught.message : "Nao foi possivel consultar o processamento.";
        setState((current) => ({ ...current, phase: "failed", error: message }));
      });
    }, delay);
  }

  async function processVideo(): Promise<void> {
    if (!state.video) {
      return;
    }
    try {
      const job = parseJob(await apiRequest<unknown>(`/api/videos/${state.video.id}/process`, { method: "POST" }));
      setState((current) => ({ ...current, phase: "processing", progress: job.progress, job, error: null }));
      await pollJob(job.id);
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : "Nao foi possivel iniciar o processamento.";
      setState((current) => ({ ...current, phase: "failed", error: message }));
    }
  }

  function submitUpload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (selectedFile) {
      void startUpload(selectedFile);
    }
  }

  function cancelUpload() {
    cancelledRef.current = true;
    uploadRef.current?.cancel();
    const videoId = videoIdRef.current;
    if (videoId) {
      void deleteVideo(videoId);
    }
    setState((current) => ({ ...current, phase: "cancelled", error: null, job: null }));
  }

  async function retryUpload() {
    if (!fileRef.current) {
      return;
    }
    if (videoIdRef.current) {
      await deleteVideo(videoIdRef.current);
    }
    videoIdRef.current = null;
    await startUpload(fileRef.current);
  }

  const isBusy = state.phase === "creating" || state.phase === "uploading" || state.phase === "confirming" || state.phase === "processing";
  const isUploadBusy = state.phase === "creating" || state.phase === "uploading" || state.phase === "confirming";

  return (
    <main className="dashboard-shell upload-shell">
      <nav className="nav dashboard-nav" aria-label="Navegacao do projeto">
        <Link className="brand" href="/dashboard">
          <span className="brand-mark">V</span>
          <span>vcut</span>
        </Link>
        <Link className="quiet-action" href="/dashboard">Voltar aos projetos</Link>
      </nav>
      <section className="upload-heading" aria-labelledby="upload-title">
        <p className="eyebrow">Ingestao inicial / {params.projectId.slice(0, 8)}</p>
        <h1 id="upload-title">Traga a fonte. O contexto vem depois.</h1>
        <p>Envie um MP4 direto para o storage. A API cria a intenção e confirma o arquivo, mas nunca transporta o seu video.</p>
      </section>
      <section className="upload-panel" aria-label="Upload de video">
        <form onSubmit={submitUpload}>
          <input ref={fileInputRef} type="file" accept="video/mp4,.mp4" onChange={selectFile} hidden />
          <div className="upload-panel-topline">
            <span className="step-label">01 / Fonte</span>
            <span className="upload-limit">MP4 / ate 512 MB</span>
          </div>
          <button className="drop-zone" type="button" onClick={() => fileInputRef.current?.click()} disabled={isBusy}>
            <span className="drop-icon" aria-hidden="true">+</span>
            <strong>{selectedFile ? selectedFile.name : "Selecione o video principal"}</strong>
            <span>{selectedFile ? formatBytes(selectedFile.size) : "Arraste sera suportado em uma proxima iteracao"}</span>
          </button>
          {selectedFile && state.phase === "idle" ? (
            <button className="primary-action upload-submit" type="submit">Iniciar upload <span aria-hidden="true">↗</span></button>
          ) : null}
        </form>
        <div className="upload-status" aria-live="polite">
          <div className="upload-status-line">
            <span>{phaseLabel(state.phase)}</span>
            {isBusy || state.phase === "ready" ? <strong>{state.progress}%</strong> : null}
          </div>
          {isBusy || state.phase === "ready" ? (
            <div className="progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={state.progress}>
              <span style={{ width: `${state.progress}%` }} />
            </div>
          ) : null}
          {isUploadBusy ? <button className="quiet-action upload-cancel" type="button" onClick={cancelUpload}>Cancelar</button> : null}
          {state.phase === "failed" ? (
            <div className="upload-failure">
              <p className="form-error" role="alert">{state.error}</p>
              <button className="quiet-action" type="button" onClick={() => void retryUpload()}>Tentar novamente</button>
            </div>
          ) : null}
          {state.phase === "cancelled" ? <p className="upload-note">Upload cancelado. O arquivo local continua selecionado.</p> : null}
          {state.phase === "ready" && state.video ? (
            <div className="video-ready">
              <span className="project-status">{state.job?.status === "COMPLETED" ? "Processado" : "Confirmado"}</span>
              <strong>{state.video.originalFilename}</strong>
              <span>{formatBytes(state.video.actualSizeBytes ?? state.video.declaredSizeBytes)} / {state.job?.status === "COMPLETED" ? "validacao concluida" : "aguardando processamento"}</span>
              {!state.job ? <button className="primary-action upload-submit" type="button" onClick={() => void processVideo()}>Processar video <span aria-hidden="true">↗</span></button> : null}
            </div>
          ) : null}
          {state.phase === "processing" && state.job ? (
            <div className="video-ready">
              <span className="project-status">{state.job.stage}</span>
              <strong>Processamento em andamento</strong>
              <span>{state.job.progress}% / consultando o job com backoff</span>
            </div>
          ) : null}
        </div>
      </section>
      <p className="upload-footnote">O arquivo fica associado somente a este projeto e usuario. URLs assinadas expiram automaticamente.</p>
    </main>
  );
}

function parseJob(value: unknown): Job {
  if (typeof value !== "object" || value === null) {
    throw new Error("A API retornou um job invalido.");
  }
  const record = value as Record<string, unknown>;
  const status = record.status;
  if (typeof record.id !== "string" || typeof record.videoId !== "string" || typeof record.stage !== "string" || typeof record.progress !== "number" || !isJobStatus(status)) {
    throw new Error("A API retornou um job invalido.");
  }
  return {
    id: record.id,
    videoId: record.videoId,
    status,
    stage: record.stage,
    progress: record.progress,
    errorCode: typeof record.errorCode === "string" ? record.errorCode : null,
    errorMessage: typeof record.errorMessage === "string" ? record.errorMessage : null
  };
}

function isJobStatus(value: unknown): value is JobStatus {
  return value === "QUEUED" || value === "PROCESSING" || value === "COMPLETED" || value === "FAILED" || value === "CANCELLED";
}
