# 示例：数据库分页浏览

使用 SQLite 存储数据，用按钮翻页。**必须声明 `"database": {}` 否则运行时报错 `Cannot read property 'query' of undefined`。**

## skill.json

```json
{
  "id": "poem-pagination",
  "version": "1.0.0",
  "abi": "1.0",
  "runtime": "js",
  "meta": {
    "name": "诗词分页浏览",
    "description": "数据库分页浏览示例"
  },
  "capabilities": {
    "ui": {},
    "database": {}
  },
  "signature": {
    "publisher": "local"
  }
}
```

## ui/index.json（片段）

```json
{"id": "root", "component": "Column", "children": ["title", "content-display", "btn-row"]},
{"id": "title", "component": "Text", "text": "诗词浏览", "variant": "h1"},
{"id": "content-display", "component": "Text", "text": "点击按钮加载数据", "variant": "body"},
{"id": "prev-label", "component": "Text", "text": "上一页"},
{"id": "prev-btn", "component": "Button", "child": "prev-label", "action": {"event": {"name": "prev-btn"}}},
{"id": "next-label", "component": "Text", "text": "下一页"},
{"id": "next-btn", "component": "Button", "child": "next-label", "variant": "primary", "action": {"event": {"name": "next-btn"}}},
{"id": "btn-row", "component": "Row", "children": ["prev-btn", "next-btn"], "justify": "center"}
```

## handlers/index.js

```js
var currentPage = 1;
var pageSize = 10;

function onInit(__inputs__, capability) {
  // 创建表（DDL）
  capability.db.run("CREATE TABLE IF NOT EXISTS poems (id INTEGER PRIMARY KEY, title TEXT, content TEXT)");

  // 插入示例数据
  var count = capability.db.query("SELECT COUNT(*) as c FROM poems", []);
  if (count[0].c === 0) {
    capability.db.execute("INSERT INTO poems (title, content) VALUES (?, ?)", ["静夜思", "床前明月光..."]);
    capability.db.execute("INSERT INTO poems (title, content) VALUES (?, ?)", ["春晓", "春眠不觉晓..."]);
  }

  // 加载第一页
  loadPage(capability);
}

function onNextBtn(__inputs__, capability) {
  currentPage = currentPage + 1;
  loadPage(capability);
}

function onPrevBtn(__inputs__, capability) {
  currentPage = Math.max(1, currentPage - 1);
  loadPage(capability);
}

function loadPage(capability) {
  var offset = (currentPage - 1) * pageSize;
  var rows = capability.db.query(
    "SELECT title, content FROM poems ORDER BY id LIMIT ? OFFSET ?",
    [pageSize, offset]
  );
  if (rows.length > 0) {
    capability.ui.set("content-display", "text", rows[0].title + "\n" + rows[0].content);
  } else {
    capability.ui.set("content-display", "text", "没有更多数据了");
    currentPage = Math.max(1, currentPage - 1);
  }
}

// Top-level functions auto-registered. Do NOT use module.exports.
```

## 关键规则

- handlers.js 里用了 `capability.db.xxx` → skill.json 的 `capabilities` 必须声明 `"database": {}`
- 同理：`capability.crypto.xxx` → 声明 `"crypto": {}`
- 同理：`capability.ai.ask` → 声明 `"ai": {}`
- 同理：`capability.network.fetch` → 声明 `"network": {"allowlist": [...]}`
- 同理：`capability.websearch.search` → 声明 `"websearch": {}`
- 没声明就调用 → 运行时报错 → 技能无法使用
