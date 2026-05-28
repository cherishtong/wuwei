// ConsoleStore.ts — ring-buffer for program logs (ns:log)
// Consumed by WwTerminal for debug/audit views

export interface LogEntry {
  time: string;
  level: string;
  type: string;
  message: string;
  data?: Record<string, unknown>;
}

type ConsoleListener = () => void;

const MAX_LOGS = 500;
const logs: LogEntry[] = [];
const listeners: ConsoleListener[] = [];

function notify() {
  listeners.forEach((fn) => fn());
}

function timeStr(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export const consoleStore = {
  dispatch(msg: Record<string, unknown>) {
    const type = msg.type as string;
    let entry: LogEntry | null = null;

    switch (type) {
      case 'plan-step':
        entry = { time: timeStr(), level: 'info', type, message: `${msg.status}: ${msg.desc || ''}` };
        break;
      case 'repair-attempt':
        entry = { time: timeStr(), level: 'warn', type, message: `[${msg.skillId}] 修复 #${msg.attempt}: ${msg.error || ''}` };
        break;
      case 'skill-log':
        entry = { time: timeStr(), level: (msg.level as string) || 'info', type, message: `[${msg.skillId}] ${msg.message || ''}` };
        break;
      case 'pi-log':
        entry = { time: timeStr(), level: (msg.level as string) || 'info', type, message: msg.message as string, data: msg.data ? JSON.parse(msg.data as string) : undefined };
        break;
    }

    if (entry) {
      logs.push(entry);
      if (logs.length > MAX_LOGS) logs.shift();
      notify();
    }
  },

  onChange(fn: ConsoleListener) {
    listeners.push(fn);
    return () => {
      const idx = listeners.indexOf(fn);
      if (idx >= 0) listeners.splice(idx, 1);
    };
  },

  getLogs(): LogEntry[] {
    return logs;
  },

  clear() {
    logs.length = 0;
    notify();
  },
};
