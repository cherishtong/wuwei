# 示例：计数器

一个简单的计数器技能，展示 Button 点击 → Handler 处理 → UI 更新的完整数据流。

## skill.json

```json
{
  "id": "counter-tool",
  "version": "1.0.0",
  "abi": "1.0",
  "runtime": "js",
  "meta": {
    "name": "计数器",
    "description": "一个简单的计数器工具"
  },
  "capabilities": {
    "ui": {},
    "storage": {}
  },
  "signature": {
    "publisher": "local"
  }
}
```

## ui/index.json

```json
{
  "components": [
    {"id": "root", "component": "Column", "children": ["title", "count-display", "button-row"], "justify": "center", "align": "center"},
    {"id": "title", "component": "Text", "text": "计数器", "variant": "h1"},
    {"id": "count-display", "component": "Text", "text": "0", "variant": "h2"},
    {"id": "decrement-label", "component": "Text", "text": "-"},
    {"id": "decrement-btn", "component": "Button", "child": "decrement-label", "variant": "default", "action": {"event": {"name": "decrement-btn"}}},
    {"id": "increment-label", "component": "Text", "text": "+"},
    {"id": "increment-btn", "component": "Button", "child": "increment-label", "variant": "primary", "action": {"event": {"name": "increment-btn"}}},
    {"id": "button-row", "component": "Row", "children": ["decrement-btn", "increment-btn"], "justify": "center"}
  ]
}
```

## handlers/index.js

```js
function onInit(__inputs__, capability) {
  var saved = capability.storage.get("count");
  if (saved) {
    capability.ui.set("count-display", "text", saved);
  }
}

function onIncrementBtn(__inputs__, capability) {
  var current = capability.ui.get("count-display", "text") || "0";
  var next = String((parseInt(current) || 0) + 1);
  capability.ui.set("count-display", "text", next);
  capability.storage.put("count", next);
}

function onDecrementBtn(__inputs__, capability) {
  var current = capability.ui.get("count-display", "text") || "0";
  var next = String(Math.max(0, (parseInt(current) || 0) - 1));
  capability.ui.set("count-display", "text", next);
  capability.storage.put("count", next);
}

// Top-level functions auto-registered. Do NOT use module.exports.
```

## 数据流

```
TextField.value: 无（计数器不需要输入）
Button click → action.event.name: "increment-btn"
  ↓
Handler: onIncrementBtn(__inputs__, capability)
  ↓
capability.ui.get("count-display", "text") → "0"
parseInt → +1 → "1"
capability.ui.set("count-display", "text", "1") → UI 自动更新
capability.storage.put("count", "1") → 持久化
```
