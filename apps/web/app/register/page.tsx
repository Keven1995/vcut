"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { apiRequest, setAccessToken } from "../../lib/api-client";

type AuthResponse = { accessToken: string };

export default function RegisterPage() {
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
        "/api/auth/register",
        {
          method: "POST",
          body: JSON.stringify({ email, password })
        },
        false
      );
      setAccessToken(response.accessToken);
      router.push("/dashboard");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Não foi possível criar a conta.");
    } finally {
      setPending(false);
    }
  }

  return (
    <main className="auth-shell">
      <section className="auth-card" aria-labelledby="register-title">
        <Link className="brand" href="/">
          <span className="brand-mark">V</span>
          <span>vcut</span>
        </Link>
        <p className="eyebrow">Primeiro passo</p>
        <h1 id="register-title">Comece com um espaço só seu.</h1>
        <p className="auth-intro">Crie sua conta e organize os primeiros projetos.</p>
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
              minLength={8}
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
            <small>Use pelo menos 8 caracteres.</small>
          </label>
          {error ? <p className="form-error" role="alert">{error}</p> : null}
          <button className="primary-action full-width" type="submit" disabled={pending}>
            {pending ? "Criando..." : "Criar conta"}
          </button>
        </form>
        <p className="auth-switch">
          Já tem conta? <Link href="/login">Entrar</Link>
        </p>
      </section>
    </main>
  );
}
