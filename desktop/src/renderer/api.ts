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
  source: 'local' | 'canvas';
  /** origem do documento quando vem do Canvas (sem URLs nem credenciais) */
  canvas: CanvasOrigin | null;
  /** última página lida (restaura a sessão) */
  last_page: number;
}

export interface CanvasOrigin {
  institution: string | null;
  course_id: number;
  course_name: string;
  course_code: string;
  module_id: string;
  module_name: string;
  file_id: number;
  display_name: string;
  remote_modified_at: string | null;
}

export const workspace = {
  get: () => api<{ slots: Record<SlotId, DocumentInfo | null> }>('/workspace'),
  setSlot: (slot: SlotId, path: string) => api<DocumentInfo>(`/workspace/slots/${slot}`, { method: 'PUT', json: { path } }),
  clearSlot: (slot: SlotId) => api<void>(`/workspace/slots/${slot}`, { method: 'DELETE' }),
  document: (id: string) => api<DocumentInfo>(`/documents/${encodeURIComponent(id)}`),
  fileUrl: (id: string) => `/api/documents/${encodeURIComponent(id)}/file`,
};

// ---- Canvas LMS (o token fica só no backend; aqui nunca aparece) ----

export interface CanvasStatus { connected: boolean; base_url: string | null; user_name: string | null; auto_refresh: boolean }
export interface CanvasCourse { id: number; name: string; short_name: string; code: string; published: boolean; state: string; term: string | null }
export interface CanvasModule { id: string; name: string; position: number; items_count: number | null; locked: boolean; kind: 'module' | 'files' }
export interface CanvasItem {
  key: string;
  kind: 'pdf' | 'file' | 'page' | 'link' | 'header' | 'other';
  title: string;
  indent: number;
  file_id: number | null;
  size: number | null;
  modified_at: string | null;
  available: boolean;
  reason: string | null;
  via: string | null;
}
export interface Cached<T> { data: T; fetched_at: number; stale: boolean; warning: string | null }
export interface CanvasOpen { course_id: number; module_id: string; file_id: number; course_name: string; module_name: string }
export interface CanvasPrepare { ok: boolean; update_available: boolean; refreshed: boolean; warning?: string | null }

const q = (refresh: boolean) => (refresh ? '?refresh=true' : '');
export const canvas = {
  status: () => api<CanvasStatus>('/canvas/status'),
  connect: (base_url: string, token: string) => api<CanvasStatus>('/canvas/connection', { method: 'PUT', json: { base_url, token } }),
  disconnect: () => api<void>('/canvas/connection', { method: 'DELETE' }),
  setAutoRefresh: (auto_refresh: boolean) => api<CanvasStatus>('/canvas/preferences', { method: 'PUT', json: { auto_refresh } }),
  courses: (refresh = false) => api<Cached<CanvasCourse[]>>('/canvas/courses' + q(refresh)),
  modules: (courseId: number, refresh = false) => api<Cached<CanvasModule[]>>(`/canvas/courses/${courseId}/modules` + q(refresh)),
  items: (courseId: number, moduleId: string, refresh = false) =>
    api<Cached<CanvasItem[]>>(`/canvas/courses/${courseId}/modules/${encodeURIComponent(moduleId)}/items` + q(refresh)),
  openInSlot: (slot: SlotId, body: CanvasOpen) => api<DocumentInfo>(`/canvas/slots/${slot}`, { method: 'PUT', json: body }),
  prepare: (docId: string) => api<CanvasPrepare>(`/canvas/documents/${encodeURIComponent(docId)}/prepare`, { method: 'POST' }),
  refreshDoc: (docId: string) => api<{ ok: boolean; updated: boolean }>(`/canvas/documents/${encodeURIComponent(docId)}/refresh`, { method: 'POST' }),
  progress: (docId: string, page: number) => api<void>(`/documents/${encodeURIComponent(docId)}/progress`, { method: 'PUT', json: { page } }),
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
