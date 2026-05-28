// SurfaceStore.ts — manages A2UI surface state per thread (ns:ui)
// Replaces direct window CustomEvent listeners in WwWorkspace

interface SurfaceState {
  skillId: string | null;
  ui: Record<string, unknown> | null;
  runtime: string | null;
  handlersJs: string | null;
  capabilities: Record<string, unknown> | null;
}

type SurfaceListener = () => void;

const surfaces = new Map<string, SurfaceState>();
const listeners: SurfaceListener[] = [];

function notify() {
  listeners.forEach((fn) => fn());
}

function getKey(threadId: string | null): string {
  return threadId || '__global__';
}

export const surfaceStore = {
  dispatch(msg: Record<string, unknown>) {
    const type = msg.type as string;
    switch (type) {
      case 'skill-activated': {
        const threadId = (msg.threadId as string) || '__global__';
        surfaces.set(threadId, {
          skillId: msg.skillId as string,
          ui: msg.ui as Record<string, unknown>,
          runtime: msg.runtime as string | null,
          handlersJs: msg.handlersJs as string | null,
          capabilities: msg.capabilities as Record<string, unknown> | null,
        });
        notify();
        break;
      }
      case 'skill-deactivated': {
        const threadId = (msg.threadId as string) || '__global__';
        const existing = surfaces.get(threadId);
        if (existing && existing.skillId === msg.skillId) {
          surfaces.delete(threadId);
        }
        notify();
        break;
      }
    }
  },

  onChange(fn: SurfaceListener) {
    listeners.push(fn);
    return () => {
      const idx = listeners.indexOf(fn);
      if (idx >= 0) listeners.splice(idx, 1);
    };
  },

  getState(threadId: string | null): SurfaceState | undefined {
    return surfaces.get(getKey(threadId));
  },

  getAllSurfaces(): Map<string, SurfaceState> {
    return surfaces;
  },
};
