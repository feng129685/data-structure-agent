# Smart Learning Orchestrator Design

## Goal

Implement the A+C+I stable iteration: after a student asks a data-structure question, the assistant reply should be easy to read and should guide the student to the most useful next learning action: interactive animation, classroom discussion, chapter materials, or C compiler verification.

## Current Context

The app already has separate pages for coaching, classroom discussion, animation lab, chapter materials, and C compiler. prototype.html already includes model chat, animation generation, classroom entry, materials chapter routing, and compiler view routing. The current gap is orchestration: users still need to decide which page to open after a reply.

## Scope

This iteration stays frontend-first and works inside the existing single-file SPA.

It will add:
- A lightweight learning action classifier based on the user question, current scenario, and assistant answer.
- A compact next-step card appended after assistant replies.
- Action buttons for animation, classroom discussion, materials, and compiler.
- Stronger animation suggestion wording for process-heavy questions.
- Reply cleanup that removes heavy Markdown markers and encourages a consistent learning-answer shape.

It will not add:
- A new backend endpoint.
- A new database table.
- A new page.
- Teacher-editable workflow rules.

## User Experience

1. Student asks a question in 智能体伴学.
2. The assistant returns a cleaner answer.
3. A small card appears below the answer with one recommended next action and up to three secondary actions.
4. If the question is about state changes, pointer moves, stack/queue operations, tree traversal, heap adjustment, or hash collision, animation is recommended first.
5. If the question is conceptual or confusing, classroom discussion is recommended.
6. If the question mentions code or runtime behavior, the compiler action is shown.
7. If the question is broad or asks for review, materials are shown.
8. Buttons route to existing views and seed the target page with the current question where possible.

## UI Rules

The card should be calm and compact. It should not look like a dashboard or add clutter to the chat. Use the existing rounded, warm off-white, gray-brown style.

Required actions:
- Generate animation: calls sendAnimationRequest(targetScenario).
- Classroom discussion: calls openClassroomDiscussion(seedQuestion).
- Chapter materials: calls openMaterialsChapter(targetScenario).
- C compiler: calls openCompilerFromOrchestrator(seedQuestion) and opens the compiler view.

## Reply Formatting

The response cleanup should be conservative. It should remove excessive Markdown symbols from model output without damaging code blocks.

The assistant prompt sent to the model should explicitly ask for this shape:
- 一句话结论
- 简短解释
- 易错提醒
- 下一步建议

The frontend cleanup should additionally reduce repeated ###, repeated bold markers, and overly dense list formatting in rendered chat.

## Verification

- Static verification script confirms the orchestrator functions and CSS hooks exist.
- 
ode --check server.js passes.
- Inline scripts in prototype.html parse.
- Browser verification confirms a stack/list process question shows the next-step card, includes animation/classroom/material/compiler actions, and has no desktop/mobile horizontal overflow.
- Production deployment updates both index.html and prototype.html.
