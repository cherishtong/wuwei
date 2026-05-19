# 无为 Wuwei × Pi AI 集成方案 v2

> Pi 框架（`@mariozechner/pi-ai`）提供统一 LLM API（20+ 厂商）、token 计费、流式事件、跨模型切换。
> Wuwei-pi 在 Pi 之上只做三件事：JSON-RPC 传输、Wuwei 专属 Prompt 模板、三文件解析。
> Java Kernel 负责所有运行时事务。JSON-RPC 2.0 over stdio 通信。

---

## 一、PI 框架提供的能力（不造轮子）

```
@mariozechner/pi-ai 已内置           Wuwei-pi 只需关注
─────────────────────────────        ─────────────────────
getModel(provider, modelId)          JSON-RPC 传输层（server.ts）
  → 20+ 厂商，IDE 类型补全
complete(model, context)             Wuwei Prompt 模板
  → 非流式调用，自动 token 计费        → Capability ABI 规范
stream(model, context)               → Memory 注入逻辑
  → 流式事件：text_delta, done...     → 三文件输出格式
  → 自动 token 计费 + 费用计算
                                     三文件解析（utils.ts）
API key 自动发现                        → === 分隔符解析
  → OPENAI_API_KEY 等环境变量          → JSON/JS 格式校验
  → 或显式传 apiKey
                                     pi/log 日志透传（logger.ts）
跨 provider 无缝切换                     → LLM 调用事件 → Java → 前端
  → Claude → GPT → Gemini 同一会话

Faux provider（测试用）
  → registerFauxProvider() 无需 API key
```

---

## 二、精简后的项目结构

```
wuwei-pi/src/
├── server.ts              # JSON-RPC 2.0 over stdio（唯一入口）
├── handlers/
│   ├── generate.ts        # skill/generate — 构建 Prompt + complete()
│   ├── repair.ts          # skill/repair — 同上
│   ├── drift.ts           # skill/analyzeDrift
│   └── ai-ask.ts          # ai/ask + ai/askStream（薄封装）
├── prompts/
│   └── wuwei.ts           # 所有 Wuwei 专属 Prompt 模板
├── utils.ts               # 三文件解析 + 格式校验
└── logger.ts              # pi/log 通知透传到 Java

## 删除的文件（PI 框架替代）
❌ router.ts               # PI 的 getModel() 就是 router
❌ prompts/generate.ts     # 合并到 prompts/wuwei.ts
❌ prompts/repair.ts       # 合并到 prompts/wuwei.ts
❌ prompts/drift.ts        # 合并到 prompts/wuwei.ts
❌ handlers/memory-summary.ts # 合并到 generate.ts
```

对应的 Java 侧不变（PiMonoProcess / PiMonoClient / PiMonoAdapter），但因为 Pi 侧更薄，W1 可以合并更多工作。

---

## 三、核心代码设计

### 3.1 server.ts — 唯一改动是用 PI 的 getModel 替代自制 router

```typescript
// server.ts — JSON-RPC 主循环, 零改动
// 读取 stdin → JSON.parse → dispatch handler → JSON.stringify → stdout
// 跟上一版完全一致，因为 PI 框架不关心传输层
```

### 3.2 handlers/generate.ts — 直接调 PI 的 stream/complete

