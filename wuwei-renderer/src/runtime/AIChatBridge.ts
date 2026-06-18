/**
 * @deprecated Use AiChatModal (window.__wuwei_ai_modal) instead.
 * This module is kept for reference only.
 * See: components/AiChatModal/aiModalBridge.ts
 *
 * AIChatBridge — global SSE-based AI chat API for all skills.
 *
 * Usage from any skill handler:
 *   window.__wuwei_ai_chat.open({
 *     memoryId: 'resume-builder-xxx',
 *     agentType: 'resume-optimize',
 *     message: '优化简历，突出AI经验',
 *     context: { data: _defaultData, mapping: _mapping },
 *     onToken: (text) => { ... },
 *     onDone: (result) => { _defaultData = result; ... },
 *     onError: (err) => { ... },
 *   });
 *
 * Events go through the existing ConversationStore so they show up in
 * the chat panel automatically — no new UI component needed.
 */

interface AIChatOptions {
  memoryId: string;
  agentType?: string;
  message: string;
  context?: Record<string, unknown>;
  onToken?: (text: string) => void;
  onDone?: (result: string) => void;
  onError?: (error: string) => void;
}

interface AIChatAPI {
  open(opts: AIChatOptions): void;
  cancel(memoryId: string): Promise<void>;
}

function createAIChatBridge(): AIChatAPI {
  const activeStreams = new Map<string, EventSource>();

  async function cancel(memoryId: string) {
    const es = activeStreams.get(memoryId);
    if (es) { es.close(); activeStreams.delete(memoryId); }
    await fetch(`/api/ai/chat/${encodeURIComponent(memoryId)}`, { method: 'DELETE' });
  }

  function open(opts: AIChatOptions) {
    const { memoryId, agentType = 'general', message, context, onToken, onDone, onError } = opts;

    // Cancel any existing stream for this memoryId
    cancel(memoryId);

    // Build SSE URL
    const params = new URLSearchParams({ message, agentType });
    if (context) params.set('context', JSON.stringify(context));
    const url = `/api/ai/chat/${encodeURIComponent(memoryId)}?${params}`;

    // Open SSE connection
    const es = new EventSource(url);
    activeStreams.set(memoryId, es);

    let fullText = '';

    // Default event: token chunks
    es.onmessage = (e) => {
      fullText += e.data;
      onToken?.(e.data);
    };

    // 'done' event: generation complete
    es.addEventListener('done', (e: MessageEvent) => {
      es.close();
      activeStreams.delete(memoryId);
      onDone?.(e.data || fullText);
    });

    // 'error' event
    es.addEventListener('error', (e: MessageEvent) => {
      es.close();
      activeStreams.delete(memoryId);
      const msg = e?.data || 'SSE connection failed';
      onError?.(typeof msg === 'string' ? msg : 'Unknown error');
    });

    // Handle connection errors
    es.onerror = () => {
      // EventSource will auto-reconnect unless we close it
      // We only keep it open for the first error
      if (es.readyState === EventSource.CLOSED) {
        activeStreams.delete(memoryId);
        onError?.('Connection lost');
      }
    };
  }

  return { open, cancel };
}

// Install global API
export function initAIChatBridge() {
  (window as unknown as Record<string, unknown>).__wuwei_ai_chat = createAIChatBridge();
}
