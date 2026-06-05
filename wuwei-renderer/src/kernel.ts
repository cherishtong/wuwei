// kernel.ts — WebSocket connection to Wuwei Kernel
// Dev mode: connects to port from VITE_KERNEL_PORT (default 49200)
// Prod mode (Tauri): uses invoke('get_kernel_port') and listens for kernel events

let ws: WebSocket | null = null;
const queue: string[] = [];
const handlers: ((msg: Record<string, unknown>) => void)[] = [];
const nsHandlers: Map<string, ((msg: Record<string, unknown>) => void)[]> = new Map();
const pending = new Map<string, { resolve: (v: Record<string, unknown>) => void; reject: (e: Error) => void }>();
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let isTauri = false;

function request(msg: Record<string, unknown>, timeoutMs = 30000): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const correlationId = crypto.randomUUID();
    pending.set(correlationId, { resolve, reject });
    send({ ...msg, correlationId });
    setTimeout(() => {
      if (pending.has(correlationId)) {
        pending.delete(correlationId);
        reject(new Error(`Request timeout: ${msg.type}`));
      }
    }, timeoutMs);
  });
}

async function getPort(): Promise<number> {
  try {
    if (window.__TAURI_INTERNALS__) {
      isTauri = true;
      const { invoke } = await import('@tauri-apps/api/core');
      return await invoke<number>('get_kernel_port');
    }
  } catch {
    // not in Tauri, fall through to env var
  }
  return parseInt(import.meta.env.VITE_KERNEL_PORT ?? '49200');
}

async function connect() {
  let port: number;
  try {
    port = await getPort();
  } catch {
    // kernel not ready yet, retry in 1s
    reconnectTimer = setTimeout(connect, 1000);
    return;
  }

  ws = new WebSocket(`ws://127.0.0.1:${port}/ws`);

  ws.onopen = () => {
    console.log(`[kernel] connected to port ${port}`);
    send({ type: 'list-skills' });
    flushQueue();
  };

  ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data);
      // Resolve pending request if correlationId matches
      if (msg.correlationId && pending.has(msg.correlationId as string)) {
        pending.get(msg.correlationId as string)!.resolve(msg);
        pending.delete(msg.correlationId as string);
        return;
      }
      handlers.forEach(h => h(msg));
      // Dispatch by namespace
      const ns = msg.ns as string | undefined;
      if (ns && nsHandlers.has(ns)) {
        nsHandlers.get(ns)!.forEach(h => h(msg));
      }
    } catch (err) {
      console.warn('[kernel] failed to parse message', err);
    }
  };

  ws.onclose = () => {
    console.log('[kernel] disconnected, reconnecting in 2s...');
    ws = null;
    reconnectTimer = setTimeout(connect, 2000);
  };

  ws.onerror = () => {
    // onclose will fire after this
  };
}

async function setupTauriListeners() {
  if (!isTauri) return;
  try {
    const { listen } = await import('@tauri-apps/api/event');
    // When kernel restarts, reconnect
    await listen<number>('kernel-ready', (event) => {
      console.log('[kernel] kernel-ready event, port:', event.payload);
      // Disconnect old WS and reconnect to new port
      if (ws) {
        ws.onclose = null; // prevent auto-reconnect during switch
        ws.close();
        ws = null;
      }
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      connect();
    });
    // When kernel exits, clear connection state
    await listen('kernel-exited', () => {
      console.log('[kernel] kernel-exited event, waiting for restart...');
      if (ws) {
        ws.onclose = null;
        ws.close();
        ws = null;
      }
    });
    // kernel-event: ALL kernel messages forwarded via Rust stdout proxy
    await listen<Record<string, unknown>>('kernel-event', (event) => {
      const msg = event.payload;
      // Dispatch to namespace handlers
      const ns = msg.ns as string | undefined;
      if (ns && nsHandlers.has(ns)) {
        nsHandlers.get(ns)!.forEach(h => h(msg));
      }
      // Also dispatch to general handlers
      handlers.forEach(h => h(msg));
    });
  } catch {
    // Tauri event API not available
  }
}

function flushQueue() {
  while (queue.length && ws?.readyState === WebSocket.OPEN) {
    ws.send(queue.shift()!);
  }
}

function send(msg: Record<string, unknown>) {
  const s = JSON.stringify(msg);
  if (ws?.readyState === WebSocket.OPEN) {
    ws.send(s);
  } else {
    queue.push(s);
  }
}

