# Database 数据库能力

声明：`"database": {}`

每个技能拥有独立的 SQLite 数据库文件。

## API

```js
// DDL — 创建表/索引/视图
capability.db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
capability.db.run("CREATE INDEX idx_name ON users(name)");

// 查询 — SELECT（返回数组，上限 10000 行）
var rows = capability.db.query("SELECT * FROM users WHERE age > ?", [18]);
// rows = [{id: 1, name: "张三", age: 25}, ...]

// 写入 — INSERT/UPDATE/DELETE（返回 {changes: N}）
var result = capability.db.execute(
  "INSERT INTO users (name, age) VALUES (?, ?)", ["王五", 28]
);
// result = {changes: 1}

// 事务
capability.db.transaction(function() {
  capability.db.execute("INSERT INTO users (name, age) VALUES (?, ?)", ["赵六", 22]);
  capability.db.execute("UPDATE users SET age = ? WHERE name = ?", [23, "赵六"]);
});
```

## SQL 白名单

**DDL（仅允许 run）：**
- `CREATE TABLE` / `CREATE INDEX` / `CREATE VIEW`
- `ALTER TABLE`
- `DROP TABLE` / `DROP INDEX`

**DML（仅允许 execute/query）：**
- `SELECT` → `query()`
- `INSERT` / `UPDATE` / `DELETE` / `REPLACE` → `execute()`

## 禁止

- ❌ 多语句（分号分隔）
- ❌ `PRAGMA` / `VACUUM` / `ATTACH` / `DETACH`
- ❌ 查询超时 5 秒
- ❌ 最多 50 张表
- ❌ 数据库文件上限 100MB

## 使用场景

- 数据录入和管理
- 用户信息存储
- 日志记录
- 配置持久化
