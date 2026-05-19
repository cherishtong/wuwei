/**
 * skill/generate handler.
 * Uses pi-ai's stream() for real-time progress, parseThreeFiles for output.
 */

import { getModel, stream, type Model, type AssistantMessageEventStream } from '@mariozechner/pi-ai';
import type { Logger } from '../logger.js';
import { sendNotification } from '../server.js';
import { buildGenerateContext, type SkillMemory, type SkillFiles } from '../prompts/wuwei.js';
import { parseThreeFiles, validateFilesFormat, extractCoreGoals, extractDesignDecision } from '../utils.js';

export interface GenerateParams {
  intent: string;
  existingSkills: string[];
  model: { provider: string; model: string };
  memory: SkillMemory | null;
  currentFiles: SkillFiles | null;
}

export interface GenerateResult {
  skillJson: string;
  uiJson: string;
  handlersJs: string;
  memoryDelta: MemoryDelta | null;
}

export interface MemoryDelta {
  newCoreGoals: string[];
  designDecision: string;
}

export async function generateHandler(
  params: GenerateParams,
  logger: Logger
): Promise<GenerateResult> {
  logger.info('开始生成 Skill', { intent: params.intent });

  sendNotification('skill/progress', {
    requestId: 'current', step: 'preparing',
    message: '准备生成 Skill...', percent: 5,
  });

  const model: Model = getModel(params.model.provider as any, params.model.model as any);
  const ctx = buildGenerateContext({
    intent: params.intent,
    existingSkills: params.existingSkills,
    memory: params.memory,
    currentFiles: params.currentFiles,
  });

  sendNotification('skill/progress', {
    requestId: 'current', step: 'generating',
    message: `调用 ${params.model.provider}/${params.model.model}...`, percent: 15,
  });

  const start = Date.now();
  const s: AssistantMessageEventStream = stream(model, ctx, {
    temperature: 0.3,
    maxTokens: 4096,
  });

  let fullText = '';

  for await (const event of s) {
    switch (event.type) {
      case 'text_delta':
        fullText += event.delta;
        // Stage detection for progress
        if (fullText.includes('=== genome/ui.json ===') &&
            !fullText.includes('=== genome/handlers.js ===')) {
          sendNotification('skill/progress', {
            requestId: 'current', step: 'generating_ui',
            message: '正在生成界面...', percent: 40,
          });
        } else if (fullText.includes('=== genome/handlers.js ===') &&
                   !fullText.includes('=== core-goals ===')) {
          sendNotification('skill/progress', {
            requestId: 'current', step: 'generating_logic',
            message: '正在生成业务逻辑...', percent: 70,
          });
        }
        break;

      case 'done': {
        const usage = event.message.usage;
        logger.llm({
          summary: `${params.model.model} 生成完成`,
          provider: params.model.provider,
          model: params.model.model,
          inputTokens: usage?.input ?? 0,
          outputTokens: usage?.output ?? 0,
          latencyMs: Date.now() - start,
          cost: usage?.cost?.total ?? 0,
        });
        break;
      }

      case 'error':
        throw new Error(event.error?.errorMessage ?? 'LLM 调用失败');
    }
  }

  sendNotification('skill/progress', {
    requestId: 'current', step: 'parsing',
    message: '解析生成结果...', percent: 90,
  });

  const files = parseThreeFiles(fullText);
  validateFilesFormat(files);

  const memoryDelta: MemoryDelta | null = params.memory == null ? {
    newCoreGoals: extractCoreGoals(fullText),
    designDecision: extractDesignDecision(fullText),
  } : null;

  sendNotification('skill/progress', {
    requestId: 'current', step: 'done',
    message: 'Skill 生成完成', percent: 100,
  });

  logger.info('Skill 生成完成');

  return { ...files, memoryDelta };
}
