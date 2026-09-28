"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { apiRequest, setAccessToken } from "../../lib/api-client";

type AuthResponse = { accessToken: string };

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      const response = await apiRequest<AuthResponse>(
        "/api/auth/login",
        {
          method: "POST",
          body: JSON.stringify({ email, password })
        },
        false
      );
      setAccessToken(response.accessToken);
      router.push("/dashboard");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Não foi possível entrar.");
    } finally {
      setPending(false);
    }
  }

  return (
    <main className="auth-shell">
      <section className="auth-card" aria-labelledby="login-title">
        <Link className="brand" href="/">
          <span className="brand-mark">V</span>
          <span>vcut</span>
        </Link>
        <p className="eyebrow">Área de acesso</p>
        <h1 id="login-title">Volte para seus projetos.</h1>
        <p className="auth-intro">Entre para continuar de onde parou.</p>
        <form className="auth-form" onSubmit={submit}>
          <label>
            Email
            <input
              required
              type="email"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <label>
            Senha
            <input
              required
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          {error ? <p className="form-error" role="alert">{error}</p> : null}
          <button className="primary-action full-width" type="submit" disabled={pending}>
            {pending ? "Entrando..." : "Entrar"}
          </button>
        </form>
        <p className="auth-switch">
          Ainda não tem conta? <Link href="/register">Criar cadastro</Link>
        </p>
      </section>
    </main>
  );
}
