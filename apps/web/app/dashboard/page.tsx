"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useCallback, useEffect, useState } from "react";
import { ApiClientError, apiRequest, clearAccessToken } from "../../lib/api-client";

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
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectName, setProjectName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(true);

  const handleError = useCallback(
    (caught: unknown) => {
      if (caught instanceof ApiClientError && caught.status === 401) {
        router.push("/login?expired=1");
        return;
      }
      setError(caught instanceof Error ? caught.message : "Não foi possível carregar os projetos.");
    },
    [router]
  );

  const loadProjects = useCallback(async () => {
    setPending(true);
    try {
      const response = await apiRequest<ProjectPage>("/api/projects");
      setProjects(response.content);
      setError(null);
    } catch (caught) {
      handleError(caught);
    } finally {
      setPending(false);
    }
  }, [handleError]);

  useEffect(() => {
    const timer = window.setTimeout(() => void loadProjects(), 0);
    return () => window.clearTimeout(timer);
  }, [loadProjects]);

  async function createProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!projectName.trim()) {
      return;
    }
    setPending(true);
    try {
      await apiRequest<Project>("/api/projects", {
        method: "POST",
        body: JSON.stringify({ name: projectName })
      });
      setProjectName("");
      await loadProjects();
    } catch (caught) {
      handleError(caught);
      setPending(false);
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
            <button className="primary-action" type="submit" disabled={pending}>
              Criar
            </button>
          </div>
        </form>
      </section>
      {error ? <p className="form-error" role="alert">{error}</p> : null}
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
          <article className="project-card" key={project.id}>
            <span className="project-status">{project.status === "ACTIVE" ? "Ativo" : "Arquivado"}</span>
            <h2>{project.name}</h2>
            <p>Criado em {new Date(project.createdAt).toLocaleDateString("pt-BR")}</p>
          </article>
        ))}
      </section>
    </main>
  );
}
