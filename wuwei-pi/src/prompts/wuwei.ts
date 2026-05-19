/**
 * Wuwei-specific prompt templates.
 * PI framework handles LLM calling — we only define WHAT to ask.
 */

import type { Context } from '@mariozechner/pi-ai';

// ── System prompts ─────────────────────────────────────────────────

export const WUWEI_GENERATE_SYSTEM = `你是无为平台（Wuwei）的 Skill 生成器。你根据用户意图生成符合 Capability ABI v1.0 规范的 Skill。

## 输出格式（严格遵守，不要有任何额外文字）
=== skill.json ===
{JSON 对象: id, name, version, runtime, abi, capabilities}
=== genome/ui.json ===
{JSON 对象: 组件树}
=== genome/handlers.js ===
{JS 代码: 同步函数}
=== core-goals ===
- 目标1
- 目标2
=== design-decision ===
设计决策说明（ADR 格式，Markdown）

## Capability ABI v1.0（全部同步函数，禁止 async/await）
capability.storage.get(key: string): string | null
capability.storage.put(key: string, value: string): void
capability.network.fetch({url, method?, body?}): {status, body}
capability.ui.get(id: string, property: string): string | boolean
capability.ui.set(id: string, property: string, value): void
capability.os.notify(title: string, body: string): void
capability.ai.ask(prompt: string): {status, body}
capability.ai.askStream(prompt: string, onChunk: function, onDone: function): void

## 强制规则
1. handlers.js 所有函数为同步函数（禁止 async/await/Promise）
2. 顶层只允许 const 声明（禁止 let/var）
3. capability.ui.set/get 的第一个参数必须是字符串字面量（如 'my-button'，不能是变量）
4. 禁止使用: eval / Function() / require / import / globalThis / setTimeout / setInterval
5. 按钮事件处理函数命名规则: button id="query-btn" → 函数名 onQueryBtn
6. 只声明 handlers.js 中实际使用的 capability（skill.json 的 capabilities 字段对齐）

## 可用 A2UI 组件
container / text / input / button / list / checkbox / slider / tabs / dialog / card

## skill.json 模板
{
  "id": "kebab-case-id",
  "name": "中文名称",
  "version": "1.0.0",
  "runtime": "graaljs",
  "abi": "1.0",
  "capabilities": { "ui": {}, "storage": {} }
}`;

export const WUWEI_REPAIR_SYSTEM = `你是无为平台的 Skill 修复专家。修复编译/审计错误，只修改导致错误的部分。

规则：
1. 只修改导致错误的部分，不要改变其他逻辑
2. 保留原始意图的所有核心目标
3. 不得引入新的 Capability 依赖（除非原本就需要）
4. 保持同步函数、禁止 let/var 等所有 ABI 规则

按原格式输出三个文件（=== skill.json === 等分隔符），不要有任何额外文字。`;

export const WUWEI_DRIFT_SYSTEM = `你是无为平台的意图漂移分析器。对比原始意图和提议的变更。

输出严格 JSON（不要 markdown code fence）:
{
  "driftScore": <0-10>,
  "retainedGoals": ["保留的目标"],
  "lostGoals": ["丢失的目标"],
  "newGoals": ["新增的目标"],
  "reason": "分析原因（中文）",
  "recommendation": "allow" | "warn" | "reject"
}

评分标准:
0-3 分: 正常迭代，功能增强
4-6 分: 部分偏离原始意图，需警告
7-10 分: 严重偏离，应拒绝`;

export const WUWEI_AI_ASK_SYSTEM = `You are a data retrieval assistant embedded in the Wuwei platform.
Your response must be pure data — no markdown, no code fences, no explanations.
If asked for structured data, return valid JSON.
If asked for text, return plain text.
Keep responses concise and directly usable by a script.`;

// ── Context builders ───────────────────────────────────────────────

