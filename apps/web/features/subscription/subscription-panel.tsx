"use client";

import { useState } from "react";
import { invalidateServerQuery, useServerQuery } from "../../lib/server-state";
import {
  cancelSubscription,
  confirmSandboxCheckout,
  createSubscriptionCheckout,
  fetchSubscriptionOverview,
  subscriptionOverviewQueryKey,
  type BillingPayment,
  type CheckoutSession,
  type PlanOffer,
  type SubscriptionOverview,
  type SubscriptionStatus
} from "./subscription-api";

export function SubscriptionPanel() {
  const query = useServerQuery<SubscriptionOverview>(
    subscriptionOverviewQueryKey,
    fetchSubscriptionOverview
  );
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const overview = query.data;
  const currentPlanCode =
    overview?.currentSubscription?.status === "ACTIVE"
      ? overview.currentSubscription.planCode
      : "FREE";

  async function runAction(key: string, action: () => Promise<SubscriptionOverview>) {
    setBusyAction(key);
    setMutationError(null);
    try {
      await action();
      invalidateServerQuery(subscriptionOverviewQueryKey);
    } catch (caught: unknown) {
      setMutationError(
        caught instanceof Error ? caught.message : "Não foi possível atualizar a assinatura."
      );
    } finally {
      setBusyAction(null);
    }
  }

  if (query.isLoading) {
    return <section className="subscription-panel" aria-label="Assinatura">Carregando planos e cobrança...</section>;
  }
  if (!overview) {
    return (
      <section className="subscription-panel" aria-label="Assinatura">
        <p className="form-error" role="alert">
          {query.error?.message ?? "Não foi possível carregar planos e cobrança."}
        </p>
        <button className="quiet-action" type="button" onClick={() => void query.refetch()}>
          Tentar novamente
        </button>
      </section>
    );
  }

  return (
    <section className="subscription-panel" aria-labelledby="subscription-title">
      <div className="subscription-heading">
        <div>
          <p className="eyebrow">Planos / cobrança</p>
          <h2 id="subscription-title">Assinatura</h2>
        </div>
        <span className="project-status">
          {overview.currentSubscription?.status
            ? subscriptionStatusLabel(overview.currentSubscription.status)
            : `Plano ${currentPlanCode}`}
        </span>
      </div>

      {overview.sandboxEnabled ? (
        <p className="subscription-sandbox-note">
          Modo sandbox: pagamentos são simulados e não há cobrança real.
        </p>
      ) : (
        <p className="subscription-sandbox-note">
          Checkout indisponível: o adapter de pagamento está desativado.
        </p>
      )}

      {mutationError ? <p className="form-error" role="alert">{mutationError}</p> : null}

      {overview.currentSubscription ? (
        <div className="subscription-current" aria-label="Plano atual">
          <div>
            <strong>{overview.currentSubscription.planCode}</strong>
            <span>
              Período até {new Date(overview.currentSubscription.periodEnd).toLocaleDateString("pt-BR")}
            </span>
            {overview.currentSubscription.cancelAtPeriodEnd ? (
              <span>Cancelamento programado para o fim do período.</span>
            ) : null}
          </div>
          {!overview.currentSubscription.cancelAtPeriodEnd &&
          (overview.currentSubscription.status === "ACTIVE" ||
            overview.currentSubscription.status === "PAST_DUE") ? (
            <button
              className="quiet-action"
              type="button"
              disabled={busyAction !== null}
              onClick={() =>
                void runAction(
                  `cancel:${overview.currentSubscription?.id ?? ""}`,
                  () => cancelSubscription(overview.currentSubscription?.id ?? "")
                )
              }
            >
              {busyAction === `cancel:${overview.currentSubscription.id}`
                ? "Cancelando..."
                : "Cancelar no fim do período"}
            </button>
          ) : null}
        </div>
      ) : null}

      <div className="subscription-plan-grid">
        {overview.plans.map((plan) => (
          <PlanCard
            key={plan.planCode}
            plan={plan}
            isCurrent={plan.planCode === currentPlanCode}
            checkoutEnabled={overview.sandboxEnabled}
            busy={busyAction !== null}
            busyAction={busyAction}
            onChoose={() =>
              void runAction(`checkout:${plan.planCode}`, () =>
                createSubscriptionCheckout(plan.planCode)
              )
            }
          />
        ))}
      </div>

      <section className="subscription-history" aria-labelledby="checkout-history-title">
        <h3 id="checkout-history-title">Checkouts recentes</h3>
        {overview.checkoutSessions.length === 0 ? (
          <p className="subscription-empty">Nenhum checkout iniciado.</p>
        ) : (
          <ul>
            {overview.checkoutSessions.map((checkout) => (
              <CheckoutRow
                key={checkout.id}
                checkout={checkout}
                sandboxEnabled={overview.sandboxEnabled}
                busy={busyAction !== null}
                isBusy={busyAction === `confirm:${checkout.id}`}
                onConfirm={() =>
                  void runAction(`confirm:${checkout.id}`, () =>
                    confirmSandboxCheckout(checkout.id)
                  )
                }
              />
            ))}
          </ul>
        )}
      </section>

      <section className="subscription-history" aria-labelledby="payment-history-title">
        <h3 id="payment-history-title">Histórico financeiro</h3>
        <p className="subscription-reconciliation">
          Conciliação: {overview.reconciliation.discrepancyMinorUnits === 0 ? "em dia" : "revisar"}
          <span>
            Provedor {formatMoney(overview.reconciliation.providerNetMinorUnits, overview.reconciliation.currency)}
            {" · "}
            ledger {formatMoney(overview.reconciliation.ledgerNetMinorUnits, overview.reconciliation.currency)}
            {" · "}
            diferença {formatMoney(overview.reconciliation.discrepancyMinorUnits, overview.reconciliation.currency)}
            {" · "}
            {overview.reconciliation.appliedEventCount} eventos / {overview.reconciliation.ledgerEntryCount} lançamentos
          </span>
        </p>
        {overview.recentPayments.length === 0 ? (
          <p className="subscription-empty">Nenhum pagamento registrado.</p>
        ) : (
          <ul>
            {overview.recentPayments.map((payment, index) => (
              <PaymentRow key={`${payment.type}:${payment.occurredAt}:${index}`} payment={payment} />
            ))}
          </ul>
        )}
      </section>
    </section>
  );
}

