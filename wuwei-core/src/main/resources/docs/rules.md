# 硬性校验规则

以下规则在 `validate()` 和最终审计中都会被检查，违反会导致生成失败。

## skill.json 规范

### 顶级字段（精确 7 个，多一个少一个都会失败）
```
id, version, abi, runtime, meta, capabilities, signature
```

### id 规则
- 格式：`^[a-z][a-z0-9-]*$` — 小写字母开头，只含小写字母+数字+连字符
- ✅ `counter-tool` `bmi-calculator` `weather-query-v2`
- ❌ `CounterTool`（大写）`counter_tool`（下划线）`-counter`（连字符开头）`123tool`（数字开头）

### version 规则
- 格式：`X.Y.Z`（semver）
- ❌ `1.0` `v1.0.0` `1`

### abi 规则
- 固定值：`"1.0"`（字符串）

### runtime 选择
- `"js"` — GraalJS 沙箱，同步 API，禁止 async/await/Promise
- `"browser-js"` — 浏览器执行，支持 async/await，支持 Three.js/Canvas

### capabilities 规则
- 必须是 JSON 对象 `{}`，不是数组 `[]`
- 只声明实际使用的 capability
- 声明了但没用 → 删掉
- 用了但没声明 → 失败

### signature 规则
- 必须包含 `"publisher": "local"`

## ui.json 规范

- 顶层是 `"components"` 数组
- 必须存在 `id="root"` 且 `component="Column"` 的组件
- Column/Row 的 `children` 是字符串数组，不是嵌套对象
- Button 用 `child`（单字符串）引用 label 组件
- Button 的 `action.event.name` 必须等于 Button 的 id
- 所有 `children` 和 `child` 引用的 id 必须在 components 中存在
- 所有 id 必须唯一

## handlers.js 规范

### JS 运行时（runtime: "js"）
- 所有函数是同步的，禁止 `async`/`await`/`Promise`
- 顶层只允许 `const`，禁止 `let`/`var`
- 禁止：`eval()` `Function()` `require` `import` `globalThis` `fetch` `XMLHttpRequest` `WebSocket`
- Handler 函数签名：`function onXxx(__inputs__, capability)`

### Browser 运行时（runtime: "browser-js"）
- 支持 `async function`，顶层允许 `var`
- storage/network/ai/threejs 调用使用 `await`
- ui.set/get 一直是同步的

### Handler 命名规则
- Button id `"add-btn"` → `function onAddBtn(__inputs__, capability)`
- `__init__` 事件 → `function onInit(__inputs__, capability)`
- `__destroy__` 事件 → `function onDestroy(__inputs__, capability)`