```typescript
import { getModel, complete, stream, type Context } from '@mariozechner/pi-ai';
import { buildGenerateContext } from '../prompts/wuwei.js';
import { parseThreeFiles, validateFilesFormat } from '../utils.js';
import { sendNotification } from '../server.js';

export async function generateHandler(params, logger) {
  const model = getModel(params.model.provider, params.model.model);
  //                                 ^^^^^^^^  ^^^^^^^^^^^
  //                                 IDE 自动补全！'openai' | 'anthropic' | ...

  const ctx: Context = buildGenerateContext({
    intent: params.intent,
    existingSkills: params.existingSkills,
    memory: params.memory,
    currentFiles: params.currentFiles,
  });

  sendNotification('skill/progress', { message: `调用 ${params.model.model}...`, percent: 15 });

  // ── 使用 PI 的 stream() — 自动获得流式事件 ──
  const s = stream(model, ctx, {
    temperature: 0.3,
    maxTokens: 4096,
  });

  let fullText = '';
  for await (const event of s) {
    switch (event.type) {
      case 'text_delta':
        fullText += event.delta;
        break;
      case 'done':
        // event.message.usage.input / .output / .cost.total  ← PI 自动计算
        logger.llm({
          summary: `${params.model.model} 生成完成`,
          provider: params.model.provider,
          model: params.model.model,
          inputTokens: event.message.usage?.input ?? 0,
          outputTokens: event.message.usage?.output ?? 0,
          latencyMs: Date.now() - start,
          cost: event.message.usage?.cost?.total ?? 0,
        });
        break;
      case 'error':
        throw new Error(event.error?.errorMessage ?? 'LLM error');
    }
  }

  const files = parseThreeFiles(fullText);
  validateFilesFormat(files);

  // memoryDelta 提取（首次生成时）
  const memoryDelta = params.memory == null ? {
    newCoreGoals: extractCoreGoals(fullText),
    designDecision: extractDesignDecision(fullText),
  } : null;

  return { ...files, memoryDelta };
}
```

### 3.3 handlers/ai-ask.ts — 极薄封装

```typescript
import { getModel, complete } from '@mariozechner/pi-ai';

export async function aiAskHandler(params) {
  const model = getModel(params.model.provider, params.model.model);

  const response = await complete(model, {
    messages: [{ role: 'user', content: params.prompt }],
    systemPrompt: 'You are a data retrieval assistant. Return pure data — no markdown.',
  });

  const body = response.content
    .filter(b => b.type === 'text')
    .map(b => b.text)
    .join('\n');

  return { status: 200, body };
}

// aiAskStream — 同样简洁
export async function aiAskStreamHandler(params) {
  const model = getModel(params.model.provider, params.model.model);
  const s = stream(model, { messages: [{ role: 'user', content: params.prompt }] });

  for await (const event of s) {
    if (event.type === 'text_delta') {
      sendNotification('ai/streamToken', { token: event.delta, done: false });
    }
  }
  sendNotification('ai/streamToken', { token: '', done: true });
  return { accepted: true };
}
```

### 3.4 prompts/wuwei.ts — 所有 Prompt 模板集中管理

