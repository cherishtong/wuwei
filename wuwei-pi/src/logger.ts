/**
 * Logger that makes pi/log JSON-RPC notifications.
 * Intercepts console.* to prevent stdout pollution on the JSON-RPC channel.
 */

export interface LlmLogEvent {
  summary: string;
  provider: string;
  model: string;
  inputTokens: number;
  outputTokens: number;
  latencyMs: number;
  cost?: number;
}

export interface Logger {
  info: (msg: string, data?: Record<string, unknown>) => void;
  warn: (msg: string, data?: Record<string, unknown>) => void;
  error: (msg: string, data?: Record<string, unknown>) => void;
  debug: (msg: string, data?: Record<string, unknown>) => void;
  llm: (event: LlmLogEvent) => void;
}

export function createLogger(
  sendNotification: (method: string, params: Record<string, unknown>) => void
): Logger {
  function emit(level: string, message: string, data?: Record<string, unknown>) {
    sendNotification('pi/log', {
      level,
      message,
      data: data ?? null,
      timestamp: Date.now(),
    });
  }

  const logger: Logger = {
    info:  (msg, data) => emit('info', msg, data),
    warn:  (msg, data) => emit('warn', msg, data),
    error: (msg, data) => emit('error', msg, data),
    debug: (msg, data) => emit('debug', msg, data),
    llm:   (event) => emit('llm', event.summary, event as unknown as Record<string, unknown>),
  };

  // Redirect console to pi/log so accidental console.log doesn't break stdio
  console.log   = (m, ...a) => logger.info(String(m), a.length ? { args: a.map(String) } : undefined);
  console.warn  = (m, ...a) => logger.warn(String(m), a.length ? { args: a.map(String) } : undefined);
  console.error = (m, ...a) => logger.error(String(m), a.length ? { args: a.map(String) } : undefined);
  console.debug = (m, ...a) => logger.debug(String(m), a.length ? { args: a.map(String) } : undefined);

  return logger;
}
