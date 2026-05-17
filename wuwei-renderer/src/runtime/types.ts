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
  canvas?: {
    render(canvasId: string, commands: Record<string, unknown>[]): void;
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
