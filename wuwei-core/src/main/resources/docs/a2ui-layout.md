# A2UI 布局模式

## Column（垂直排列）

```json
// 居中
{"id": "root", "component": "Column", "children": ["a", "b"], "justify": "center", "align": "center"}

// 顶对齐，子元素靠左
{"id": "root", "component": "Column", "children": ["a", "b"], "justify": "start", "align": "start"}

// header 贴顶，footer 贴底
{"id": "root", "component": "Column", "children": ["header", "body", "footer"], "justify": "spaceBetween"}
```

## Row（水平排列）

```json
// 按钮靠右
{"id": "btn-row", "component": "Row", "children": ["cancel", "confirm"], "justify": "end"}

// 两端对齐，垂直居中
{"id": "row", "component": "Row", "children": ["left", "right"], "justify": "spaceBetween", "align": "center"}
```

## 卡片模式

```
Card → Column（inner）→ Text（title + content）
```
```json
{"id": "inner", "component": "Column", "children": ["card-title", "card-text"]},
{"id": "card-title", "component": "Text", "text": "标题", "variant": "h3"},
{"id": "card-text", "component": "Text", "text": "内容", "variant": "body"},
{"id": "card", "component": "Card", "child": "inner"}
```

## 按钮模式

```json
// 文字按钮
{"id": "btn-label", "component": "Text", "text": "点击"},
{"id": "my-btn", "component": "Button", "child": "btn-label", "variant": "primary",
 "action": {"event": {"name": "my-btn"}}}

// 图标按钮
{"id": "close-icon", "component": "Icon", "name": "close"},
{"id": "close-btn", "component": "Button", "child": "close-icon", "variant": "borderless",
 "action": {"event": {"name": "close-btn"}}}
```

## 视觉层次

- h1 → 页面标题（最大）
- h2/h3 → 区域标题
- body → 正文（默认）
- caption → 辅助文字（小号灰色）

## 按钮权重

- primary → 主操作（提交/确认/搜索）
- default → 次要操作（取消/重置）
- borderless → 轻量操作（关闭/删除）