type PlanCardProps = {
  readonly plan: PlanOffer;
  readonly isCurrent: boolean;
  readonly checkoutEnabled: boolean;
  readonly busy: boolean;
  readonly busyAction: string | null;
  readonly onChoose: () => void;
};

function PlanCard({ plan, isCurrent, checkoutEnabled, busy, busyAction, onChoose }: PlanCardProps) {
  const actionKey = `checkout:${plan.planCode}`;
  const priceLabel =
    plan.planCode === "FREE"
      ? "Gratuito"
      : plan.monthlyPriceMinorUnits === 0
        ? checkoutEnabled
          ? "Sandbox · sem cobrança real"
          : "Preço pendente de configuração"
        : formatMoney(plan.monthlyPriceMinorUnits, plan.currency);
  return (
    <article className={`subscription-plan-card${plan.planCode === "PRO" ? " is-featured" : ""}`}>
      <div className="subscription-plan-title">
        <h3>{plan.displayName}</h3>
        {isCurrent ? <span className="project-status">Atual</span> : null}
      </div>
      <strong className="subscription-price">
        {priceLabel}
        {plan.planCode === "FREE" ? null : <small> / mês</small>}
      </strong>
      <ul className="subscription-benefits">
        <li>{plan.monthlyProcessingMinutes} minutos processados</li>
        <li>{formatBytes(plan.maxStorageBytes)} de armazenamento</li>
        <li>Arquivos até {formatBytes(plan.maxFileSizeBytes)}</li>
        <li>{plan.maxConcurrentJobs} job(s) simultâneos</li>
        <li>Retenção de render final: {plan.retention.finalDays} dias</li>
      </ul>
      <button
        className={plan.planCode === "PRO" ? "primary-action" : "quiet-action"}
        type="button"
        disabled={busy || isCurrent || plan.planCode === "FREE" || !checkoutEnabled}
        onClick={onChoose}
      >
        {busyAction === actionKey
          ? "Preparando checkout..."
          : isCurrent
            ? "Plano atual"
            : plan.planCode === "FREE"
              ? "Plano gratuito"
              : checkoutEnabled
                ? "Escolher Pro"
                : "Checkout desativado"}
      </button>
    </article>
  );
}

