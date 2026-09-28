import { getApiErrorCode, parseApiError } from "./api-errors";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const ACCESS_TOKEN_KEY = "vcut.accessToken";

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
}

export async function refreshAccessToken(): Promise<string | null> {
  const response = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" }
  });
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
  const accessToken = getAccessToken();
  if (accessToken) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    credentials: "include",
    headers
  });

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

function isAuthResponse(value: unknown): value is { accessToken: string } {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const accessToken = (value as { accessToken?: unknown }).accessToken;
  return typeof accessToken === "string" && accessToken.length > 0;
}
