import { useSyncExternalStore } from 'react';
import type { AiModalOptions, AiModalAPI } from './types';

// ── Global store (useSyncExternalStore compatible) ─────────────

interface ModalState {
  config: AiModalOptions | null;
  open: boolean;
  key: number; // increment on each open to force remount
}

let state: ModalState = { config: null, open: false, key: 0 };
const listeners = new Set<() => void>();

function getSnapshot(): ModalState {
  return state;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function emit() {
  listeners.forEach((fn) => fn());
}

// ── Public API ──────────────────────────────────────────────────

export function openAiModal(opts: AiModalOptions) {
  const memoryId = opts.memoryId || `aimodal-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  state = {
    config: { ...opts, memoryId },
    open: true,
    key: state.key + 1,
  };
  emit();
}

export function closeAiModal() {
  state = { config: null, open: false, key: state.key };
  emit();
}

/** React hook to read the current modal state. */
export function useModalState(): ModalState {
  return useSyncExternalStore(subscribe, getSnapshot);
}

// ── Global bridge installation ──────────────────────────────────

let installed = false;

export function initAiModalBridge(): AiModalAPI {
  if (installed) return (window as unknown as Record<string, unknown>).__wuwei_ai_modal as AiModalAPI;

  const api: AiModalAPI = {
    open: openAiModal,
    cancel(memoryId: string) {
      closeAiModal();
      fetch(`/api/ai/chat/${encodeURIComponent(memoryId)}`, { method: 'DELETE' }).catch(() => {});
    },
  };

  (window as unknown as Record<string, unknown>).__wuwei_ai_modal = api;
  installed = true;
  return api;
}
