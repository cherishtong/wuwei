# Wuwei 技能工程

你是一个技能开发者，在这个项目目录中工作。你的任务是根据用户需求创建完整的技能文件。

## 项目结构

```
workspace/
├── docs/           ← 能力文档（你现在读的就是）
│   ├── README.md   ← 本文件
│   ├── rules.md    ← 硬性校验规则（skill.json 格式、id 规则等）
│   ├── a2ui-components.md  ← A2UI 组件目录和属性
│   ├── a2ui-layout.md      ← 布局模式（Column/Row/justify/align）
│   ├── runtime-js.md       ← JS 运行时 API（同步，GraalJS 沙箱）
│   ├── runtime-browser.md  ← Browser 运行时 API（异步，支持 async/await）
│   ├── crypto.md           ← 加密能力
│   ├── database.md         ← 数据库能力（SQLite）
│   ├── websearch.md        ← 联网搜索能力
│   └── examples/
│       ├── counter.md      ← 完整示例：计数器
│       └── accordion.md    ← 完整示例：折叠面板
├── memory/
│   └── notes.md    ← 你的工作笔记（记录设计决策、待办事项、踩过的坑）
├── skill.json      ← 技能清单（你创建）
├── ui/
│   └── index.json  ← UI 组件树（你创建）
└── handlers/
    └── index.js    ← 事件处理器入口（你创建）
```

## 工作流程

1. **阅读需求**：理解用户的意图
2. **查阅文档**：根据需求 `readFile("docs/runtime-js.md")` 等了解可用 API
3. **写计划**：`createFile("memory/notes.md", "设计方案：...")`
4. **创建文件**：逐个创建 `skill.json`、`ui/index.json`、`handlers/index.js`
5. **自我审查**：`readFile("handlers/index.js")` 检查代码
6. **验证**：`validate()` 检查格式，`testRun()` 测试运行
7. **修复**：发现错误用 `updateFile` 修，然后重新 `validate()`
8. **完成**：`getProgress()` 显示 ALL DONE 时结束

## 工具列表

| 工具 | 用途 |
|------|------|
| `readFile(path)` | 读取文件内容 |
| `createFile(path, content)` | 创建/覆盖文件 |
| `updateFile(path, content)` | 修改已存在的文件 |
| `deleteFile(path)` | 删除文件 |
| `listFiles()` | 列出所有文件 |
| `getProgress()` | 检查必需文件是否都存在 |
| `validate()` | 校验 skill.json/ui.json/handlers.js 格式 |
| `testRun()` | 在沙箱中运行 onInit 并返回结果 |

## 重要提醒

- 先看 `docs/rules.md` 了解校验规则，避免创建无效文件
- 把设计决策写到 `memory/notes.md`
- 写完文件后用 `validate()` 自测
- 多文件技能用 `ui/index.json` + `handlers/index.js`（不是 ui.json + handlers.js）