export interface SkillMemory {
  originalIntent: string;
  coreGoals: string[];
  recentEvolution?: { version: string; type: string; summary: string }[];
  recentDecisions?: string;
}

export interface SkillFiles {
  skillJson: string;
  uiJson: string;
  handlersJs: string;
}

function buildMemorySection(memory: SkillMemory): string {
  const parts: string[] = [
    `## Skill 记忆（必须保留以下目标）
原始意图：${memory.originalIntent}
核心目标（不得删除）：
${memory.coreGoals.map(g => `- ${g}`).join('\n')}`,
  ];

  if (memory.recentEvolution?.length) {
    parts.push(`最近变更历史：
${memory.recentEvolution.map(e => `- v${e.version}: [${e.type}] ${e.summary}`).join('\n')}`);
  }

  if (memory.recentDecisions) {
    parts.push(`设计决策（请遵守）：
${memory.recentDecisions}`);
  }

  return parts.join('\n\n');
}

function buildExistingSection(existingSkills: string[]): string {
  return `## 已有 Skill（id 不得重复）
${existingSkills.join(', ')}`;
}

function buildCurrentFilesSection(files: SkillFiles): string {
  return `## 当前 Skill 文件（请在此基础上修改，不要改变 id）
### skill.json
\`\`\`json
${files.skillJson}
\`\`\`

### genome/ui.json
\`\`\`json
${files.uiJson}
\`\`\`

### genome/handlers.js
\`\`\`js
${files.handlersJs}
\`\`\``;
}

// ── Public builders ─────────────────────────────────────────────────

export function buildGenerateContext(params: {
  intent: string;
  existingSkills: string[];
  memory: SkillMemory | null;
  currentFiles: SkillFiles | null;
}): Context {
  const sections: string[] = [];

  if (params.memory) {
    sections.push(buildMemorySection(params.memory));
  }

  if (params.existingSkills.length > 0) {
    sections.push(buildExistingSection(params.existingSkills));
  }

  if (params.currentFiles) {
    sections.push(buildCurrentFilesSection(params.currentFiles));
  }

  sections.push(`## 用户需求
${params.intent}`);

  return {
    systemPrompt: WUWEI_GENERATE_SYSTEM,
    messages: [{ role: 'user', content: sections.join('\n\n') }],
  };
}

export function buildRepairContext(params: {
  error: string;
  files: SkillFiles;
  memory: SkillMemory | null;
  attempt: number;
}): Context {
  const sections: string[] = [];

  sections.push(`这是第 ${params.attempt} 次修复尝试。`);

  if (params.memory) {
    sections.push(buildMemorySection(params.memory));
  }

  sections.push(`## 错误信息
${params.error}

## 当前 skill.json
\`\`\`json
${params.files.skillJson}
\`\`\`

## 当前 genome/ui.json
\`\`\`json
${params.files.uiJson}
\`\`\`

## 当前 genome/handlers.js
\`\`\`js
${params.files.handlersJs}
\`\`\``);

  return {
    systemPrompt: WUWEI_REPAIR_SYSTEM,
    messages: [{ role: 'user', content: sections.join('\n\n') }],
  };
}

export function buildDriftContext(params: {
  originalIntent: string;
  coreGoals: string[];
  currentHandlersJs: string;
  proposedChange: string;
}): Context {
  return {
    systemPrompt: WUWEI_DRIFT_SYSTEM,
    messages: [{
      role: 'user',
      content: `原始意图：${params.originalIntent}

核心目标（不得丢失）：
${params.coreGoals.map(g => `- ${g}`).join('\n')}

当前 handlers.js 逻辑摘要：
${params.currentHandlersJs.slice(0, 2000)}

提议的变更：
${params.proposedChange}

请分析这次变更是否偏离了原始意图。`,
    }],
  };
}

export function buildAiAskContext(prompt: string): Context {
  return {
    systemPrompt: WUWEI_AI_ASK_SYSTEM,
    messages: [{ role: 'user', content: prompt }],
  };
}
