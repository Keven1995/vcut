"use client";

import Link from "next/link";
import { SubscriptionPanel } from "../../../features/subscription";

export default function SubscriptionPage() {
  return (
    <main className="dashboard-shell">
      <nav className="nav dashboard-nav" aria-label="Navegação da assinatura">
        <Link className="brand" href="/dashboard">
          <span className="brand-mark">V</span>
          <span>vcut</span>
        </Link>
        <Link className="quiet-action" href="/dashboard">Voltar ao dashboard</Link>
      </nav>
      <section className="dashboard-heading" aria-labelledby="subscription-page-title">
        <div>
          <p className="eyebrow">Planos / cobrança</p>
          <h1 id="subscription-page-title">Seu plano acompanha o ritmo de produção.</h1>
        </div>
      </section>
      <SubscriptionPanel />
    </main>
  );
}
