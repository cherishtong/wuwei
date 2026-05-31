# JS 运行时（GraalJS 沙箱）

`runtime: "js"` — 同步执行，禁止 async/await/Promise。

## 限制

- ❌ `async`/`await`/`Promise`
- ❌ 顶层 `let`/`var`（仅 `const`）
- ❌ `eval()` `Function()` `require` `import` `globalThis` `fetch` `XMLHttpRequest` `WebSocket`

## 可用 API

```
// 持久化存储
capability.storage.get(key) → string | null
capability.storage.put(key, value) → void
capability.storage.delete(key) → void

// UI 操作（同步）
capability.ui.set(id, "text", value) → void
capability.ui.get(id, "text") → string

// 系统通知（需声明 os）
capability.os.notify(title, body) → void

// 网络请求（需声明 network + allowlist）
capability.network.fetch({url, method?, headers?, body?}) → {status, body}

// AI 问答（需声明 ai）
capability.ai.ask(prompt) → {status, body}

// 文件操作（需声明 file）
capability.file.read(path) → string
capability.file.write(path, content) → void
capability.file.list(dir) → [string]
capability.file.delete(path) → void

// 数据库（需声明 database）
capability.db.run(sql) → void（仅 DDL）
capability.db.query(sql, [params]) → [{col: val, ...}]（仅 SELECT）
capability.db.execute(sql, [params]) → {changes: N}（INSERT/UPDATE/DELETE）
capability.db.transaction(fn) → void

// 搜索（需声明 websearch）
capability.websearch.search(query, limit?) → {results: [{title, url, snippet, score}]}

// 加密（需声明 crypto）
capability.crypto.deriveKey(password, salt) → key (base64)
capability.crypto.encrypt(plaintext, key) → ciphertext (base64)
capability.crypto.decrypt(ciphertext, key) → plaintext
capability.crypto.hash(data) → hex string
capability.crypto.randomBytes(n) → base64 (max 1024)
capability.crypto.generatePassword(len) → string
```

## 正确示例

```js
function onInit(__inputs__, capability) {
  var saved = capability.storage.get("count");
  if (saved) {
    capability.ui.set("count-display", "text", saved);
  }
}

function onAddBtn(__inputs__, capability) {
  var current = capability.ui.get("count-display", "text") || "0";
  var next = String((parseInt(current) || 0) + 1);
  capability.ui.set("count-display", "text", next);
  capability.storage.put("count", next);
}

function onDestroy(__inputs__, capability) {
  // 清理资源
}
```
