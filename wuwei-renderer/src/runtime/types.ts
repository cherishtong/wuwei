import type { ThreeJsAPI } from './ThreeJsCapability';

export interface BrowserCapability {
  ui: {
    set(id: string, property: string, value: unknown): void;
    get(id: string, property: string): unknown;
    flush(): void;
  };
  permission: {
    check(name: string): boolean;
    request(name: string, reason: string): Promise<boolean>;
  };
  storage?: {
    get(key: string): Promise<string | null>;
    put(key: string, value: string): Promise<void>;
    delete(key: string): Promise<void>;
  };
  network?: {
    fetch(opts: { url: string; method?: string; body?: string }): Promise<{ status: number; body: string }>;
  };
  ai?: {
    ask(prompt: string): Promise<{ status: number; body: string }>;
  };
  file?: {
    read(path: string): Promise<string>;
    write(path: string, data: string): Promise<void>;
    list(dir: string): Promise<string[]>;
    delete(path: string): Promise<void>;
  };
  os?: {
    notify(title: string, body: string): Promise<void>;
  };
  crypto?: {
    encrypt(plaintext: string, key: string): Promise<string>;
    decrypt(ciphertext: string, key: string): Promise<string>;
    hash(data: string): Promise<string>;
    deriveKey(password: string, salt: string): Promise<string>;
    randomBytes(n: number): Promise<string>;
    generatePassword(len: number): Promise<string>;
  };
  db?: {
    run(sql: string): Promise<void>;
    query(sql: string, params?: unknown[]): Promise<Record<string, unknown>[]>;
    execute(sql: string, params?: unknown[]): Promise<{ changes: number }>;
  };
  websearch?: {
    search(query: string, opts?: { limit?: number }): Promise<{ results: { title: string; url: string; snippet: string; score?: number }[]; answer: string }>;
  };
  canvas?: {
    render(canvasId: string, commands: Record<string, unknown>[]): void;
    getContext?(canvasId: string): CanvasRenderingContext2D | null;
  };
  threejs?: ThreeJsAPI;
}

export type HandlerFunction = (inputs: Record<string, unknown>, capability?: BrowserCapability) => void | Promise<void>;

export interface BrowserRuntimeState {
  skillId: string;
  handlers: Record<string, HandlerFunction>;
  declaredCaps: Record<string, unknown>;
  componentCache: Record<string, Record<string, unknown>>;
  patchBuffer: { type: string; id: string; [key: string]: unknown }[];
}