type CheckoutRowProps = {
  readonly checkout: CheckoutSession;
  readonly sandboxEnabled: boolean;
  readonly busy: boolean;
  readonly isBusy: boolean;
  readonly onConfirm: () => void;
};

function CheckoutRow({ checkout, sandboxEnabled, busy, isBusy, onConfirm }: CheckoutRowProps) {
  return (
    <li className="subscription-history-row">
      <div>
        <strong>{checkout.planCode} · {checkoutStatusLabel(checkout.status)}</strong>
        <span>Expira em {new Date(checkout.expiresAt).toLocaleString("pt-BR")}</span>
      </div>
      <div className="subscription-row-actions">
        {checkout.status === "PENDING" && checkout.checkoutUrl ? (
          <a className="quiet-action" href={checkout.checkoutUrl} target="_blank" rel="noreferrer">
            Abrir checkout
          </a>
        ) : null}
        {sandboxEnabled && checkout.status === "PENDING" ? (
          <button className="quiet-action" type="button" disabled={busy} onClick={onConfirm}>
            {isBusy ? "Confirmando..." : "Simular pagamento aprovado"}
          </button>
        ) : null}
      </div>
    </li>
  );
}

function PaymentRow({ payment }: { readonly payment: BillingPayment }) {
  return (
    <li className="subscription-history-row">
      <div>
        <strong>{billingTypeLabel(payment.type)}</strong>
        <span>{new Date(payment.occurredAt).toLocaleString("pt-BR")}</span>
      </div>
      <strong>{formatMoney(payment.amountMinorUnits, payment.currency)}</strong>
    </li>
  );
}

function formatMoney(amountMinorUnits: number, currency: string): string {
  const digits =
    new Intl.NumberFormat("pt-BR", { style: "currency", currency }).resolvedOptions()
      .maximumFractionDigits ?? 2;
  return new Intl.NumberFormat("pt-BR", { style: "currency", currency }).format(
    amountMinorUnits / 10 ** digits
  );
}

function formatBytes(bytes: number): string {
  if (bytes >= 1024 ** 3) {
    return `${(bytes / 1024 ** 3).toFixed(0)} GB`;
  }
  return `${(bytes / 1024 ** 2).toFixed(0)} MB`;
}

function subscriptionStatusLabel(status: SubscriptionStatus): string {
  switch (status) {
    case "ACTIVE":
      return "Ativa";
    case "PAST_DUE":
      return "Pagamento pendente";
    case "CANCELED":
      return "Cancelada";
    case "EXPIRED":
      return "Expirada";
    case "CHARGEBACK":
      return "Contestada";
  }
}

function checkoutStatusLabel(status: CheckoutSession["status"]): string {
  switch (status) {
    case "PENDING":
      return "Aguardando pagamento";
    case "COMPLETED":
      return "Concluído";
    case "EXPIRED":
      return "Expirado";
    case "FAILED":
      return "Falhou";
  }
}

function billingTypeLabel(type: BillingPayment["type"]): string {
  switch (type) {
    case "CHARGE":
      return "Cobrança confirmada";
    case "REFUND":
      return "Reembolso";
    case "CHARGEBACK":
      return "Contestação";
  }
}
