# Sprint: 数据结构动画可视化 + 交互增强

## Goal
将 prototype.html 从"静态文案演示"升级为"带真正动画的交互式数据结构教学操作台"，使每个场景都有可视化的数据结构动画，课堂演示时能让学生"看到"操作过程。

## Success Criteria
- [ ] 栈场景：右侧面板显示竖向栈动画，push/pop/top 操作有真正的元素移动和高亮动画
- [ ] 链表场景：显示节点+箭头的链表图，插入/删除时箭头有重连动画
- [ ] 二叉树场景：显示树形结构图，层序遍历时节点按访问顺序高亮，队列同步变化
- [ ] 选结构场景：显示对比条形图，直观比较各结构的插入/删除/查询性能
- [ ] 小测场景：显示答题进度环和错题标签
- [ ] 聊天发送消息后有打字机效果（逐字显示）
- [ ] 所有动画流畅（CSS transition/animation，不用 JS 定时器模拟）
- [ ] 场景切换时可视化区域有平滑过渡
- [ ] 页面无横向滚动、无 JS 报错
- [ ] 响应式：1180px 以下右侧面板正常折叠到下方

## File Scope
**Can modify:** `frontend/prototype.html`
**Must NOT touch:** 其他所有 .md 文件

## Context

### 现有架构
- 单文件 HTML（~2000 行），CSS + HTML + JS 全在一个文件里
- CSS 变量系统完善（:root 定义了 --bg, --surface, --accent 等）
- 三栏布局：左（280px）+ 中（自适应）+ 右（340px）
- 5 个场景：choose, stack, list, tree, quiz
- 每个场景有完整的 conversation, workflow, tests, scores, logs 数据
- 右侧面板有 4 个 tab：流程 / 测试 / 评分 / 日志

### 关键代码模式
场景数据结构：
```javascript
const scenarioData = {
  stack: {
    title: "栈操作推演",
    // ... 其他字段
    visual: "用竖向堆叠卡片展示栈，顶部高亮当前栈顶。"  // ← 这个只是文字！
  }
}
```

右侧面板渲染函数：
```javascript
function renderRightPanel() {
  // flow tab: 渲染 workflow steps
  // test tab: 渲染测试项
  // score tab: 渲染评分条
  // log tab: 渲染日志
  // 注意：visual 字段只是作为文字追加在 flow tab 底部
}
```

### 需要新增的功能
1. **可视化区域**：在右侧面板顶部（tab 栏下方、内容区上方）添加一个专门的可视化区域
2. **栈动画**：用 CSS flexbox + transition 实现竖向栈，元素从底部推入/弹出
3. **链表动画**：用 SVG 画节点和箭头，插入/删除时箭头路径变化有 transition
4. **二叉树动画**：用 SVG 画树结构，层序遍历时节点逐个高亮
5. **对比图**：用 CSS 条形图展示各结构性能对比
6. **进度环**：用 SVG circle 实现答题进度
7. **打字机效果**：新消息逐字显示，每 20-30ms 一个字

### 设计规范
- 使用现有 CSS 变量系统，不要引入新颜色
- 动画时长：300-500ms，缓动函数用 cubic-bezier(0.16, 1, 0.3, 1)
- 可视化区域高度：200-280px，不要太高挤压对话区
- 节点/元素样式：圆角 8px，与现有 --radius 一致
- 高亮色：--accent (#1f7c8c) 或 --accent-soft (#dceff2)

## DO NOT BREAK
- [ ] 5 个场景的切换功能
- [ ] 聊天发送和回复功能
- [ ] 左侧面板的章节树、题目卡片、参考资料、历史记录
- [ ] 右侧 4 个 tab 的切换和内容
- [ ] 搜索过滤功能
- [ ] 学生选择功能
- [ ] 保存和发布按钮
- [ ] 本地存储持久化
- [ ] 响应式布局（1180px, 860px 断点）
