#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate course PDF materials for the data-structure learning agent.

Each handout uses the same teaching template so the materials page feels like a
coherent learning kit rather than a loose list of files.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Iterable

from fpdf import FPDF
from pypdf import PdfReader


ROOT = Path(__file__).resolve().parent
OUTPUT_DIR = ROOT / "pdfs"
OUTPUT_DIR.mkdir(exist_ok=True)


def first_existing(paths: Iterable[str]) -> str:
    for item in paths:
        if item and Path(item).exists():
            return item
    raise FileNotFoundError("No CJK font found. Install Microsoft YaHei or Noto Sans CJK.")


REGULAR_FONT = first_existing([
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simhei.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
])

BOLD_FONT = first_existing([
    "C:/Windows/Fonts/msyhbd.ttc",
    "C:/Windows/Fonts/simhei.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc",
    REGULAR_FONT,
])


MATERIALS = [
    {
        "filename": "linear-structure-selection.pdf",
        "title": "线性结构选型讲义",
        "subtitle": "数组、链表、栈、队列该怎么选",
        "objectives": ["能根据操作频率选择结构", "能说清数组、链表、栈、队列的区别", "能用复杂度解释选型理由"],
        "concepts": [
            ("数组", "连续存储，支持 O(1) 随机访问；插入删除通常需要搬移元素。"),
            ("链表", "离散存储，通过指针连接；已知位置时插入删除方便，但随机访问慢。"),
            ("栈", "后进先出，只在栈顶操作，适合处理最近未完成的任务。"),
            ("队列", "先进先出，适合按到达顺序处理任务。"),
        ],
        "process": ["先判断是否需要按下标随机访问", "再判断插入删除是否频繁", "最后判断处理顺序是否有 LIFO 或 FIFO 约束"],
        "template": "if need_random_access:\n    choose('array')\nelif need_frequent_insert_delete:\n    choose('linked list')\nelif order == 'last in first out':\n    choose('stack')\nelif order == 'first in first out':\n    choose('queue')",
        "mistakes": ["只看插入复杂度，忘记查找定位也要时间", "把栈和队列混为一谈", "认为链表任何插入删除都是 O(1)，忽略寻找位置的成本"],
        "exercises": ["浏览器后退记录适合什么结构？", "需要频繁查询用户是否存在，为什么不优先用链表？", "实现打印任务排队时应选什么结构？"],
        "summary": "选型先看操作模式，再谈实现方式。复杂度是解释选择的证据，不是背结论。",
    },
    {
        "filename": "complexity-cheatsheet.pdf",
        "title": "复杂度分析速查表",
        "subtitle": "常见数据结构操作复杂度",
        "objectives": ["能快速查常见结构复杂度", "能区分平均、均摊和最坏情况", "能用规模判断算法是否可行"],
        "concepts": [
            ("O(1)", "操作次数不随 n 增长，例如数组下标访问。"),
            ("O(log n)", "每次排除一半左右的数据，例如二分查找。"),
            ("O(n)", "需要线性扫描，例如链表查找。"),
            ("O(n log n)", "常见于高效排序和分治算法。"),
        ],
        "table": [
            ["结构", "访问", "查找", "插入/删除"],
            ["数组", "O(1)", "O(n)", "O(n)"],
            ["链表", "O(n)", "O(n)", "O(1)*"],
            ["栈/队列", "受限", "O(n)", "O(1)"],
            ["哈希表", "-", "O(1)均摊", "O(1)均摊"],
            ["堆", "-", "O(n)", "O(log n)"],
            ["BST", "O(log n)平均", "O(log n)平均", "O(log n)平均"],
        ],
        "process": ["先确认 n 的规模", "再看每个元素会被处理几次", "最后说明额外空间来自哪些变量或容器"],
        "template": "时间复杂度：看循环、递归深度、每个元素入出次数\n空间复杂度：看是否新开数组、哈希表、递归栈",
        "mistakes": ["只写 O(n)，不说明 n 代表什么", "把均摊 O(1) 说成任何时候都是 O(1)", "递归题漏掉调用栈空间"],
        "exercises": ["单调栈为什么是 O(n)？", "哈希表什么时候会退化？", "递归二叉树遍历的空间复杂度是多少？"],
        "summary": "复杂度分析要把操作次数和数据规模对应起来，不能只背表格。",
    },
    {
        "filename": "choosing-exercises.pdf",
        "title": "课堂练习：结构选型",
        "subtitle": "用场景训练结构判断",
        "objectives": ["能从题干提取关键操作", "能给出首选结构和理由", "能识别容易混淆的场景"],
        "concepts": [
            ("判断依据", "读题时圈出随机访问、插入删除、顺序处理、优先级、查找频率。"),
            ("回答格式", "先给结构，再给复杂度理由，最后提醒边界或替代方案。"),
        ],
        "process": ["圈关键操作", "匹配结构特性", "写复杂度理由", "补充边界条件"],
        "template": "场景：____\n首选结构：____\n理由：____ 操作更频繁，复杂度为 ____\n注意：____",
        "mistakes": ["看到插入就选链表，但题目其实频繁按下标查找", "看到排队就忘记是否存在优先级", "没有写出为什么该结构更合适"],
        "exercises": [
            "频繁按编号查询学生成绩，应选什么结构？",
            "模拟撤销功能，应选什么结构？",
            "需要每次取出优先级最高的任务，应选什么结构？",
            "实现 BFS 层序遍历，应选什么结构？",
        ],
        "summary": "结构选型不是猜名词，而是把操作需求翻译成复杂度优势。",
    },
    {
        "filename": "stack-queue-lecture.pdf",
        "title": "栈与队列讲义",
        "subtitle": "LIFO 与 FIFO 的核心区别",
        "objectives": ["能说清栈和队列的操作口", "能画出 push/pop/enqueue/dequeue 的状态变化", "能识别典型应用"],
        "concepts": [
            ("栈", "后进先出，只允许在栈顶插入和删除。"),
            ("队列", "先进先出，队尾入队，队头出队。"),
            ("循环队列", "用数组首尾相接，避免普通顺序队列的假溢出。"),
        ],
        "process": ["栈操作时只关注 top", "队列操作时同时关注 head 和 tail", "判断空满前先明确指针语义"],
        "template": "stack.push(x)\nstack.pop()\nqueue.enqueue(x)\nqueue.dequeue()",
        "mistakes": ["把 peek/top 当成删除操作", "队列出队后忘记移动 head", "循环队列空满条件写混"],
        "exercises": ["写出 push(1), push(2), pop(), push(3) 后的栈状态", "队列依次入队 A,B,C 后出队一次，队头是谁？", "为什么 BFS 适合队列？"],
        "summary": "栈和队列的差别不是存什么，而是元素离开的顺序。",
    },
    {
        "filename": "bracket-expression.pdf",
        "title": "括号匹配与表达式求值",
        "subtitle": "栈的经典应用",
        "objectives": ["能用栈判断括号是否有效", "能理解表达式求值为什么需要保存未完成运算符", "能处理空栈边界"],
        "concepts": [
            ("最近未匹配", "右括号要匹配最近出现的左括号，这正好符合栈顶含义。"),
            ("后缀表达式", "运算符出现时，从栈中取最近的两个操作数计算。"),
        ],
        "process": ["遇到左括号入栈", "遇到右括号检查栈顶", "不匹配立即失败", "遍历结束后栈必须为空"],
        "template": "for ch in s:\n    if ch in '([{': push(ch)\n    else:\n        if empty() or top() does_not_match ch: return False\n        pop()\nreturn empty()",
        "mistakes": ["右括号出现时没有先判断栈空", "只判断数量相等，没判断顺序", "表达式求值时弹出两个操作数顺序写反"],
        "exercises": ["判断 ([{}]) 是否有效", "判断 ([)] 为什么无效", "后缀表达式 2 3 4 * + 的结果是多少？"],
        "summary": "栈适合处理“最近出现、最先解决”的问题。",
    },
    {
        "filename": "monotonic-stack.pdf",
        "title": "单调栈专题练习",
        "subtitle": "下一个更大元素类问题",
        "objectives": ["能说清单调栈维护什么", "能解释为什么总复杂度是 O(n)", "能套用模板处理相邻更大/更小问题"],
        "concepts": [
            ("单调性", "栈内元素按某种顺序保持递增或递减。"),
            ("等待答案", "栈中保存还没有找到下一个更大元素的位置。"),
        ],
        "process": ["遍历当前元素", "当当前元素能解决栈顶问题时弹栈", "记录答案", "当前下标入栈等待后续元素"],
        "template": "ans = [-1] * n\nstack = []\nfor i, x in enumerate(nums):\n    while stack and x > nums[stack[-1]]:\n        ans[stack.pop()] = x\n    stack.append(i)",
        "mistakes": ["栈里存值导致无法回填答案下标", "没有理解每个元素最多入栈出栈一次", "递增栈和递减栈方向写反"],
        "exercises": ["求 [2,1,3] 的下一个更大元素", "每日温度为什么适合单调栈？", "单调栈和普通栈的区别是什么？"],
        "summary": "单调栈的关键不是栈本身，而是把还没得到答案的元素暂存起来。",
    },
    {
        "filename": "linked-list-basics.pdf",
        "title": "链表基础讲义",
        "subtitle": "节点、指针与基本操作",
        "objectives": ["能画出单链表节点关系", "能完成头插、尾插、删除", "能区分链表和数组的优缺点"],
        "concepts": [
            ("节点", "由数据域和指针域组成，指针指向下一个节点。"),
            ("头指针", "指向链表第一个节点，是访问整条链的入口。"),
            ("双链表", "每个节点同时保存前驱和后继指针，删除更方便但空间更大。"),
        ],
        "process": ["先找到要操作的位置", "保存可能丢失的 next", "再改指针方向", "最后更新 head 或 tail"],
        "template": "new.next = head\nhead = new\n\nprev.next = curr.next  # delete curr",
        "mistakes": ["先改 curr.next 导致后续节点丢失", "删除头节点时忘记更新 head", "把链表随机访问误认为 O(1)"],
        "exercises": ["画出头插 N 到 A->B 的过程", "删除第一个节点时 head 如何变化？", "为什么链表查找第 i 个元素是 O(n)？"],
        "summary": "链表操作的本质是改连接关系，顺序错了就会断链。",
    },
    {
        "filename": "pointer-operations.pdf",
        "title": "链表指针操作图解",
        "subtitle": "头插、删除、反转的状态变化",
        "objectives": ["能追踪 prev/curr/next", "能解释反转链表的每一步", "能检查常见断链风险"],
        "concepts": [
            ("prev", "当前节点的前驱，删除或反转时经常需要它。"),
            ("curr", "当前正在处理的节点。"),
            ("next", "提前保存的后继，防止改指针后找不到剩余链表。"),
        ],
        "process": ["保存 next = curr.next", "让 curr.next 指向 prev", "prev 前进到 curr", "curr 前进到 next"],
        "template": "prev = None\ncurr = head\nwhile curr:\n    nxt = curr.next\n    curr.next = prev\n    prev = curr\n    curr = nxt\nhead = prev",
        "mistakes": ["忘记保存 nxt", "循环结束后返回 curr 而不是 prev", "处理空链表和单节点链表时没有检查"],
        "exercises": ["反转 A->B->C 的每一步状态", "删除 B 时 prev 和 curr 分别是谁？", "为什么需要 dummy head？"],
        "summary": "链表题要先画状态，再写代码。变量名就是你观察指针的镜头。",
    },
    {
        "filename": "linked-list-problems.pdf",
        "title": "链表经典题目集",
        "subtitle": "反转、合并、环检测与快慢指针",
        "objectives": ["能识别链表高频题型", "能用快慢指针判断环", "能把题目拆成指针状态"],
        "concepts": [
            ("快慢指针", "两个指针速度不同，可用于找中点或判断环。"),
            ("dummy head", "虚拟头节点能简化头节点插入删除。"),
            ("原地操作", "通常要求 O(1) 额外空间，需要直接改指针。"),
        ],
        "process": ["明确是否需要前驱", "决定是否使用 dummy", "画出一次循环中的指针移动", "补空链表和单节点边界"],
        "template": "dummy = Node(0)\ntail = dummy\nwhile a and b:\n    if a.val <= b.val:\n        tail.next = a; a = a.next\n    else:\n        tail.next = b; b = b.next\n    tail = tail.next",
        "mistakes": ["合并链表后忘记接上剩余部分", "快慢指针循环条件写错", "环检测只判断值相等而不是节点相同"],
        "exercises": ["合并两个有序链表", "判断链表是否有环", "找链表中点", "删除倒数第 k 个节点"],
        "summary": "链表经典题的共同点是指针移动条件，先确认循环不丢节点。",
    },
    {
        "filename": "binary-tree-lecture.pdf",
        "title": "二叉树与遍历讲义",
        "subtitle": "前序、中序、后序与层序",
        "objectives": ["能区分四种遍历顺序", "能解释递归遍历的访问时机", "能用队列做层序遍历"],
        "concepts": [
            ("前序", "根、左、右，先访问根。"),
            ("中序", "左、根、右，BST 的中序结果递增。"),
            ("后序", "左、右、根，常用于释放或汇总子树信息。"),
            ("层序", "按层从左到右访问，通常用队列。"),
        ],
        "process": ["先确认访问根的时机", "递归处理左子树", "递归处理右子树", "层序时维护一个队列"],
        "template": "def preorder(root):\n    if not root: return\n    visit(root)\n    preorder(root.left)\n    preorder(root.right)",
        "mistakes": ["把访问和经过混淆", "层序遍历忘记每次出队后加入左右孩子", "递归没有空节点出口"],
        "exercises": ["给出一棵树的前序和中序", "用队列写层序遍历过程", "为什么 BST 中序有序？"],
        "summary": "遍历的核心是访问根节点的时机，而不是死背名称。",
    },
    {
        "filename": "bst-heap.pdf",
        "title": "BST 与堆专题",
        "subtitle": "搜索树和优先队列的区别",
        "objectives": ["能说清 BST 的有序性", "能说清堆的父子关系", "能避免把堆当成二叉搜索树"],
        "concepts": [
            ("BST", "左子树小于根，右子树大于根；中序遍历有序。"),
            ("堆", "父节点优先级不大于或不小于子节点；整体不保证有序。"),
            ("完全二叉树", "堆通常用数组表示完全二叉树。"),
        ],
        "process": ["BST 查找按大小走左或右", "堆插入先放末尾再上浮", "删除堆顶后用末尾元素补位再下沉"],
        "template": "parent = (i - 1) // 2\nleft = 2 * i + 1\nright = 2 * i + 2",
        "mistakes": ["认为堆的中序遍历有序", "BST 退化成链表时仍写 O(log n)", "删除堆顶后忘记下沉恢复性质"],
        "exercises": ["BST 中查找 7 的路径如何走？", "最小堆插入 1 后为什么要上浮？", "堆和 BST 哪个适合 Top-K？"],
        "summary": "BST 为查找服务，堆为快速取最优元素服务，目标不同。",
    },
    {
        "filename": "traversal-exercises.pdf",
        "title": "树的遍历练习题",
        "subtitle": "用序列检查遍历理解",
        "objectives": ["能从树写出遍历序列", "能根据访问顺序判断遍历类型", "能用层序队列解释状态变化"],
        "concepts": [
            ("访问序列", "把每次 visit 的节点按顺序写下来。"),
            ("递归栈", "DFS 遍历隐含使用调用栈。"),
        ],
        "process": ["画出根、左、右", "标记访问根的时机", "逐层或递归写序列", "检查节点是否遗漏"],
        "template": "层序：\nqueue = [root]\nwhile queue:\n    node = queue.pop(0)\n    visit(node)\n    push(node.left)\n    push(node.right)",
        "mistakes": ["把空节点也写进结果", "递归回溯时重复访问", "层序没有按从左到右入队"],
        "exercises": ["根为 A，左 B，右 C 的前序是什么？", "同一棵树的中序是什么？", "层序遍历时队列如何变化？"],
        "summary": "遍历题适合边画边写，序列必须和访问动作一一对应。",
    },
    {
        "filename": "queue-lecture.pdf",
        "title": "队列基础讲义",
        "subtitle": "FIFO、循环队列与基本操作",
        "objectives": ["能解释 FIFO", "能画出 head/tail 变化", "能实现循环队列的空满判断"],
        "concepts": [
            ("普通队列", "队尾入队，队头出队。"),
            ("循环队列", "数组逻辑上首尾相接，指针取模移动。"),
            ("假溢出", "数组前面有空位但 tail 已到末尾，循环队列可以避免。"),
        ],
        "process": ["enqueue 写入 tail", "tail 向后移动", "dequeue 读取 head", "head 向后移动"],
        "template": "tail = (tail + 1) % capacity\nhead = (head + 1) % capacity\nempty = head == tail\nfull = (tail + 1) % capacity == head",
        "mistakes": ["head/tail 指向含义不统一", "取模遗漏导致越界", "空满条件都写成 head == tail"],
        "exercises": ["容量为 5 的循环队列入队 3 次后 tail 在哪里？", "为什么 BFS 使用队列？", "队列和栈的出元素顺序有什么不同？"],
        "summary": "队列的难点在于指针语义一致，尤其是循环队列。",
    },
    {
        "filename": "deque-lecture.pdf",
        "title": "双端队列专题",
        "subtitle": "两端都能操作的队列",
        "objectives": ["能区分队列和双端队列", "能说出双端队列操作", "能理解滑动窗口应用"],
        "concepts": [
            ("双端队列", "front 和 back 两端都可以插入或删除。"),
            ("单调队列", "常用双端队列维护窗口内候选最大值或最小值。"),
        ],
        "process": ["窗口右端进入新元素", "移除过期下标", "维护队列单调性", "队头就是当前窗口答案"],
        "template": "while deque and deque[0] <= i - k: pop_front()\nwhile deque and nums[deque[-1]] <= nums[i]: pop_back()\npush_back(i)",
        "mistakes": ["把双端队列当成两个栈", "滑动窗口中忘记删除过期下标", "队列里存值导致无法判断是否过期"],
        "exercises": ["普通队列不能从头部插入，双端队列可以吗？", "滑动窗口最大值为什么需要删除过期下标？", "单调队列里通常存值还是下标？"],
        "summary": "双端队列给了两端操作能力，适合需要维护候选集合的窗口题。",
    },
    {
        "filename": "queue-exercises.pdf",
        "title": "队列应用练习",
        "subtitle": "BFS、任务调度和缓冲区",
        "objectives": ["能把 FIFO 应用到场景题", "能写出 BFS 队列变化", "能识别队列边界"],
        "concepts": [
            ("BFS", "先访问距离更近的节点，队列保证按层推进。"),
            ("任务调度", "先来的任务先处理，除非题目有优先级。"),
            ("缓冲区", "生产者写入，消费者按顺序读取。"),
        ],
        "process": ["初始节点入队", "出队并处理", "把未访问邻居入队", "直到队列为空"],
        "template": "queue = [start]\nvisited.add(start)\nwhile queue:\n    node = dequeue()\n    for nxt in neighbors(node):\n        if nxt not in visited:\n            visited.add(nxt)\n            enqueue(nxt)",
        "mistakes": ["访问标记太晚导致重复入队", "用栈写 BFS 导致顺序错误", "有优先级时仍使用普通队列"],
        "exercises": ["写出二叉树层序遍历的队列变化", "为什么 BFS 能求无权图最短步数？", "打印队列是否适合普通队列？"],
        "summary": "队列应用题的关键是“谁先进入，谁先被处理”。",
    },
    {
        "filename": "heap-lecture.pdf",
        "title": "堆与优先队列讲义",
        "subtitle": "上浮、下沉和数组表示",
        "objectives": ["能用数组表示堆", "能解释插入上浮", "能解释删除堆顶下沉"],
        "concepts": [
            ("最小堆", "每个父节点都不大于子节点，堆顶是最小值。"),
            ("最大堆", "每个父节点都不小于子节点，堆顶是最大值。"),
            ("优先队列", "每次取出优先级最高或最低的元素。"),
        ],
        "process": ["插入元素放到数组末尾", "和父节点比较并上浮", "删除堆顶后用末尾元素补位", "和更合适的孩子比较并下沉"],
        "template": "def parent(i): return (i - 1) // 2\ndef left(i): return 2 * i + 1\ndef right(i): return 2 * i + 2",
        "mistakes": ["以为堆数组是整体有序", "下沉时没有选择更小或更大的孩子", "堆顶删除后忘记恢复堆性质"],
        "exercises": ["最小堆插入 2 的上浮过程", "删除堆顶后第一步做什么？", "优先队列插入和取堆顶复杂度是多少？"],
        "summary": "堆只保证局部父子关系，因此能快速取堆顶，但不能快速查任意元素。",
    },
    {
        "filename": "heap-applications.pdf",
        "title": "堆的应用专题",
        "subtitle": "Top-K、中位数维护和调度",
        "objectives": ["能判断 Top-K 用大根堆还是小根堆", "能理解双堆维护中位数", "能解释优先队列调度"],
        "concepts": [
            ("Top-K", "用大小为 k 的堆维护当前最优的 k 个元素。"),
            ("双堆", "一个最大堆保存较小一半，一个最小堆保存较大一半。"),
            ("调度", "优先级高的任务先出队。"),
        ],
        "process": ["确定要保留最大还是最小", "堆大小超过 k 时弹出不需要的元素", "每次更新后堆顶代表当前答案"],
        "template": "for x in nums:\n    heappush(heap, x)\n    if len(heap) > k:\n        heappop(heap)\nanswer = heap[0]",
        "mistakes": ["Top-K 最大值误用最大堆导致难以淘汰", "忘记限制堆大小为 k", "中位数双堆没有保持大小平衡"],
        "exercises": ["求第 k 大元素应该维护什么堆？", "两个堆如何得到中位数？", "任务调度为什么不用普通队列？"],
        "summary": "堆应用的共同点是只关心当前最优元素，而不是全部排序。",
    },
    {
        "filename": "heap-exercises.pdf",
        "title": "堆操作练习题",
        "subtitle": "建堆、插入、删除和复杂度",
        "objectives": ["能手推堆数组变化", "能写出上浮下沉步骤", "能分析堆操作复杂度"],
        "concepts": [
            ("建堆", "从最后一个非叶子节点开始向前下沉。"),
            ("上浮", "新元素比父节点更优时交换。"),
            ("下沉", "父节点不满足堆性质时与更优孩子交换。"),
        ],
        "process": ["找到父子下标", "比较父子优先级", "必要时交换", "重复直到性质恢复"],
        "template": "while i > 0 and heap[i] < heap[parent(i)]:\n    swap(i, parent(i))\n    i = parent(i)",
        "mistakes": ["从叶子节点开始建堆但做了无用操作", "下沉只和左孩子比较", "把建堆复杂度误写成 O(n log n)"],
        "exercises": ["数组 [5,3,8,1] 建最小堆", "最小堆插入 0 后怎么上浮？", "删除堆顶后如何下沉？"],
        "summary": "堆题要把数组下标和树形父子关系对应起来。",
    },
    {
        "filename": "hash-lecture.pdf",
        "title": "哈希表基础讲义",
        "subtitle": "哈希函数、冲突和扩容",
        "objectives": ["能解释 key 如何定位桶", "能说出两类冲突处理", "能理解平均 O(1) 的条件"],
        "concepts": [
            ("哈希函数", "把 key 映射成数组下标。"),
            ("冲突", "不同 key 映射到同一位置。"),
            ("负载因子", "元素数量与桶数量的比例，过高会影响性能。"),
        ],
        "process": ["计算 hash(key)", "定位 bucket index", "如果冲突则按策略处理", "负载过高时扩容并重新映射"],
        "template": "index = hash(key) % capacity\nbucket = table[index]\nsearch_or_insert(bucket, key, value)",
        "mistakes": ["认为哈希表永远 O(1)", "扩容后没有重新计算位置", "只比较 hash 值不比较 key"],
        "exercises": ["为什么两个 key 可能落到同一个桶？", "链地址法如何处理冲突？", "负载因子过高会发生什么？"],
        "summary": "哈希表用空间换时间，性能依赖哈希函数和冲突控制。",
    },
    {
        "filename": "hash-applications.pdf",
        "title": "哈希表应用专题",
        "subtitle": "字典、集合、缓存和计数",
        "objectives": ["能识别哈希表题型", "能用 map 做计数和索引", "能理解缓存查找"],
        "concepts": [
            ("字典 Map", "key 到 value 的映射。"),
            ("集合 Set", "只关心元素是否存在。"),
            ("计数表", "用 key 记录元素，用 value 记录出现次数。"),
            ("缓存", "用哈希表快速定位缓存项。"),
        ],
        "process": ["确定 key 是什么", "确定 value 记录什么", "遍历时更新 map", "查询时判断 key 是否存在"],
        "template": "count = {}\nfor x in nums:\n    count[x] = count.get(x, 0) + 1",
        "mistakes": ["key 选错导致信息不足", "忽略重复元素", "只用数组导致无法处理稀疏 key"],
        "exercises": ["两数之和为什么适合哈希表？", "统计字符频率时 key 和 value 分别是什么？", "集合如何判断重复元素？"],
        "summary": "哈希应用题先设计 key，再设计 value。",
    },
    {
        "filename": "hash-exercises.pdf",
        "title": "哈希表练习题",
        "subtitle": "冲突、负载因子和性能分析",
        "objectives": ["能分析哈希表性能", "能处理冲突场景", "能说明最坏情况"],
        "concepts": [
            ("平均情况", "哈希分布均匀且负载适中时，查找接近 O(1)。"),
            ("最坏情况", "大量冲突可能退化为 O(n)。"),
            ("再哈希", "扩容后重新计算所有 key 的位置。"),
        ],
        "process": ["给定 key 计算位置", "检查是否冲突", "按链地址法或开放寻址处理", "写出平均与最坏复杂度"],
        "template": "put(key, val):\n    i = hash(key) % m\n    for entry in table[i]:\n        if entry.key == key: update\n    append new entry",
        "mistakes": ["只写平均复杂度不写最坏情况", "开放寻址探测时没有处理删除标记", "扩容后沿用旧下标"],
        "exercises": ["m=7 时 key=15 应落在哪个桶？", "链地址法中桶里有多个元素怎么办？", "负载因子超过阈值为什么要扩容？"],
        "summary": "哈希表题既要会用，也要知道它为什么可能变慢。",
    },
    {
        "filename": "review-outline.pdf",
        "title": "数据结构复习大纲",
        "subtitle": "考前按结构快速回顾",
        "objectives": ["能按章节建立复习顺序", "能抓住高频考点", "能把错题归类回知识点"],
        "concepts": [
            ("线性结构", "数组、链表、栈、队列关注存储和操作顺序。"),
            ("树结构", "二叉树、BST、堆关注层次关系。"),
            ("哈希结构", "关注映射、冲突和平均复杂度。"),
            ("复杂度", "所有结构都要能说明时间和空间成本。"),
        ],
        "process": ["先扫概念和复杂度", "再练操作状态变化", "最后做综合题和错题复盘"],
        "template": "复习顺序：\n1. 结构定义\n2. 基本操作\n3. 复杂度\n4. 易错边界\n5. 典型题",
        "mistakes": ["只刷题不补概念", "复杂度只背结论", "错题没有归类到知识点"],
        "exercises": ["列出每章最容易错的一点", "写出每种结构的典型应用", "给自己出 5 道选型题"],
        "summary": "考前复习要压缩路径：概念、操作、复杂度、边界、题型。",
    },
    {
        "filename": "mock-exam.pdf",
        "title": "期末模拟试卷",
        "subtitle": "综合模拟与解析方向",
        "objectives": ["能检验全章掌握度", "能发现薄弱结构", "能按解析复盘错题"],
        "concepts": [
            ("选择题", "重点考定义、复杂度、选型和边界。"),
            ("简答题", "重点考能否说清原因。"),
            ("编程题", "重点考操作步骤和边界条件。"),
        ],
        "process": ["先限时完成", "再对照解析定位错误", "最后把错题带入智能体追问"],
        "template": "答题复盘：\n题号：____\n错因：概念 / 操作 / 复杂度 / 边界\n补救：回看 ____ 资料",
        "mistakes": ["选择题只凭印象", "简答题没有复杂度理由", "编程题漏空结构和单元素结构"],
        "exercises": ["栈和队列的核心区别是什么？", "反转链表为什么要保存 next？", "BST 中序遍历结果有什么性质？", "哈希表平均 O(1) 的前提是什么？"],
        "summary": "模拟卷的价值不只是分数，而是把薄弱点暴露出来。",
    },
]


