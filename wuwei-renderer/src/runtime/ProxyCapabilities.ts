import type { BrowserCapability } from './types';
import { kernel } from '../kernel';

type PendingRequest = {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
};

const pendingRequests = new Map<string, PendingRequest>();

export function initProxyListener() {
  window.addEventListener('capability-proxy-result', ((e: CustomEvent) => {
    const { skillId: _skillId, requestId, result, error } = e.detail;
    const pending = pendingRequests.get(requestId);
    if (!pending) return;
    pendingRequests.delete(requestId);
    clearTimeout(pending.timer);
    if (error) {
      pending.reject(new Error(error));
    } else {
      pending.resolve(result);
    }
  }) as EventListener);
}

function proxyCall(
  skillId: string,
  capName: string,
  method: string,
  args: unknown[]
): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const requestId = `${capName}-${method}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const timer = setTimeout(() => {
      pendingRequests.delete(requestId);
      reject(new Error(`Timeout: ${capName}.${method} after 30s`));
    }, 30000);
    pendingRequests.set(requestId, { resolve, reject, timer });
    kernel.send({
      type: 'capability-proxy',
      skillId,
      capName,
      method,
      args,
      requestId,
    });
  });
}

export function createProxyCapabilities(
  skillId: string,
  declaredCaps: Record<string, unknown>
): Partial<BrowserCapability> {
  const caps: Partial<BrowserCapability> = {};

  if (declaredCaps.storage) {
    caps.storage = {
      get: (key: string) => proxyCall(skillId, 'storage', 'get', [key]) as Promise<string | null>,
      put: (key: string, value: string) => proxyCall(skillId, 'storage', 'put', [key, value]) as Promise<void>,
      delete: (key: string) => proxyCall(skillId, 'storage', 'delete', [key]) as Promise<void>,
    };
  }

  if (declaredCaps.network) {
    caps.network = {
      fetch: (opts: { url: string; method?: string; body?: string }) =>
        proxyCall(skillId, 'network', 'fetch', [opts]) as Promise<{ status: number; body: string }>,
    };
  }

  if (declaredCaps.ai) {
    caps.ai = {
      ask: (prompt: string) =>
        proxyCall(skillId, 'ai', 'ask', [prompt]) as Promise<{ status: number; body: string }>,
    };
  }

  if (declaredCaps.file) {
    caps.file = {
      read: (path: string) => proxyCall(skillId, 'file', 'read', [path]) as Promise<string>,
      write: (path: string, data: string) => proxyCall(skillId, 'file', 'write', [path, data]) as Promise<void>,
      list: (dir: string) => proxyCall(skillId, 'file', 'list', [dir]) as Promise<string[]>,
      delete: (path: string) => proxyCall(skillId, 'file', 'delete', [path]) as Promise<void>,
    };
  }

  if (declaredCaps.os) {
    caps.os = {
      notify: (title: string, body: string) =>
        proxyCall(skillId, 'os', 'notify', [title, body]) as Promise<void>,
    };
  }

  return caps;
}
