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
      reject(new Error(`Timeout: ${capName}.${method} after 120s`));
    }, 120000);
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

  if (declaredCaps.crypto) {
    caps.crypto = {
      encrypt: (plaintext: string, key: string) =>
        proxyCall(skillId, 'crypto', 'encrypt', [plaintext, key]) as Promise<string>,
      decrypt: (ciphertext: string, key: string) =>
        proxyCall(skillId, 'crypto', 'decrypt', [ciphertext, key]) as Promise<string>,
      hash: (data: string) =>
        proxyCall(skillId, 'crypto', 'hash', [data]) as Promise<string>,
      deriveKey: (password: string, salt: string) =>
        proxyCall(skillId, 'crypto', 'deriveKey', [password, salt]) as Promise<string>,
      randomBytes: (n: number) =>
        proxyCall(skillId, 'crypto', 'randomBytes', [n]) as Promise<string>,
      generatePassword: (len: number) =>
        proxyCall(skillId, 'crypto', 'generatePassword', [len]) as Promise<string>,
    };
  }

  if (declaredCaps.db || declaredCaps.database) {
    caps.db = {
      run: (sql: string) =>
        proxyCall(skillId, 'db', 'run', [sql]) as Promise<void>,
      query: (sql: string, params?: unknown[]) =>
        proxyCall(skillId, 'db', 'query', [sql, params]) as Promise<Record<string, unknown>[]>,
      execute: (sql: string, params?: unknown[]) =>
        proxyCall(skillId, 'db', 'execute', [sql, params]) as Promise<{ changes: number }>,
    };
  }

  if (declaredCaps.websearch) {
    caps.websearch = {
      search: (query: string, opts?: { limit?: number }) =>
        proxyCall(skillId, 'websearch', 'search', [query, opts?.limit ?? 5]) as Promise<{
          results: { title: string; url: string; snippet: string; score?: number }[];
          answer: string;
        }>,
    };
  }

  if (declaredCaps.resume) {
    caps.resume = {
      list: () => proxyCall(skillId, 'resume', 'list', []) as Promise<any[]>,
      read: (name: string) => proxyCall(skillId, 'resume', 'read', [name]) as Promise<string>,
      upload: (name: string, content: string) => proxyCall(skillId, 'resume', 'upload', [name, content]) as Promise<any>,
      delete: (name: string) => proxyCall(skillId, 'resume', 'delete', [name]) as Promise<any>,
      parse: (name: string) => proxyCall(skillId, 'resume', 'parse', [name]) as Promise<any>,
      dataList: () => proxyCall(skillId, 'resume', 'dataList', []) as Promise<any[]>,
      dataLoad: (name: string) => proxyCall(skillId, 'resume', 'dataLoad', [name]) as Promise<any>,
      dataSave: (name: string, dataJson: string, mappingJson?: string) => proxyCall(skillId, 'resume', 'dataSave', [name, dataJson, mappingJson ?? null]) as Promise<any>,
      dataDelete: (name: string) => proxyCall(skillId, 'resume', 'dataDelete', [name]) as Promise<any>,
      optimize: (dataJson: string, mappingJson: string, suggestion?: string) => proxyCall(skillId, 'resume', 'optimize', [dataJson, mappingJson, suggestion ?? '']) as Promise<string>,
      generateMapping: (templateName: string, dataJson: string) => proxyCall(skillId, 'resume', 'generateMapping', [templateName, dataJson]) as Promise<string>,
      render: (templateId: string, data: Record<string, unknown>) => proxyCall(skillId, 'resume', 'render', [templateId, data]) as Promise<string>,
    };
  }

  return caps;
}