```typescript
import type { Context } from '@mariozechner/pi-ai';

export const WUWEI_SYSTEM_PROMPT = `你是无为平台（Wuwei）的 Skill 生成器...`;

export function buildGenerateContext(params: {
  intent: string;
  existingSkills: string[];
  memory: SkillMemory | null;
  currentFiles: SkillFiles | null;
}): Context {
  const userMessage = [];
  if (params.memory) userMessage.push(buildMemorySection(params.memory));
  if (params.existingSkills.length > 0) userMessage.push(buildExistingSection(params.existingSkills));
  if (params.currentFiles) userMessage.push(buildCurrentFilesSection(params.currentFiles));
  userMessage.push(`## 用户需求\n${params.intent}`);

  return {
    systemPrompt: WUWEI_SYSTEM_PROMPT,
    messages: [{ role: 'user', content: userMessage.join('\n\n') }],
  };
}
```

### 3.5 删掉的 router.ts — PI 框架已完全覆盖

```
原 router.ts 功能                PI 框架对应
─────────────────────           ──────────────────────────
多模型路由                        getModel(provider, model) — 20+ 厂商
API key 管理                      环境变量自动发现 / apiKey 参数
HTTP 请求                        内置 fetch + SSE 解析
流式 token 解析                   stream() → text_delta 事件
Token 计数 + 费用                  response.usage.{input, output, cost}
Fallback 模型切换                  try/catch → 换 model 重试（3 行代码）
重试逻辑                          AbortController + signal 参数
```

---

## 四、与上一版的差异总结

| 模块 | v1（自造轮子） | v2（用 PI 框架） |
|------|---------------|-----------------|
| router.ts | 260 行 HTTP + SSE + 重试 | **删除**，PI 内置 |
| generate.ts | 手写 callLLM + 流式分块 | 直接调 `stream(model, ctx)` |
| ai-ask.ts | 手写 stub + 模拟流式 | `complete(model, {messages})` 3 行 |
| prompts/*.ts | 3 个文件分散 | 合并为 `prompts/wuwei.ts` |
| 模型路由 | 自建 fetch + body 构造 | `getModel()` 类型安全 |
| Token 计费 | 手写 cost 计算 | `response.usage.cost.total` |
| 测试 | 无 | `registerFauxProvider()` |
| 总代码量 | ~600 行 | ~300 行（少一半） |

---

## 五、修订执行计划

PI 框架承担了模型路由、API 调用、token 计费、流式事件、跨厂商切换等所有通用 LLM 工作。Wuwei-pi 只剩 JSON-RPC 传输层 + Prompt 模板 + 三文件解析。计划可以从 4 周压缩到实 2 周。

### W1 — Pi 进程通信 + 真实 LLM 调用（一次性完成 W1+W2）

**目标：** Java ↔ Pi JSON-RPC 通信 + Pi 使用 PI 框架真实调 LLM，全链路走通

| # | 任务 | 验收标准 |
|---|------|---------|
| 1.1 | `wuwei-pi/` 项目初始化 | `bun install` 安装 `@mariozechner/pi-ai`，`package.json` / `tsconfig.json` 就绪 |
| 1.2 | `server.ts` — JSON-RPC 主循环 | `echo '{...}' \| bun run src/server.ts` 返回正确 JSON-RPC 响应 |
| 1.3 | `prompts/wuwei.ts` — Wuwei 专属 Prompt 模板 | 包含 Capability ABI v1.0 完整规范、输出格式、核心规则 |
| 1.4 | `utils.ts` — 三文件解析器 | `=== skill.json ===` / `=== ui.json ===` / `=== handlers.js ===` 正确提取 |
| 1.5 | `handlers/generate.ts` — 使用 `stream(model, ctx)` | 输入意图，PI 框架调 LLM，返回合法三文件 |
| 1.6 | `handlers/repair.ts` — 使用 `complete(model, ctx)` | 输入错误信息 + 当前文件，返回修复后三文件 |
| 1.7 | `handlers/drift.ts` — 漂移分析 | 输入 originalIntent + proposedChange，返回 driftScore + recommendation |
| 1.8 | `handlers/ai-ask.ts` — 薄封装 | `complete(model, {messages})` → `{status, body}` |
| 1.9 | `handlers/ai-ask.ts` 流式 — `stream()` | 逐 token 推送 `ai/streamToken` 通知 |
| 1.10 | `logger.ts` — pi/log 透传 | LLM 调用产生 pi/log 通知含 token/费用 |
| 1.11 | PiMonoProcess.java — 启动 bun exe | Java 启动 Pi 进程，日志看到 PID |
| 1.12 | PiMonoClient.java — JSON-RPC 客户端 | Java 发 `skill/generate`，拿到 Pi 返回的三文件 |
| 1.13 | PiMonoAdapter.java — 业务适配层 | 封装 generate / repair / drift / aiAsk 方法 |
| 1.14 | Main.java 集成 | 内核启动时 Pi 进程同时启动，替换 LlmClient 路径 |

**W1 验证：** 前端输入意图"做一个倒计时器" → Java → Pi（PI 框架调 GPT-4o） → 生成三文件 → 审计通过 → 激活 → UI 渲染。同时前端 Terminal 看到 LLM 调用的 token 用量和费用。

### W2 — Memory 系统 + Java 侧重构 + 打包

**目标：** Memory 文件完整、AiCapability 走 Pi、SQLite 模型路由、bun 打包 exe

| # | 任务 | 验收标准 |
|---|------|---------|
| 2.1 | SkillMemoryService.java — intent.lock / evolution.jsonl / design.md | 首次生成后写入，evolution 只追加，intent.lock 不可覆盖 |
| 2.2 | PiMonoAdapter 注入 Memory 到请求参数 | generate/repair 请求携带 originalIntent + coreGoals |
| 2.3 | SkillGenerator 重构 — 替换 LlmClient 为 PiMonoAdapter | 生成/修复/refine 全走 Pi，审计+安装留在 Java |
| 2.4 | AiCapability 重构 — 走 PiMonoAdapter | `capability.ai.ask()` → PiMonoAdapter.aiAsk() → Pi 进程 → LLM |
| 2.5 | model_routing + model_usage_log 表 | SQLite 管理路由，前端可切换 |
| 2.6 | StoreService 新增 getModelRouting / recordModelUsage | Pi 返回的 LLM 日志写入 model_usage_log |
| 2.7 | 删除 LlmClient / LlmConfig | 清理所有旧 LLM 调用路径 |
| 2.8 | `bun build --compile` 打包 exe | 输出 `wuwei-pi.exe`（约 80MB，含 PI 框架 + bun runtime） |
| 2.9 | Tauri externalBin 配置 + 启动流程 | `wuwei-shell/binaries/wuwei-pi.exe` 作为第二个 sidecar |

**W2 验证：** 进化 3 次后 evolution.jsonl 有 4 行记录，intent.lock 不变。前端切模型后下次生成用新模型。`bun build --compile` 输出单个 exe，双击可运行。

### W3（可选）— Drift 前端 + 回归测试

| # | 任务 |
|---|------|
| 3.1 | 前端 Drift 确认对话框（driftScore > 6 弹窗） |
| 3.2 | 前端模型切换面板 |
| 3.3 | 前端 Terminal — pi / llm / kernel 三个 Tab |
| 3.4 | Faux provider 集成测试（无需 API key 的确定性测试） |
| 3.5 | 12 个端到端测试场景 |

---

## 六、测试策略

### 单元测试（PI 框架自带 Faux Provider）

```typescript
import { registerFauxProvider, fauxAssistantMessage, fauxText } from '@mariozechner/pi-ai';