export const kernel = {
  async init() {
    await setupTauriListeners();
    connect();
  },

  connect,

  send,

  onMessage(h: (msg: Record<string, unknown>) => void) {
    handlers.push(h);
  },

  /** Subscribe to messages in a specific namespace. */
  onNamespace(ns: string, h: (msg: Record<string, unknown>) => void) {
    if (!nsHandlers.has(ns)) {
      nsHandlers.set(ns, []);
    }
    nsHandlers.get(ns)!.push(h);
    return () => {
      const arr = nsHandlers.get(ns);
      if (arr) {
        const idx = arr.indexOf(h);
        if (idx >= 0) arr.splice(idx, 1);
      }
    };
  },

  /** Subscribe to conversation-update messages (ns:conv). */
  onConversationUpdate(fn: (msg: Record<string, unknown>) => void) {
    return this.onNamespace('conv', (msg) => {
      if (msg.type === 'conversation-update') fn(msg);
    });
  },

  /** Subscribe to A2UI events (ns:ui). */
  onUiEvent(fn: (msg: Record<string, unknown>) => void) {
    return this.onNamespace('ui', fn);
  },

  sendIntent(text: string, threadId?: string, modelOverride?: { provider?: string; model?: string; apiKey?: string; apiUrl?: string }) {
    send({ type: 'user-intent', threadId: threadId || null, payload: { text, ...modelOverride } });
  },

  refineSkill(skillId: string, feedback: string, threadId?: string, modelOverride?: { provider?: string; model?: string; apiKey?: string; apiUrl?: string }) {
    send({ type: 'refine-skill', skillId, threadId: threadId || null, payload: { feedback, ...modelOverride } });
  },

  handleEvent(skillId: string, eventId: string, inputs: Record<string, unknown>) {
    send({ type: 'handle-event', skillId, eventId, inputs });
  },

  confirmGate(skillId: string, capName: string, approved: boolean) {
    send({ type: 'confirm-gate', skillId, capName, approved });
  },
  getMetrics() {
    send({ type: 'get-metrics' });
  },
  listLogDates(source: string) {
    send({ type: 'list-logs', source });
  },
  getLog(source: string, date: string) {
    send({ type: 'get-log', source, date });
  },
  sendRenderLog(level: string, message: string) {
    send({ type: 'render-log', level, message });
  },

  activateSkill(skillId: string, threadId?: string) {
    send({ type: 'activate-skill', skillId, threadId: threadId || null });
  },

  deactivateSkill(skillId: string, threadId?: string) {
    send({ type: 'deactivate-skill', skillId, threadId: threadId || null });
  },

  listSkills() {
    send({ type: 'list-skills' });
  },

  setRateLimit(enabled: boolean) {
    send({ type: 'set-rate-limit', enabled });
  },

  getRateLimit() {
    send({ type: 'get-rate-limit' });
  },

  listModelRouting() {
    send({ type: 'list-model-routing' });
  },

  setModelRouting(taskType: string, provider: string, model: string, apiUrl?: string, apiKey?: string, params?: string) {
    send({ type: 'set-model-routing', taskType, provider, model, apiUrl: apiUrl || '', apiKey: apiKey || '', params: params || '{}' });
  },

  deleteModelRouting(taskType: string) {
    send({ type: 'delete-model-routing', taskType });
  },

  // ── Conversation persistence (kernel-backed SQLite) ──

  async createConversation(skillId?: string, skillName?: string) {
    const resp = await request({ type: 'create-conversation', skillId: skillId || '', skillName: skillName || '' });
    return resp.conversation as Record<string, unknown>;
  },

  async findOrCreateConversation(skillId?: string, skillName?: string) {
    const resp = await request({ type: 'find-or-create-conversation', skillId: skillId || '', skillName: skillName || '' });
    return resp.conversation as Record<string, unknown>;
  },

  async listConversations() {
    const resp = await request({ type: 'list-conversations' });
    return resp.conversations as Array<Record<string, unknown>>;
  },

  async getConversation(convId: string) {
    const resp = await request({ type: 'get-conversation', convId });
    return resp.conversation as Record<string, unknown>;
  },

  async deleteConversation(convId: string) {
    await request({ type: 'delete-conversation', convId });
  },

  async updateConversationTitle(convId: string, title: string) {
    await request({ type: 'update-conversation-title', convId, title });
  },

  async getHomeConversation() {
    const resp = await request({ type: 'get-home-conversation' });
    return resp.conversation as Record<string, unknown>;
  },

  async setThreadActiveSkill(convId: string, skillId: string | null) {
    await request({ type: 'set-thread-active-skill', convId, skillId: skillId || '' });
  },

  async getThread(convId: string) {
    const resp = await request({ type: 'get-thread', convId });
    return resp.thread as Record<string, unknown>;
  },

  skillHandoff(fromSkillId: string, toSkillId: string, threadId: string, context?: Record<string, unknown>) {
    send({ type: 'skill-handoff', fromSkillId, toSkillId, threadId, context: context || {} });
  },

  deleteMessage(threadId: string, messageId: string) {
    send({ type: 'delete-message', threadId, messageId });
  },
};