class CoursePDF(FPDF):
    def __init__(self, title: str):
        super().__init__("P", "mm", "A4")
        self.course_title = title
        self.set_auto_page_break(auto=True, margin=18)
        self.set_margins(16, 18, 16)
        self.add_font("msyh", "", REGULAR_FONT, uni=True)
        self.add_font("msyh", "B", BOLD_FONT, uni=True)

    def header(self):
        if self.page_no() == 1:
            return
        self.set_font("msyh", "", 8)
        self.set_text_color(138, 126, 112)
        self.cell(0, 7, self.course_title, align="R")
        self.ln(9)

    def footer(self):
        self.set_y(-14)
        self.set_font("msyh", "", 8)
        self.set_text_color(160, 150, 138)
        self.cell(0, 8, f"{self.page_no()}", align="C")

    def title_page(self, title: str, subtitle: str):
        self.add_page()
        self.set_fill_color(251, 249, 244)
        self.rect(0, 0, 210, 297, "F")
        self.set_y(54)
        self.set_font("msyh", "", 10)
        self.set_text_color(150, 128, 112)
        self.cell(0, 8, "DATA STRUCTURE AGENT", align="C")
        self.ln(14)
        self.set_font("msyh", "B", 26)
        self.set_text_color(45, 39, 34)
        self.multi_cell(0, 13, title, align="C")
        self.ln(5)
        self.set_font("msyh", "", 13)
        self.set_text_color(104, 92, 82)
        self.multi_cell(0, 8, subtitle, align="C")
        self.ln(22)
        self.set_draw_color(198, 176, 160)
        self.line(70, self.get_y(), 140, self.get_y())
        self.ln(14)
        self.set_font("msyh", "", 10)
        self.set_text_color(130, 118, 106)
        self.multi_cell(0, 7, "统一模板：学习目标 · 核心概念 · 状态变化 · 操作模板 · 易错点 · 练习", align="C")

    def section(self, title: str):
        self.ln(5)
        self.set_font("msyh", "B", 15)
        self.set_text_color(45, 39, 34)
        self.cell(0, 9, title)
        self.ln(10)
        self.set_draw_color(224, 214, 202)
        self.line(16, self.get_y(), 194, self.get_y())
        self.ln(4)

    def paragraph(self, text: str):
        self.set_font("msyh", "", 10.8)
        self.set_text_color(58, 53, 48)
        self.multi_cell(0, 7, text)
        self.ln(1)

    def bullet(self, text: str):
        self.set_font("msyh", "", 10.5)
        self.set_text_color(58, 53, 48)
        x = self.l_margin
        self.set_x(x)
        self.cell(6, 7, "-")
        self.set_x(x + 8)
        self.multi_cell(170, 7, text)
        self.set_x(x)

    def concept(self, name: str, text: str):
        self.set_fill_color(255, 253, 248)
        self.set_draw_color(228, 218, 206)
        x, y = self.get_x(), self.get_y()
        self.rect(x, y, 178, 18, "DF")
        self.set_xy(x + 4, y + 3)
        self.set_font("msyh", "B", 10.5)
        self.set_text_color(45, 39, 34)
        self.cell(32, 6, name)
        self.set_font("msyh", "", 10)
        self.set_text_color(80, 70, 62)
        self.multi_cell(136, 6, text)
        self.set_y(y + 21)

    def code_block(self, text: str):
        self.ln(2)
        self.set_fill_color(244, 240, 234)
        self.set_draw_color(226, 215, 202)
        self.set_font("msyh", "", 9)
        self.set_text_color(52, 48, 44)
        for line in text.splitlines():
            self.set_x(self.l_margin)
            self.cell(178, 5.5, line, border=0, ln=1, fill=True)
        self.ln(2)

    def simple_table(self, rows: list[list[str]]):
        widths = [42, 43, 43, 46]
        for row_index, row in enumerate(rows):
            self.set_font("msyh", "B" if row_index == 0 else "", 9.4)
            self.set_fill_color(239, 233, 225) if row_index == 0 else self.set_fill_color(255, 253, 248)
            self.set_text_color(52, 48, 44)
            for index, cell in enumerate(row):
                self.cell(widths[index], 8, cell, border=1, align="C", fill=True)
            self.ln()
        self.ln(3)


