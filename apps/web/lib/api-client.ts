import { getApiErrorCode, parseApiError } from "./api-errors";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const ACCESS_TOKEN_KEY = "vcut.accessToken";
const TRACE_CONTEXT_KEY = "vcut.traceContext";
const TRACE_CONTEXT_TTL_MS = 60 * 60 * 1000;
const CORRELATION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const TRACEPARENT_PATTERN = /^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$/i;

type TraceContext = {
  correlationId: string;
  traceparent: string | null;
  lastSeenAt: number;
};

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiClientError";
    this.status = status;
    this.code = code;
  }
}

export function getAccessToken(): string | null {
  if (typeof window === "undefined") {
    return null;
  }
  return window.sessionStorage.getItem(ACCESS_TOKEN_KEY);
}

export function setAccessToken(accessToken: string): void {
  window.sessionStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
}

export function clearAccessToken(): void {
  window.sessionStorage.removeItem(ACCESS_TOKEN_KEY);
  window.sessionStorage.removeItem(TRACE_CONTEXT_KEY);
}

export async function refreshAccessToken(): Promise<string | null> {
  const headers = flowHeaders();
  headers.set("Content-Type", "application/json");
  const response = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
    method: "POST",
    credentials: "include",
    headers
  });
  rememberTraceContext(response);
  if (!response.ok) {
    clearAccessToken();
    return null;
  }
  const payload: unknown = await response.json();
  if (!isAuthResponse(payload)) {
    clearAccessToken();
    return null;
  }
  setAccessToken(payload.accessToken);
  return payload.accessToken;
}

export async function apiRequest<T>(
  path: string,
  init: RequestInit = {},
  retryAuthentication = true
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  const traceHeaders = flowHeaders();
  traceHeaders.forEach((value, name) => headers.set(name, value));
  const accessToken = getAccessToken();
  if (accessToken) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    credentials: "include",
    headers
  });
  rememberTraceContext(response);

  if (response.status === 401 && retryAuthentication && path !== "/api/auth/refresh") {
    const refreshedToken = await refreshAccessToken();
    if (refreshedToken) {
      return apiRequest<T>(path, init, false);
    }
  }

  const payload: unknown = response.status === 204 ? null : await response.json();
  if (!response.ok) {
    const parsed = parseApiError(payload);
    throw new ApiClientError(
      response.status,
      getApiErrorCode(payload) ?? "UNKNOWN_ERROR",
      parsed?.message ?? "Não foi possível concluir a solicitação."
    );
  }
  return payload as T;
}

function flowHeaders(): Headers {
  const context = getTraceContext();
  const headers = new Headers({ "X-Correlation-Id": context.correlationId });
  if (context.traceparent) {
    headers.set("traceparent", context.traceparent);
  }
  return headers;
}

function getTraceContext(): TraceContext {
  if (typeof window === "undefined") {
    return { correlationId: "", traceparent: null, lastSeenAt: 0 };
  }
  const serialized = window.sessionStorage.getItem(TRACE_CONTEXT_KEY);
  if (serialized) {
    try {
      const value: unknown = JSON.parse(serialized);
      if (isTraceContext(value) && Date.now() - value.lastSeenAt <= TRACE_CONTEXT_TTL_MS) {
        return value;
      }
    } catch {
      window.sessionStorage.removeItem(TRACE_CONTEXT_KEY);
    }
  }
  const fresh = {
    correlationId: window.crypto.randomUUID(),
    traceparent: null,
    lastSeenAt: Date.now()
  } satisfies TraceContext;
  window.sessionStorage.setItem(TRACE_CONTEXT_KEY, JSON.stringify(fresh));
  return fresh;
}

function rememberTraceContext(response: Response): void {
  if (typeof window === "undefined") {
    return;
  }
  const prior = getTraceContext();
  const correlationId = response.headers.get("X-Correlation-Id") ?? prior.correlationId;
  const responseTraceparent = response.headers.get("traceparent");
  const traceparent = responseTraceparent && TRACEPARENT_PATTERN.test(responseTraceparent)
    ? responseTraceparent
    : prior.traceparent;
  if (!CORRELATION_ID_PATTERN.test(correlationId)) {
    return;
  }
  window.sessionStorage.setItem(
    TRACE_CONTEXT_KEY,
    JSON.stringify({ correlationId, traceparent, lastSeenAt: Date.now() } satisfies TraceContext)
  );
}

function isTraceContext(value: unknown): value is TraceContext {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const context = value as Record<string, unknown>;
  return (
    typeof context.correlationId === "string" &&
    CORRELATION_ID_PATTERN.test(context.correlationId) &&
    (context.traceparent === null ||
      (typeof context.traceparent === "string" && TRACEPARENT_PATTERN.test(context.traceparent))) &&
    typeof context.lastSeenAt === "number"
  );
}

function isAuthResponse(value: unknown): value is { accessToken: string } {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const accessToken = (value as { accessToken?: unknown }).accessToken;
  return typeof accessToken === "string" && accessToken.length > 0;
}
