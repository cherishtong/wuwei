# Browser 运行时

`runtime: "browser-js"` — 浏览器执行，支持 async/await，支持 Three.js/Canvas。

## 与 JS 运行时的区别

| | JS 运行时 | Browser 运行时 |
|---|---|---|
| async/await | ❌ | ✅ |
| 顶层 var | ❌ | ✅（声明状态） |
| storage/network/ai | 同步 | 返回 Promise |
| Three.js 3D | ❌ | ✅ |
| Canvas 2D 绘图 | ❌ | ✅ |
| ui.set/get | 同步 | 同步 |

## 可用 API（异步，需 await）

```js
// 持久化存储
var saved = await capability.storage.get("count");
await capability.storage.put("count", "5");

// 网络请求（需声明 network + allowlist）
var resp = await capability.network.fetch({url: "https://api.example.com/data"});
if (resp.status === 200) { ... }

// AI 问答（需声明 ai）
var result = await capability.ai.ask("查询信息");

// Three.js 3D（需声明 threejs）
var scene3d = await capability.threejs.init('my-canvas');
var T = scene3d.THREE;
var geo = T.BoxGeometry(1, 1, 1);
var mat = T.MeshStandardMaterial({color: 0x44aa88, roughness: 0.3});
var cube = T.Mesh(geo, mat);
scene3d.scene.add(cube);
scene3d.animate(function() { cube.rotation.y += 0.01; });

// Canvas 2D 绘图（需声明 canvas）
capability.canvas.render("my-canvas", [
  {type: "rect", x: 10, y: 10, w: 100, h: 50, fill: "#4488ff"}
]);

// 系统通知
await capability.os.notify("标题", "内容");

// 文件操作
var content = await capability.file.read("data.txt");
await capability.file.write("output.txt", data);
```

## 可用 API（同步，无需 await）

```js
capability.ui.set("title", "text", "新标题");
var current = capability.ui.get("title", "text");
```

## 所有能力（需在 capabilities 中声明）

```json
{"ui": {}, "storage": {}, "canvas": {}, "threejs": {},
 "network": {"allowlist": ["https://api.example.com"]},
 "ai": {}, "file": {}, "os": {},
 "crypto": {}, "database": {}, "websearch": {}}
```

## Three.js 对象操作

属性直接赋值，不用 set：
```js
cube.position.x = 2;    // ✅
cube.position.set(2,0,0); // ❌
```

颜色必须是 6 位 hex：`0x44aa88` `0xffffff` `0x404040`
禁止只写 `0x` 不带数字。
