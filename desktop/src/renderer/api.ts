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

// ---- Documentos / workspace ----

export type SlotId = 'A' | 'B';

export interface DocumentInfo {
  id: string;
  slot: SlotId;
  name: string;
  pages: number;
  title: string | null;
  /** processing | ready | no_text | error */
  status: 'processing' | 'ready' | 'no_text' | 'error';
  pages_done: number;
  error: string | null;
  /** ok | modified | missing */
  file_state: 'ok' | 'modified' | 'missing';
}

export const workspace = {
  get: () => api<{ slots: Record<SlotId, DocumentInfo | null> }>('/workspace'),
  setSlot: (slot: SlotId, path: string) => api<DocumentInfo>(`/workspace/slots/${slot}`, { method: 'PUT', json: { path } }),
  clearSlot: (slot: SlotId) => api<void>(`/workspace/slots/${slot}`, { method: 'DELETE' }),
  document: (id: string) => api<DocumentInfo>(`/documents/${encodeURIComponent(id)}`),
  fileUrl: (id: string) => `/api/documents/${encodeURIComponent(id)}/file`,
};

// ---- Exercícios ----

export type ExerciseKind = 'objective' | 'subjective';

export interface ExRef {
  slot: SlotId;
  page: number;
  file_name: string;
  document_id: string;
}

export interface ExUsage {
  input_tokens?: number;
  output_tokens?: number;
  cost_usd?: number | null;
}

export interface ExOption {
  id: string;
  text: string;
  /** só depois da correção */
  explanation?: string;
  is_correct?: boolean;
}

/** Questão gerada (sem gabarito). */
export interface Exercise {
  id: string;
  kind: ExerciseKind;
  statement: string;
  difficulty: string;
  options: ExOption[];
  model: string;
  usage: ExUsage | null;
}

/** Correção. Os campos de cada tipo ficam ausentes no outro. */
export interface ExResult {
  exercise_id: string;
  kind: ExerciseKind;
  difficulty?: string;
  references: ExRef[];
  usage?: ExUsage | null;
  // objetiva
  correct?: boolean;
  selected?: string;
  correct_option?: string;
  explanation?: string;
  options?: ExOption[];
  // discursiva
  verdict?: 'correct' | 'partial' | 'incorrect';
  score?: number;
  summary?: string;
  strengths?: string[];
  missing?: string[];
  corrections?: Array<{ concept: string; explanation: string }>;
  ideal_answer?: string;
  criteria?: string[];
}

export const exercises = {
  generate: (kind: ExerciseKind) => api<Exercise>('/exercises/generate', { method: 'POST', json: { kind } }),
  answer: (id: string, body: { option?: string; text?: string }) =>
    api<ExResult>(`/exercises/${encodeURIComponent(id)}/answer`, { method: 'POST', json: body }),
};

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
