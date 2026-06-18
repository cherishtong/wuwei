/** Config passed by a skill when opening the AI modal. */
export interface AiModalOptions {
  /** Dialog title (optional, defaults to "AI 助手") */
  title?: string;
  /** Session identifier for multi-turn history (auto-generated if omitted) */
  memoryId?: string;
  /** The initial prompt / user message */
  message: string;
  /** System prompt selector: "resume-optimize" | "general" | ... */
  agentType?: string;
  /** Arbitrary JSON context passed to the backend for prompt building */
  context?: Record<string, unknown>;
  /** Called when user clicks "确认" with the full assistant response text */
  onResult?: (fullText: string) => void;
  /** Called on error or when user closes without accepting */
  onError?: (error: string) => void;
}

/** Internal message type for the SSE runtime. */
export interface SSEChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  status?:
    | { type: 'running' }
    | { type: 'complete'; reason: 'stop' }
    | { type: 'incomplete'; reason: 'error' | 'cancelled'; error?: string };
  createdAt?: Date;
}

/** Public API exposed on window.__wuwei_ai_modal */
export interface AiModalAPI {
  open(opts: AiModalOptions): void;
  cancel(memoryId: string): void;
}