def render_material(item: dict) -> dict:
    pdf = CoursePDF(item["title"])
    pdf.title_page(item["title"], item["subtitle"])

    pdf.add_page()
    pdf.section("一、学习目标")
    for objective in item["objectives"]:
        pdf.bullet(objective)

    pdf.section("二、核心概念")
    for name, text in item["concepts"]:
        pdf.concept(name, text)

    if item.get("table"):
        pdf.section("三、速查表")
        pdf.simple_table(item["table"])
        next_index = "四"
    else:
        next_index = "三"

    pdf.section(f"{next_index}、状态变化或解题路径")
    for step_index, step in enumerate(item["process"], 1):
        pdf.bullet(f"{step_index}. {step}")

    pdf.section("操作模板")
    pdf.code_block(item["template"])

    pdf.section("易错点")
    for mistake in item["mistakes"]:
        pdf.bullet(mistake)

    pdf.section("练习题")
    for exercise in item["exercises"]:
        pdf.bullet(exercise)

    pdf.section("小结")
    pdf.paragraph(item["summary"])

    output = OUTPUT_DIR / item["filename"]
    pdf.output(str(output))
    pages = len(PdfReader(str(output)).pages)
    size_kb = round(output.stat().st_size / 1024)
    return {"filename": item["filename"], "pages": pages, "sizeKB": size_kb}


def main():
    print("Generating course PDFs...")
    manifest = []
    for item in MATERIALS:
        result = render_material(item)
        manifest.append(result)
        print(f"  {result['filename']}: {result['pages']} pages, {result['sizeKB']} KB")

    manifest_path = OUTPUT_DIR / "materials-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Done. {len(manifest)} PDFs generated in {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
