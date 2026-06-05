import type { BrowserCapability } from './types';
import { kernel } from '../kernel';

export function createLocalCapabilities(
  skillId: string,
  componentCache: Record<string, Record<string, unknown>>,
  patchBuffer: Record<string, unknown>[],
  declaredCaps: Record<string, unknown>,
  flushPatches?: (skillId: string, patches: Record<string, unknown>[]) => void,
): Pick<BrowserCapability, 'ui' | 'permission' | 'canvas'> {
  return {
    ui: {
      set(id: string, property: string, value: unknown) {
        if (!componentCache[id]) componentCache[id] = {};
        componentCache[id][property] = value;
        patchBuffer.push({ id, [property]: value });
      },
      get(id: string, property: string): unknown {
        return componentCache[id]?.[property] ?? null;
      },
      flush() {
        if (flushPatches && patchBuffer.length > 0) {
          const patches = [...patchBuffer];
          patchBuffer.length = 0;
          flushPatches(skillId, patches);
        }
      },
    },
    canvas: declaredCaps['canvas']
      ? {
          render(canvasId: string, commands: Record<string, unknown>[]) {
            const key = `__wuwei_canvas_${canvasId}`;
            let retries = 0;
            const tryDraw = () => {
              const entry = (window as unknown as Record<string, unknown>)[key] as
                | { el: HTMLCanvasElement; draw: (cmds: Record<string, unknown>[]) => void }
                | undefined;
              if (entry) {
                entry.draw(commands);
              } else if (retries < 30) {
                retries++;
                requestAnimationFrame(tryDraw);
              } else {
                console.warn('[Canvas] Canvas not ready after 30 frames:', canvasId);
              }
            };
            tryDraw();
          },
          getContext(canvasId: string): CanvasRenderingContext2D | null {
            const key = `__wuwei_canvas_${canvasId}`;
            const entry = (window as unknown as Record<string, unknown>)[key] as
              | { el: HTMLCanvasElement; draw: (cmds: Record<string, unknown>[]) => void }
              | undefined;
            if (!entry?.el) return null;
            // Sync canvas resolution to display size for sharp rendering
            const rect = entry.el.getBoundingClientRect();
            if (rect.width > 0 && rect.height > 0) {
              entry.el.width = Math.round(rect.width * window.devicePixelRatio);
              entry.el.height = Math.round(rect.height * window.devicePixelRatio);
              const ctx = entry.el.getContext('2d');
              if (ctx) ctx.scale(window.devicePixelRatio, window.devicePixelRatio);
            }
            return entry.el.getContext('2d');
          },
        }
      : undefined,
    permission: {
      check(name: string): boolean {
        return name in declaredCaps;
      },
      request(name: string, _reason: string): Promise<boolean> {
        // Permission.request for browser-js: send via capability-proxy
        // The kernel handles gate dialog for browser-js skills too
        return new Promise((resolve) => {
          const requestId = `perm-${Date.now()}-${Math.random().toString(36).slice(2)}`;
          const handler = (e: Event) => {
            const detail = (e as CustomEvent).detail;
            if (detail.requestId === requestId) {
              window.removeEventListener('capability-proxy-result', handler);
              resolve(detail.result === true || detail.result === 'true');
            }
          };
          window.addEventListener('capability-proxy-result', handler);
          kernel.send({
            type: 'capability-proxy',
            skillId: skillId,
            capName: 'permission',
            method: 'request',
            args: [name, _reason],
            requestId,
          } as Record<string, unknown>);
          // Timeout after 5 minutes
          setTimeout(() => {
            window.removeEventListener('capability-proxy-result', handler);
            resolve(false);
          }, 300000);
        });
      },
    },
  };
}
