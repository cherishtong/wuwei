/**
 * Wuwei Pi-mono JSON-RPC 2.0 Server.
 *
 * Reads stdin line-by-line, dispatches to handlers, writes responses to stdout.
 * PI framework (@mariozechner/pi-ai) handles all LLM calling, model routing,
 * token counting, and cost tracking. This server only does transport + dispatch.
 * Pi holds no state — model config and memory are carried in each request.
 */

import { generateHandler } from './handlers/generate.js';
import { repairHandler } from './handlers/repair.js';
import { driftHandler } from './handlers/drift.js';
import { memorySummaryHandler } from './handlers/memory-summary.js';
import { aiAskHandler, aiAskStreamHandler } from './handlers/ai-ask.js';
import { createLogger, type Logger } from './logger.js';

const enc = new TextEncoder();

type Handler = (params: any, logger: Logger) => Promise<any>;

const handlers: Record<string, Handler> = {
  'skill/generate':        generateHandler,
  'skill/repair':          repairHandler,
  'skill/analyzeDrift':    driftHandler,
  'skill/summarizeMemory': memorySummaryHandler,
  'ai/ask':                aiAskHandler,
  'ai/askStream':          aiAskStreamHandler,
};

// ── Output helpers ─────────────────────────────────────────────────

function send(obj: Record<string, unknown>) {
  Bun.stdout.write(enc.encode(JSON.stringify(obj) + '\n'));
}

export function sendNotification(method: string, params: Record<string, unknown>) {
  send({ jsonrpc: '2.0', method, params });
}

// ── Read loop ─────────────────────────────────────────────────────

const logger = createLogger(sendNotification);
const decoder = new TextDecoder();

logger.info('Pi-mono RPC server starting...');

const stream = Bun.stdin.stream();
const reader = stream.getReader();
let buffer = '';

try {
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (!value) continue;
    buffer += decoder.decode(value, { stream: true });

    let newline = buffer.indexOf('\n');
    while (newline !== -1) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);

      if (line) {
        try {
          const msg = JSON.parse(line);

          if (msg.id !== undefined && msg.id !== null && msg.method) {
            const handler = handlers[msg.method];
            if (!handler) {
              send({
                jsonrpc: '2.0', id: msg.id,
                error: { code: -32601, message: `Method not found: ${msg.method}` },
              });
            } else {
              try {
                const result = await handler(msg.params, logger);
                send({ jsonrpc: '2.0', id: msg.id, result });
              } catch (e: any) {
                logger.error(`Handler error [${msg.method}]: ${e.message}`);
                send({
                  jsonrpc: '2.0', id: msg.id,
                  error: { code: -32001, message: e.message },
                });
              }
            }
          } else {
            // Invalid
            send({
              jsonrpc: '2.0', id: msg.id ?? null,
              error: { code: -32600, message: 'Invalid Request' },
            });
          }
        } catch {
          send({
            jsonrpc: '2.0', id: null,
            error: { code: -32700, message: 'Parse error' },
          });
        }
      }

      newline = buffer.indexOf('\n');
    }
  }
} finally {
  logger.info('Pi-mono RPC server shutting down');
}
