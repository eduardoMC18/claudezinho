/** Cliente do backend. Tudo passa pelo proxy do processo principal (app://local/api/*), que injeta o token. */

export class ApiError extends Error {
  constructor(public status: number, message: string, public kind?: string) {
    super(message);
  }
}

export async function api<T = unknown>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const { json, headers, ...rest } = init;
  const res = await fetch('/api' + path, {
    ...rest,
    headers: { ...(json !== undefined ? { 'content-type': 'application/json' } : {}), ...headers },
    body: json !== undefined ? JSON.stringify(json) : rest.body,
  });
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  let data: unknown = undefined;
  try { data = text ? JSON.parse(text) : undefined; } catch { /* corpo não-JSON */ }
  if (!res.ok) {
    const d = (data as { detail?: unknown } | undefined)?.detail;
    if (d && typeof d === 'object') {
      const o = d as { kind?: string; message?: string };
      throw new ApiError(res.status, o.message ?? `HTTP ${res.status}`, o.kind);
    }
    throw new ApiError(res.status, typeof d === 'string' ? d : `HTTP ${res.status}`);
  }
  return data as T;
}

// ---- Configurações de IA (formato de GET /settings/ai) ----

export interface ModelInfo {
  id: string;
  label: string;
  description: string;
  context_window: number | null;
  price_input_per_mtok: number | null;
  price_output_per_mtok: number | null;
}

export interface ProviderInfo {
  id: string;
  display_name: string;
  key_hint: string;
  has_key: boolean;
  selected_model: string;
  models: ModelInfo[];
}

export interface AiOverview {
  default_provider: string;
  providers: ProviderInfo[];
}

export const aiSettings = {
  get: () => api<AiOverview>('/settings/ai'),
  setDefault: (id: string) => api<AiOverview>('/settings/ai', { method: 'PUT', json: { default_provider: id } }),
  setModel: (providerId: string, modelId: string) =>
    api<AiOverview>('/settings/ai', { method: 'PUT', json: { models: { [providerId]: modelId } } }),
  saveKey: (providerId: string, apiKey: string) =>
    api<void>(`/settings/ai/providers/${encodeURIComponent(providerId)}/key`, { method: 'PUT', json: { api_key: apiKey } }),
  removeKey: (providerId: string) => api<void>(`/settings/ai/providers/${encodeURIComponent(providerId)}/key`, { method: 'DELETE' }),
  test: (providerId: string, model: string, apiKey?: string) =>
    api<{ ok: boolean; message: string; latency_ms: number | null }>(
      `/settings/ai/providers/${encodeURIComponent(providerId)}/test`,
      { method: 'POST', json: { model, api_key: apiKey || undefined } },
    ),
};
