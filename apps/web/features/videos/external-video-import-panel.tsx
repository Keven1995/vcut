"use client";

import { type FormEvent, useState } from "react";
import { cancelVideoImport, importVideo, type ImportedVideo } from "./video-api";

type ExternalVideoImportPanelProps = {
  readonly projectId: string;
  readonly onImported: (result: ImportedVideo) => void;
};

export function ExternalVideoImportPanel({
  projectId,
  onImported
}: ExternalVideoImportPanelProps) {
  const [sourceUrl, setSourceUrl] = useState("fixture://sample-video");
  const [rightsConfirmed, setRightsConfirmed] = useState(false);
  const [isImporting, setIsImporting] = useState(false);
  const [activeImportId, setActiveImportId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [provenance, setProvenance] = useState<ImportedVideo["provenance"] | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (!rightsConfirmed || !sourceUrl.trim()) {
      return;
    }
    const importId = crypto.randomUUID();
    setIsImporting(true);
    setActiveImportId(importId);
    setError(null);
    try {
      const result = await importVideo(projectId, {
        importId,
        providerId: "fixture-local",
        sourceUrl: sourceUrl.trim(),
        externalAssetId: "sample-video",
        consentPolicyVersion: "test-consent-v1",
        rightsConfirmed
      });
      setProvenance(result.provenance);
      onImported(result);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Nao foi possivel importar a fonte.");
    } finally {
      setIsImporting(false);
      setActiveImportId(null);
    }
  }

  async function cancel(): Promise<void> {
    if (!activeImportId) {
      return;
    }
    try {
      await cancelVideoImport(activeImportId);
      setError("Cancelamento solicitado.");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Nao foi possivel cancelar a importacao.");
    }
  }

  return (
    <section className="upload-panel external-import-panel" aria-labelledby="external-import-title">
      <div className="upload-panel-topline">
        <span className="step-label">Fonte de teste</span>
        <span className="upload-limit">Fixture local / sem acesso externo</span>
      </div>
      <h2 id="external-import-title">Importar uma fonte autorizada</h2>
      <p>
        Este modo aceita somente o fixture local registrado. URLs de outras fontes sao recusadas.
      </p>
      <form onSubmit={(event) => void submit(event)}>
        <label className="form-field" htmlFor="external-video-source">
          <span>Identificador da fonte</span>
          <input
            id="external-video-source"
            value={sourceUrl}
            onChange={(event) => setSourceUrl(event.target.value)}
            autoComplete="off"
            required
          />
        </label>
        <label className="external-import-consent">
          <input
            type="checkbox"
            checked={rightsConfirmed}
            onChange={(event) => setRightsConfirmed(event.target.checked)}
          />
          <span>
            Confirmo que tenho autorizacao para importar e usar este conteudo neste projeto.
          </span>
        </label>
        <button className="primary-action upload-submit" type="submit" disabled={!rightsConfirmed || isImporting}>
          {isImporting ? "Importando fixture..." : "Importar fixture"}
        </button>
        {isImporting ? (
          <button className="quiet-action" type="button" onClick={() => void cancel()}>
            Cancelar importacao
          </button>
        ) : null}
      </form>
      {error ? <p className="form-error" role="alert">{error}</p> : null}
      {provenance ? (
        <dl className="external-import-provenance" aria-label="Procedencia da importacao">
          <div><dt>Fonte</dt><dd>{provenance.providerId}</dd></div>
          <div><dt>Origem</dt><dd>{provenance.sourceOrigin}</dd></div>
          <div><dt>Consentimento</dt><dd>{provenance.consentPolicyVersion}</dd></div>
          <div><dt>Registrado em</dt><dd>{new Date(provenance.consentAcceptedAt).toLocaleString()}</dd></div>
        </dl>
      ) : null}
    </section>
  );
}
