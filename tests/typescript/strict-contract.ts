type ExternalPayload = Record<string, unknown>;

function readTitle(payload: unknown): string {
  if (
    typeof payload === "object" &&
    payload !== null &&
    "title" in payload &&
    typeof payload.title === "string"
  ) {
    return payload.title;
  }

  return "untitled";
}

const payload: ExternalPayload = { title: "Example" };
const title: string = readTitle(payload);

export { readTitle, title };
