import Link from "next/link";

const workflow = [
  { label: "01", title: "Envie seu video", detail: "Upload direto e seguro para o storage." },
  { label: "02", title: "Deixe a analise trabalhar", detail: "Transcricao e sinais de conteudo em etapas." },
  { label: "03", title: "Escolha seu corte", detail: "Sugestoes editaveis antes do render final." }
];

export default function HomePage() {
  return (
    <main className="shell">
      <nav className="nav" aria-label="Navegacao principal">
        <Link className="brand" href="/" aria-label="Vcut inicio">
          <span className="brand-mark">V</span>
          <span>vcut</span>
        </Link>
        <span className="nav-status">Fundacao local</span>
      </nav>

      <section className="hero" aria-labelledby="hero-title">
        <div className="hero-copy">
          <p className="eyebrow">Cortes com contexto, nao apenas recortes</p>
          <h1 id="hero-title">Do video longo ao momento que merece ser visto.</h1>
          <p className="hero-description">
            A base da plataforma esta pronta para receber videos, entender cada etapa e
            devolver sugestoes publicaveis com controle humano.
          </p>
          <div className="hero-actions">
            <Link className="primary-action" href="/register">
              Criar primeiro projeto
            </Link>
            <Link className="text-action" href="/login">
              Entrar <span aria-hidden="true">-&gt;</span>
            </Link>
            <a className="text-action" href="#workflow">
              Conhecer o fluxo <span aria-hidden="true">-&gt;</span>
            </a>
          </div>
        </div>

        <div className="hero-card" aria-label="Resumo do processamento">
          <div className="card-topline">
            <span className="live-dot" aria-hidden="true" />
            <span>Pipeline pronto</span>
            <span className="card-time">00:00:00</span>
          </div>
          <div className="video-frame">
            <div className="frame-grid" aria-hidden="true" />
            <div className="play-orb" aria-hidden="true">&gt;</div>
            <span className="frame-label">PREVIEW / 16:9</span>
          </div>
          <div className="card-footer">
            <span>Sem projetos ainda</span>
            <span className="footer-arrow" aria-hidden="true">&#8599;</span>
          </div>
        </div>
      </section>

      <section className="workflow" id="workflow" aria-labelledby="workflow-title">
        <div className="section-heading">
          <p className="eyebrow">Como funciona</p>
          <h2 id="workflow-title">Uma linha clara entre materia-prima e publicacao.</h2>
        </div>
        <div className="workflow-grid">
          {workflow.map((item) => (
            <article className="workflow-item" key={item.label}>
              <span className="step-label">{item.label}</span>
              <h3>{item.title}</h3>
              <p>{item.detail}</p>
            </article>
          ))}
        </div>
      </section>

      <footer className="footer">
        <span>vcut / sprint 1</span>
        <span>Video intelligence, built in the open.</span>
      </footer>
    </main>
  );
}
