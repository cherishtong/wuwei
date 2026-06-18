import { useCallback, useEffect, useRef, useState } from 'react';
import { useExternalStoreRuntime } from '@assistant-ui/react';
import type { SSEChatMessage, AiModalOptions } from './types';

// ── Helpers ─────────────────────────────────────────────────────

let nextId = 0;
function uid(): string {
  return `msg-${++nextId}-${Math.random().toString(36).slice(2, 6)}`;
}

function extractText(content: unknown): string {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .filter((p): p is { type: 'text'; text: string } => p?.type === 'text')
      .map((p) => p.text)
      .join('');
  }
  return String(content ?? '');
}

function buildSSEUrl(memoryId: string, message: string, agentType?: string, context?: Record<string, unknown>): string {
  const params = new URLSearchParams({ message, agentType: agentType || 'general' });
  if (context) params.set('context', JSON.stringify(context));
  return `/api/ai/chat/${encodeURIComponent(memoryId)}?${params}`;
}

// ── Hook ────────────────────────────────────────────────────────

interface UseSSEChatRuntimeResult {
  runtime: ReturnType<typeof useExternalStoreRuntime<SSEChatMessage>>;
  messages: SSEChatMessage[];
  isRunning: boolean;
  /** Accept the current assistant response → calls onResult */
  accept: () => void;
  /** Retry the last user message */
  retry: () => void;
  /** Cancel and close */
  cancel: () => void;
}

export function useSSEChatRuntime(opts: AiModalOptions): UseSSEChatRuntimeResult {
  const { memoryId = '', message: initialMessage, agentType, context, onResult, onError } = opts;

  const [messages, setMessages] = useState<SSEChatMessage[]>([]);
  const [isRunning, setIsRunning] = useState(false);
  const esRef = useRef<EventSource | null>(null);
  const bufferRef = useRef(''); // accumulated assistant text for accept()

  // Track if we've auto-started the initial message
  const startedRef = useRef(false);

  const closeES = useCallback(() => {
    if (esRef.current) {
      esRef.current.close();
      esRef.current = null;
    }
  }, []);

  /** Start SSE streaming for a given user text. */
  const startStream = useCallback(
    (text: string) => {
      if (!text.trim() || isRunning) return;

      closeES();
      setIsRunning(true);
      bufferRef.current = '';

      const userMsg: SSEChatMessage = {
        id: uid(),
        role: 'user',
        content: text,
        createdAt: new Date(),
      };
      const assistantMsg: SSEChatMessage = {
        id: uid(),
        role: 'assistant',
        content: '',
        status: { type: 'running' },
        createdAt: new Date(),
      };

      setMessages((prev) => [...prev, userMsg, assistantMsg]);

      const url = buildSSEUrl(memoryId, text, agentType, context);
      const es = new EventSource(url);
      esRef.current = es;

      // Token chunks
      let chunkCount = 0;
      es.onmessage = (e) => {
        chunkCount++;
        if (chunkCount <= 5 || chunkCount % 20 === 0) {
          console.debug('[AiModal] chunk', chunkCount, 'size:', e.data.length, 'chars');
        }
        bufferRef.current += e.data;
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.role === 'assistant') {
            updated[updated.length - 1] = {
              ...last,
              content: bufferRef.current,
            };
          }
          return updated;
        });
      };

      // Completion
      es.addEventListener('done', (e: Event) => {
        closeES();
        setIsRunning(false);
        const fullText = (e as MessageEvent).data || bufferRef.current;
        bufferRef.current = fullText;
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.role === 'assistant') {
            updated[updated.length - 1] = {
              ...last,
              content: fullText,
              status: { type: 'complete', reason: 'stop' },
            };
          }
          return updated;
        });
      });

      // Error
      es.addEventListener('error', (e: Event) => {
        closeES();
        setIsRunning(false);
        const errText = (e as MessageEvent)?.data || 'SSE error';
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.role === 'assistant') {
            updated[updated.length - 1] = {
              ...last,
              status: { type: 'incomplete', reason: 'error', error: errText },
            };
          }
          return updated;
        });
        onError?.(typeof errText === 'string' ? errText : 'Unknown error');
      });

      // Connection lost (EventSource auto-reconnect, but we handle explicit close)
      es.onerror = () => {
        if (es.readyState === EventSource.CLOSED) {
          // If we didn't already close it ourselves, treat as error
          if (esRef.current === es) {
            closeES();
            setIsRunning(false);
            setMessages((prev) => {
              const updated = [...prev];
              const last = updated[updated.length - 1];
              if (last?.role === 'assistant' && last.status?.type === 'running') {
                updated[updated.length - 1] = {
                  ...last,
                  status: { type: 'incomplete', reason: 'error', error: 'Connection lost' },
                };
              }
              return updated;
            });
            onError?.('Connection lost');
          }
        }
      };
    },
    [memoryId, agentType, context, isRunning, closeES, onError],
  );

  // Auto-start the initial message on mount
  useEffect(() => {
    if (!startedRef.current && initialMessage) {
      startedRef.current = true;
      startStream(initialMessage);
    }
    return () => closeES(); // cleanup on unmount
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Accept
  const accept = useCallback(() => {
    const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');
    if (lastAssistant) {
      onResult?.(lastAssistant.content);
    }
  }, [messages, onResult]);

  // Retry: re-send last user message
  const retry = useCallback(() => {
    const lastUser = [...messages].reverse().find((m) => m.role === 'user');
    if (lastUser) {
      // Remove messages starting from the last user message
      const idx = messages.findIndex((m) => m.id === lastUser.id);
      setMessages((prev) => prev.slice(0, idx));
      startStream(lastUser.content);
    }
  }, [messages, startStream]);

  // Cancel
  const cancel = useCallback(() => {
    closeES();
    setIsRunning(false);
    if (memoryId) {
      fetch(`/api/ai/chat/${encodeURIComponent(memoryId)}`, { method: 'DELETE' }).catch(() => {});
    }
  }, [memoryId, closeES]);

  // ── useExternalStoreRuntime integration ───────────────────────
  const runtime = useExternalStoreRuntime<SSEChatMessage>({
    messages,
    isRunning,
    convertMessage: (msg) => ({
      role: msg.role as 'user' | 'assistant',
      id: msg.id,
      content: [{ type: 'text' as const, text: msg.content }],
      status: msg.status ?? (msg.role === 'assistant' ? { type: 'complete' as const, reason: 'stop' as const } : undefined),
      createdAt: msg.createdAt,
    }),
    onNew: async (appendedMsg) => {
      const text = extractText(appendedMsg.content);
      if (!text.trim()) return;
      startStream(text);
    },
    onCancel: async () => { cancel(); },
    onReload: async (_parentId: string | null, _config: unknown) => { retry(); },
  });

  return { runtime, messages, isRunning, accept, retry, cancel };
}
