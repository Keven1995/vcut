import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { apiRequest } from "./api-client";

function jsonResponse(correlationId: string, traceparent: string): Response {
  return new Response(JSON.stringify({ accepted: true }), {
    status: 200,
    headers: {
      "Content-Type": "application/json",
      "X-Correlation-Id": correlationId,
      traceparent
    }
  });
}

describe("API trace context", () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    window.sessionStorage.clear();
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("propagates correlation ID and W3C traceparent across API calls", async () => {
    const correlationId = "11111111-1111-4111-8111-111111111111";
    const firstTraceparent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
    const nextTraceparent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-cccccccccccccccc-01";
    fetchMock
      .mockResolvedValueOnce(jsonResponse(correlationId, firstTraceparent))
      .mockResolvedValueOnce(jsonResponse(correlationId, nextTraceparent));

    await apiRequest<{ accepted: boolean }>("/api/videos/one/process");
    await apiRequest<{ accepted: boolean }>("/api/renders/two/download-url");

    const secondRequestHeaders = new Headers(fetchMock.mock.calls[1]?.[1]?.headers);
    expect(secondRequestHeaders.get("X-Correlation-Id")).toBe(correlationId);
    expect(secondRequestHeaders.get("traceparent")).toBe(firstTraceparent);
  });
});
