"use client";

import { useServerQuery } from "../../lib/server-state";
import { fetchUsageSummary, usageSummaryQueryKey } from "./usage-api";

export function UsageSummaryPanel() {
  const query = useServerQuery(usageSummaryQueryKey, fetchUsageSummary);
  const summary = query.data;
  if (query.isLoading) {
    return <section className="usage-panel" aria-label="Uso da conta">Carregando uso...</section>;
  }
  if (!summary) {
    return (
      <section className="usage-panel" aria-label="Uso da conta">
        <h2>Uso da conta</h2>
        <p className="form-error" role="alert">{query.error?.message ?? "Uso indisponivel."}</p>
      </section>
    );
  }
  const processingPercent = percentage(summary.processedMinutes + summary.reservedMinutes, summary.processingLimitMinutes);
  const storagePercent = percentage(summary.retainedBytes, summary.storageLimitBytes);

  return (
    <section className="usage-panel" aria-labelledby="usage-summary-title">
      <div className="usage-panel-heading">
        <div>
          <p className="eyebrow">Periodo / {new Date(summary.periodStart).toLocaleDateString("pt-BR")}</p>
          <h2 id="usage-summary-title">Consumo e limites</h2>
        </div>
        <span className="project-status">Plano {summary.planCode}</span>
      </div>
      <div className="usage-metrics-grid">
        <article>
          <span>Minutos processados</span>
          <strong>{summary.processedMinutes} / {summary.processingLimitMinutes}</strong>
          <small>{summary.reservedMinutes} min reservados · {summary.remainingMinutes} min livres</small>
          <div className="usage-meter" role="progressbar" aria-label="Uso de minutos" aria-valuemin={0} aria-valuemax={100} aria-valuenow={processingPercent}>
            <span style={{ width: `${processingPercent}%` }} />
          </div>
        </article>
        <article>
          <span>Armazenamento</span>
          <strong>{formatBytes(summary.retainedBytes)} / {formatBytes(summary.storageLimitBytes)}</strong>
          <small>{summary.renders} renders · {summary.activeJobs} jobs ativos de {summary.concurrentJobLimit}</small>
          <div className="usage-meter" role="progressbar" aria-label="Uso de armazenamento" aria-valuemin={0} aria-valuemax={100} aria-valuenow={storagePercent}>
            <span style={{ width: `${storagePercent}%` }} />
          </div>
        </article>
        <article className="usage-cost-card">
          <span>Custo estimado</span>
          <strong>{summary.estimatedCost.toFixed(4)}</strong>
          <small>Unidade de custo definida na configuracao</small>
        </article>
      </div>
      <div className="usage-ledger" aria-labelledby="usage-ledger-title">
        <h3 id="usage-ledger-title">Atividade recente</h3>
        {summary.recentLedger.length === 0 ? <p>Nenhum consumo registrado neste periodo.</p> : null}
        {summary.recentLedger.map((entry, index) => (
          <div className="usage-ledger-row" key={`${entry.operation}-${entry.createdAt}-${index}`}>
            <span>{entry.operation}</span>
            <span>{entry.processedMinutes} min · {entry.renders} renders</span>
            <span>{entry.outcome}</span>
            <time dateTime={entry.createdAt}>{new Date(entry.createdAt).toLocaleString("pt-BR")}</time>
          </div>
        ))}
      </div>
    </section>
  );
}

function percentage(value: number, limit: number): number {
  if (limit <= 0) {
    return 0;
  }
  return Math.min(100, Math.max(0, Math.round((value / limit) * 100)));
}

function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 1024)} KB`;
  }
  if (bytes < 1024 * 1024 * 1024) {
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}