// 无需 API key，确定性测试
const faux = registerFauxProvider();
faux.setResponses([
  fauxAssistantMessage([
    fauxText(STUB_THREE_FILE_OUTPUT)
  ])
]);

const model = faux.getModel();
const result = await generateHandler({ model: { provider: 'faux', model: 'test' }, ... });
assert(result.skillJson.includes('"id":'));
faux.unregister();
```

### 集成测试

```
1. echo '{"jsonrpc":"2.0","id":"1","method":"skill/generate",...}' | bun run src/server.ts
   → stdout 输出包含 skillJson / uiJson / handlersJs 的 JSON-RPC 响应

2. Java → PiMonoClient.call("skill/generate", ...)
   → CompletableFuture 在 60s 内返回 SkillFiles

3. 全链路：前端输入意图 → WebSocket → Java → Pi → LLM → 审计 → 沙盒 → A2UI 渲染
```

---

## 七、关键原则

1. **PI 框架做所有通用 LLM 工作**：模型路由、API 调用、token 计费、流式解析、跨厂商切换 — 全部由 `@mariozechner/pi-ai` 提供
2. **Wuwei-pi 只写差异化逻辑**：JSON-RPC 传输、Wuwei Capability ABI Prompt、三文件解析、Memory 注入
3. **Pi 进程无状态**：模型配置由 Java 在请求参数中携带，Memory 由 Java 管理文件，Pi 只读参数
4. **API Key 不落配置**：PI 框架从环境变量自动读取，`wuwei.json` 不含密钥
