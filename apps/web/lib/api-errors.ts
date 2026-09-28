export type ApiErrorResponse = {
  readonly code: string;
  readonly message: string;
  readonly traceId: string;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

export function parseApiError(value: unknown): ApiErrorResponse | null {
  if (!isRecord(value)) {
    return null;
  }

  const { code, message, traceId } = value;
  if (
    typeof code !== "string" ||
    typeof message !== "string" ||
    typeof traceId !== "string"
  ) {
    return null;
  }

  return { code, message, traceId };
}

export function getApiErrorCode(value: unknown): string | null {
  return parseApiError(value)?.code ?? null;
}
