/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_KERNEL_PORT: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

interface Window {
  __TAURI_INTERNALS__?: unknown;
}
