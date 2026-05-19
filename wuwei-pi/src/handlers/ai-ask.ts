/**
 * ai/ask and ai/askStream handlers.
 * Thin wrapper over pi-ai's complete() / stream().
 */

import { getModel, complete, stream, type Model } from '@mariozechner/pi-ai';
import type { Logger } from '../logger.js';
import { sendNotification } from '../server.js';
import { buildAiAskContext } from '../prompts/wuwei.js';

export interface AiAskParams {
  skillId: string;
  prompt: string;
  model: { provider: string; model: string };
}

export interface AiAskResult {
  status: number;
  body: string;
}

export async function aiAskHandler(
  params: AiAskParams,
  logger: Logger
): Promise<AiAskResult> {
  logger.info('ai.ask 调用', { skillId: params.skillId });

  try {
    const model: Model = getModel(params.model.provider as any, params.model.model as any);
    const ctx = buildAiAskContext(params.prompt);

    const start = Date.now();
    const response = await complete(model, ctx, {
      temperature: 0.3,
      maxTokens: 2048,
    });

    const body = response.content
      .filter(b => b.type === 'text')
      .map(b => b.text)
      .join('')
      .trim();

    logger.llm({
      summary: 'ai.ask 完成',
      provider: params.model.provider,
      model: params.model.model,
      inputTokens: response.usage?.input ?? 0,
      outputTokens: response.usage?.output ?? 0,
      latencyMs: Date.now() - start,
      cost: response.usage?.cost?.total ?? 0,
    });

    return { status: 200, body };

  } catch (e: any) {
    logger.error('ai.ask 失败', { error: e.message });
    return { status: -1, body: `AI 调用失败: ${e.message}` };
  }
}

// ── Streaming ─────────────────────────────────────────────────────

export interface AiAskStreamParams {
  skillId: string;
  prompt: string;
  model: { provider: string; model: string };
}

export async function aiAskStreamHandler(
  params: AiAskStreamParams,
  logger: Logger
): Promise<{ accepted: boolean }> {
  logger.info('ai.askStream 调用', { skillId: params.skillId });

  const model: Model = getModel(params.model.provider as any, params.model.model as any);
  const ctx = buildAiAskContext(params.prompt);

  // Fire and forget — streaming continues via notifications
  (async () => {
    try {
      const s = stream(model, ctx, { temperature: 0.3, maxTokens: 2048 });
      for await (const event of s) {
        if (event.type === 'text_delta') {
          sendNotification('ai/streamToken', {
            requestId: 'current',
            token: event.delta,
            done: false,
          });
        } else if (event.type === 'done') {
          sendNotification('ai/streamToken', {
            requestId: 'current',
            token: '',
            done: true,
          });
        }
      }
    } catch (e: any) {
      logger.error('ai.askStream 失败', { error: e.message });
      sendNotification('ai/streamToken', {
        requestId: 'current',
        token: `ERROR: ${e.message}`,
        done: true,
      });
    }
  })();

  return { accepted: true };
}
