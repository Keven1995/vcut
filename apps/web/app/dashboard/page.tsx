"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { apiRequest, clearAccessToken } from "../../lib/api-client";
import { invalidateServerQuery, useServerQuery } from "../../lib/server-state";
import { UsageSummaryPanel } from "../../features/usage/usage-summary-panel";

type Project = {
  id: string;
  name: string;
  status: "ACTIVE" | "ARCHIVED";
  createdAt: string;
};

type ProjectPage = {
  content: Project[];
  page: { totalElements: number; totalPages: number };
};

export default function DashboardPage() {
  const router = useRouter();
  const [projectName, setProjectName] = useState("");
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const projectQuery = useServerQuery<ProjectPage>("projects", () => apiRequest<ProjectPage>("/api/projects"));
  const projects = projectQuery.data?.content ?? [];
  const pending = projectQuery.isLoading;
  const error = mutationError ?? projectQuery.error?.message ?? null;

  async function createProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!projectName.trim()) {
      return;
    }
    setCreating(true);
    try {
      await apiRequest<Project>("/api/projects", {
        method: "POST",
        body: JSON.stringify({ name: projectName })
      });
      setProjectName("");
      invalidateServerQuery("projects");
    } catch (caught) {
      setMutationError(caught instanceof Error ? caught.message : "Não foi possível criar o projeto.");
    } finally {
      setCreating(false);
    }
  }

  async function logout() {
    try {
      await apiRequest<void>("/api/auth/logout", { method: "DELETE" });
    } finally {
      clearAccessToken();
      router.push("/login");
    }
  }

  return (
    <main className="dashboard-shell">
      <nav className="nav dashboard-nav" aria-label="Navegação autenticada">
        <Link className="brand" href="/dashboard">
          <span className="brand-mark">V</span>
          <span>vcut</span>
        </Link>
        <Link className="quiet-action" href="/dashboard/subscription">
          Assinatura
        </Link>
        <button className="quiet-action" type="button" onClick={() => void logout()}>
          Sair
        </button>
      </nav>
      <section className="dashboard-heading" aria-labelledby="dashboard-title">
        <div>
          <p className="eyebrow">Seu espaço de trabalho</p>
          <h1 id="dashboard-title">Projetos que estão prontos para ganhar contexto.</h1>
        </div>
        <form className="project-form" onSubmit={createProject}>
          <label htmlFor="project-name">Novo projeto</label>
          <div className="inline-form">
            <input
              id="project-name"
              required
              maxLength={120}
              placeholder="Ex.: Podcast de setembro"
              value={projectName}
              onChange={(event) => setProjectName(event.target.value)}
            />
            <button className="primary-action" type="submit" disabled={pending || creating}>
              Criar
            </button>
          </div>
        </form>
      </section>
      {error ? <p className="form-error" role="alert">{error}</p> : null}
      <UsageSummaryPanel />
      <section className="project-grid" aria-live="polite">
        {pending && projects.length === 0 ? <p className="empty-state">Carregando projetos...</p> : null}
        {!pending && projects.length === 0 ? (
          <div className="empty-state">
            <span className="step-label">Ainda vazio</span>
            <h2>O primeiro projeto começa com uma boa fonte.</h2>
            <p>Crie um projeto acima para preparar o fluxo de upload e processamento.</p>
          </div>
        ) : null}
        {projects.map((project) => (
          <Link className="project-card" href={`/dashboard/projects/${project.id}`} key={project.id}>
            <span className="project-status">{project.status === "ACTIVE" ? "Ativo" : "Arquivado"}</span>
            <h2>{project.name}</h2>
            <p>Criado em {new Date(project.createdAt).toLocaleDateString("pt-BR")}</p>
            <span className="project-open">Abrir projeto <span aria-hidden="true">↗</span></span>
          </Link>
        ))}
      </section>
    </main>
  );
}
