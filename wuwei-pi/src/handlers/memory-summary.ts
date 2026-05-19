/**
 * skill/summarizeMemory handler.
 * Uses pi-ai's complete() to compress evolution logs.
 */

import { getModel, complete, type Model } from '@mariozechner/pi-ai';
import type { Logger } from '../logger.js';

export interface MemorySummaryParams {
  skillId: string;
  evolutionLog: string;
  maxLength: number;
  model: { provider: string; model: string };
}

export interface MemorySummaryResult {
  summary: string;
}

export async function memorySummaryHandler(
  params: MemorySummaryParams,
  logger: Logger
): Promise<MemorySummaryResult> {
  logger.info('Memory 摘要请求', { skillId: params.skillId });

  const model: Model = getModel(params.model.provider as any, params.model.model as any);

  const response = await complete(model, {
    messages: [{
      role: 'user',
      content: `将以下 Skill 进化日志压缩为不超过 ${params.maxLength} 字符的摘要。保留关键决策和变更历史。

进化日志：
${params.evolutionLog}`,
    }],
  }, {
    temperature: 0.1,
    maxTokens: Math.ceil(params.maxLength / 3),
  });

  const summary = response.content
    .filter(b => b.type === 'text')
    .map(b => b.text)
    .join('');

  return {
    summary: summary.length > params.maxLength
      ? summary.slice(0, params.maxLength - 3) + '...'
      : summary,
  };
}
