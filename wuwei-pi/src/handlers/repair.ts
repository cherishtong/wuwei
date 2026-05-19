/**
 * skill/repair handler.
 * Uses pi-ai's complete() for fast, non-streaming repair calls.
 */

import { getModel, complete, type Model } from '@mariozechner/pi-ai';
import type { Logger } from '../logger.js';
import { buildRepairContext, type SkillMemory } from '../prompts/wuwei.js';
import { parseThreeFiles, validateFilesFormat, type SkillFiles } from '../utils.js';

export interface RepairParams {
  skillId: string;
  error: string;
  files: SkillFiles;
  model: { provider: string; model: string };
  memory: SkillMemory | null;
  attempt: number;
}

export async function repairHandler(
  params: RepairParams,
  logger: Logger
): Promise<SkillFiles> {
  logger.info(`开始修复 Skill（第 ${params.attempt} 次）`, {
    skillId: params.skillId,
    error: params.error,
  });

  const model: Model = getModel(params.model.provider as any, params.model.model as any);
  const ctx = buildRepairContext({
    error: params.error,
    files: params.files,
    memory: params.memory,
    attempt: params.attempt,
  });

  const start = Date.now();
  const response = await complete(model, ctx, {
    temperature: 0.2,
    maxTokens: 4096,
  });

  const fullText = response.content
    .filter(b => b.type === 'text')
    .map(b => b.text)
    .join('');

  logger.llm({
    summary: `修复完成（第 ${params.attempt} 次）`,
    provider: params.model.provider,
    model: params.model.model,
    inputTokens: response.usage?.input ?? 0,
    outputTokens: response.usage?.output ?? 0,
    latencyMs: Date.now() - start,
    cost: response.usage?.cost?.total ?? 0,
  });

  const files = parseThreeFiles(fullText);
  validateFilesFormat(files);
  return files;
}
