# 数据库完整示例：建表、增删改查、分页、连表

声明 `"database":{}` 后可用。每个技能有独立 SQLite 数据库。

## 必须在 skill.json 中声明

```json
{
  "capabilities": {
    "ui": {},
    "database": {}
  }
}
```

## handlers/index.js 完整示例

```js
// ⚠️ 顶层函数自动注册，禁止写 module.exports

function onInit(__inputs__, capability) {
  // 1. 建表（DDL）
  capability.db.run("CREATE TABLE IF NOT EXISTS books (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, author_id INTEGER, year INTEGER)");
  capability.db.run("CREATE TABLE IF NOT EXISTS authors (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, dynasty TEXT)");

  // 2. 插入种子数据
  var count = capability.db.query("SELECT COUNT(*) as c FROM books", []);
  if (count[0].c === 0) {
    capability.db.execute("INSERT INTO authors (name, dynasty) VALUES (?, ?)", ["苏轼", "北宋"]);
    capability.db.execute("INSERT INTO authors (name, dynasty) VALUES (?, ?)", ["李白", "唐"]);
    capability.db.execute("INSERT INTO authors (name, dynasty) VALUES (?, ?)", ["杜甫", "唐"]);

    capability.db.execute("INSERT INTO books (title, author_id, year) VALUES (?, ?, ?)", ["念奴娇·赤壁怀古", 1, 1082]);
    capability.db.execute("INSERT INTO books (title, author_id, year) VALUES (?, ?, ?)", ["水调歌头", 1, 1076]);
    capability.db.execute("INSERT INTO books (title, author_id, year) VALUES (?, ?, ?)", ["静夜思", 2, 726]);
  }

  // 3. 加载第一页
  showPage(capability, 1);
}

// ━━ 分页查询 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
function showPage(capability, page) {
  var size = 10;
  var offset = (page - 1) * size;
  var rows = capability.db.query(
    "SELECT b.title, a.name as author, a.dynasty, b.year FROM books b JOIN authors a ON b.author_id = a.id ORDER BY b.year LIMIT ? OFFSET ?",
    [size, offset]
  );
  var text = "";
  for (var i = 0; i < rows.length; i++) {
    text = text + rows[i].title + " — " + rows[i].author + "（" + rows[i].dynasty + "）\n";
  }
  capability.ui.set("content", "text", text);
  capability.ui.set("page-info", "text", "第" + page + "页");
}

// ━━ 按钮事件 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
var currentPage = 1;

function onPrevBtn(__inputs__, capability) {
  currentPage = Math.max(1, currentPage - 1);
  showPage(capability, currentPage);
}

function onNextBtn(__inputs__, capability) {
  currentPage = currentPage + 1;
  showPage(capability, currentPage);
}

// ━━ 搜索（SELECT + WHERE）━━━━━━━━━━━━━━━━━━━━━
function onSearchBtn(__inputs__, capability) {
  var keyword = capability.ui.get("search-input", "value") || "";
  var rows = capability.db.query(
    "SELECT b.title, a.name FROM books b JOIN authors a ON b.author_id = a.id WHERE b.title LIKE ?",
    ["%" + keyword + "%"]
  );
  var text = "搜索 '" + keyword + "': ";
  for (var i = 0; i < rows.length; i++) {
    text = text + rows[i].title + " | ";
  }
  capability.ui.set("content", "text", text);
}

// ━━ 新增（INSERT）━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
function onAddBtn(__inputs__, capability) {
  var title = capability.ui.get("title-input", "value");
  var author = capability.ui.get("author-input", "value");
  if (title && author) {
    capability.db.execute("INSERT INTO books (title, author_id, year) VALUES (?, 1, 2025)", [title]);
    capability.ui.set("content", "text", "已添加: " + title);
  }
}

// ━━ 删除（DELETE）━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
function onDeleteBtn(__inputs__, capability) {
  var id = capability.ui.get("delete-id", "value");
  var result = capability.db.execute("DELETE FROM books WHERE id = ?", [parseInt(id) || 0]);
  capability.ui.set("content", "text", "已删除 " + result.changes + " 条");
}

// ━━ 事务（批量操作）━━━━━━━━━━━━━━━━━━━━━━━━━━━
function onBatchAddBtn(__inputs__, capability) {
  capability.db.transaction(function() {
    capability.db.execute("INSERT INTO books (title, author_id, year) VALUES (?, 1, 2025)", ["新书A"]);
    capability.db.execute("INSERT INTO books (title, author_id, year) VALUES (?, 1, 2025)", ["新书B"]);
    capability.db.execute("INSERT INTO books (title, author_id, year) VALUES (?, 1, 2025)", ["新书C"]);
  });
  capability.ui.set("content", "text", "批量添加完成");
}

// ⚠️ 不要写 module.exports。顶层函数自动注册。
```

## 能力声明速查表

| handlers.js 中调用 | skill.json 必须声明 |
|---|---|
| `capability.db.run/query/execute/transaction` | `"database": {}` |
| `capability.storage.get/put/delete` | `"storage": {}` |
| `capability.ui.set/get` | `"ui": {}` |
| `capability.crypto.encrypt/decrypt/hash/...` | `"crypto": {}` |
| `capability.ai.ask` | `"ai": {}` |
| `capability.network.fetch` | `"network": {"allowlist": ["..."]}` |
| `capability.websearch.search` | `"websearch": {}` |
