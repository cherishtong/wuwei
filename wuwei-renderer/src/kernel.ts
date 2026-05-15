// kernel.ts — WebSocket connection to Wuwei Kernel
// Dev mode: connects to port from VITE_KERNEL_PORT (default 49200)
// Prod mode (Tauri): uses invoke('get_kernel_port') and listens for kernel events

let ws: WebSocket | null = null;
const queue: string[] = [];
const handlers: ((msg: Record<string, unknown>) => void)[] = [];
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let isTauri = false;

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
      handlers.forEach(h => h(msg));
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

  sendIntent(text: string) {
    send({ type: 'user-intent', payload: { text } });
  },

  refineSkill(skillId: string, feedback: string) {
    send({ type: 'refine-skill', skillId, payload: { feedback } });
  },

  handleEvent(skillId: string, eventId: string, inputs: Record<string, unknown>) {
    send({ type: 'handle-event', skillId, eventId, inputs });
  },

  confirmGate(skillId: string, capName: string, approved: boolean) {
    send({ type: 'confirm-gate', skillId, capName, approved });
  },

  activateSkill(skillId: string) {
    send({ type: 'activate-skill', skillId });
  },

  deactivateSkill(skillId: string) {
    send({ type: 'deactivate-skill', skillId });
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
};
