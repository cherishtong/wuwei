# 示例：折叠面板展示数据

一个使用 Accordion 展示分类数据的技能（纯静态展示）。

## skill.json

```json
{
  "id": "data-display",
  "version": "1.0.0",
  "abi": "1.0",
  "runtime": "js",
  "meta": {
    "name": "数据展示",
    "description": "使用折叠面板展示分类数据"
  },
  "capabilities": {
    "ui": {}
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
    {"id": "root", "component": "Column", "children": ["title", "divider", "data-accordion"], "justify": "start", "align": "stretch"},
    {"id": "title", "component": "Text", "text": "数据展示", "variant": "h1"},
    {"id": "divider", "component": "Divider"},
    {"id": "data-accordion", "component": "Accordion", "items": [
      {"value": "sec-1", "triggerText": "第一部分", "contentId": "content-1"},
      {"value": "sec-2", "triggerText": "第二部分", "contentId": "content-2"}
    ]},
    {"id": "content-1", "component": "Column", "children": ["text-1a", "text-1b"]},
    {"id": "text-1a", "component": "Text", "text": "这是第一部分的**第一条**内容", "variant": "body"},
    {"id": "text-1b", "component": "Text", "text": "- 列表项1\n- 列表项2", "variant": "body"},
    {"id": "content-2", "component": "Column", "children": ["text-2"]},
    {"id": "text-2", "component": "Text", "text": "这是第二部分的内容", "variant": "body"}
  ]
}
```

## handlers/index.js

```js
function onInit(__inputs__, capability) {
  // 纯静态展示，无需初始化逻辑
}

// Top-level functions auto-registered. Do NOT use module.exports.
```

## 关键模式

1. Accordion 的 `items` 中每项用 `contentId` 指向一个组件
2. 每个 content 组件通常是一个 Column，包含多个子元素
3. 如果用 AI 动态获取内容，在 `onInit` 中调 `capability.ai.ask()` 然后用 `capability.ui.set()` 更新
