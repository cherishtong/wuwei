/**
 * skill/analyzeDrift handler.
 * Uses pi-ai's complete() for semantic drift analysis.
 */

import { getModel, complete, type Model } from '@mariozechner/pi-ai';
import type { Logger } from '../logger.js';
import { buildDriftContext } from '../prompts/wuwei.js';

export interface DriftParams {
  skillId: string;
  originalIntent: string;
  coreGoals: string[];
  currentHandlersJs: string;
  proposedChange: string;
  model: { provider: string; model: string };
}

export interface DriftResult {
  driftScore: number;
  retainedGoals: string[];
  lostGoals: string[];
  newGoals: string[];
  reason: string;
  recommendation: 'allow' | 'warn' | 'reject';
}

export async function driftHandler(
  params: DriftParams,
  logger: Logger
): Promise<DriftResult> {
  logger.info('开始分析意图漂移', { skillId: params.skillId });

  const model: Model = getModel(params.model.provider as any, params.model.model as any);
  const ctx = buildDriftContext({
    originalIntent: params.originalIntent,
    coreGoals: params.coreGoals,
    currentHandlersJs: params.currentHandlersJs,
    proposedChange: params.proposedChange,
  });

  const start = Date.now();
  const response = await complete(model, ctx, {
    temperature: 0.1,
    maxTokens: 1024,
  });

  const text = response.content
    .filter(b => b.type === 'text')
    .map(b => b.text)
    .join('');

  logger.llm({
    summary: '漂移分析完成',
    provider: params.model.provider,
    model: params.model.model,
    inputTokens: response.usage?.input ?? 0,
    outputTokens: response.usage?.output ?? 0,
    latencyMs: Date.now() - start,
    cost: response.usage?.cost?.total ?? 0,
  });

  try {
    const parsed = JSON.parse(text.trim());
    return {
      driftScore: Math.min(Math.max(parsed.driftScore ?? 5, 0), 10),
      retainedGoals: parsed.retainedGoals ?? [],
      lostGoals: parsed.lostGoals ?? [],
      newGoals: parsed.newGoals ?? [],
      reason: parsed.reason ?? '无法分析',
      recommendation: parsed.recommendation ?? 'warn',
    };
  } catch {
    // Fallback: heuristic if LLM response wasn't valid JSON
    logger.warn('漂移分析 JSON 解析失败，使用启发式', { raw: text.slice(0, 200) });
    const retained = params.coreGoals.filter(g =>
      params.proposedChange.includes(g.slice(0, 4)));
    const lost = params.coreGoals.filter(g => !retained.includes(g));
    const score = lost.length * 2.5;
    return {
      driftScore: Math.min(score, 10),
      retainedGoals: retained,
      lostGoals: lost,
      newGoals: [],
      reason: lost.length > 0 ? `可能丢失: ${lost.join(', ')}` : '目标完整',
      recommendation: score > 6 ? 'warn' : 'allow',
    };
  }
}
