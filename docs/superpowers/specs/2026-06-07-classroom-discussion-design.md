# Multi-Agent Classroom Discussion Design

## Goal

Add a clear "课堂讨论" entry inside the 智能体伴学 experience. A student should be able to move from normal tutoring into a teacher-student multi-agent discussion with one click, without configuring a complex role panel first.

The feature should make the product feel like a learning agent, not a role-setting demo. The classroom should help students understand one data-structure question through several classroom voices: explanation, boundary cases, misconceptions, follow-up questions, and summary.

## Current Context

The app already has a `classroom` view with role cards, discussion messages, board notes, next actions, and `/api/chat` model integration. The current issue is product clarity: the classroom feature sits as a separate navigation item and still feels like a configuration panel. The new iteration should expose it naturally from 智能体伴学 and simplify the classroom flow.

## Approaches

### Option A: Add a mode chip inside 智能体伴学

Add a `课堂讨论` chip beside existing agent mode chips. Clicking it opens the existing classroom view and pre-fills the classroom question from the current tutor input or the latest user message.

Pros: small change, easy to understand, keeps the tutor page clean.

Cons: users still land on the current classroom layout unless the classroom page is simplified.

### Option B: Embed discussion directly in the tutor chat

The normal chat stream could render multi-agent messages inline after a user asks a question.

Pros: no page transition.

Cons: likely makes 智能体伴学 cluttered again and mixes two different interaction models in one stream.

### Option C: Dedicated simplified discussion view launched from tutor

Keep the classroom as a dedicated view, but reshape it into a one-click discussion mode. 智能体伴学 gets a visible entry card/button. The classroom page defaults to selected roles, hides unnecessary configuration details by default, and focuses on input, discussion, board summary, and next steps.

Recommendation: Option C. It respects the user's repeated preference to avoid piling features into one page, while making the multi-agent mode feel accessible from the tutor.

## Recommended User Flow

1. User enters 智能体伴学.
2. In the mode strip or a compact action row, user sees `课堂讨论`.
3. Clicking it opens the classroom view.
4. If the tutor input has text, that text is copied into the classroom question box.
5. If the tutor input is empty, the app uses the latest user question in the current scenario.
6. If neither exists, the classroom shows a short placeholder based on the current chapter.
7. User clicks `开始课堂讨论`.
8. The model generates a short multi-agent discussion.
9. The page shows role messages, a teacher summary board, and next actions such as `回到伴学追问`, `生成动画演示`, or `查看章节资料`.

## Role Presets

Each role should have a distinct system-style setting stored in the front-end role data and included in `buildClassroomPrompt`.

- 主讲老师: gives a clear conclusion first, explains the concept in classroom language, avoids long Markdown, and keeps the answer structured.
- 助教: checks boundary cases, complexity, code implementation risks, and common exam traps.
- 爱提问的同学: asks why, asks for analogies, and turns vague points into concrete follow-up questions.
- 易错同学: intentionally voices common misunderstandings, so the teacher or assistant can correct them.
- 总结同学: turns the discussion into a short board note with conclusion, key steps, and one thing to review.

Default selected roles should be all five. Advanced role selection can remain available, but it should be visually secondary.

## UI Design

### Tutor Entry

In 智能体伴学, add a compact classroom entry near the mode chips or quick question area:

`课堂讨论`

Supporting copy should be short, for example:

`让老师、助教和同学围绕这个问题讨论一轮`

Click behavior:

- Copy current tutor textarea value to classroom input if present.
- Otherwise copy the latest user message in the current session.
- Switch to the classroom view.

### Classroom View

Simplify the classroom page into a discussion-first layout:

- Main panel: title, current chapter chips, question input, `开始课堂讨论`, discussion stream.
- Side panel: role presets collapsed or compact, board summary, next actions.
- Remove or visually downplay long role descriptions on first screen.
- Keep rounded soft visual style and current black/off-white/gray-brown palette.

## Prompt Contract

The model prompt should instruct the model to return strict JSON:

```json
{
  "messages": [
    { "role": "teacher", "speaker": "主讲老师", "tag": "讲解", "content": "..." }
  ],
  "board": [
    { "title": "结论", "body": "..." }
  ],
  "next": [
    { "label": "回到伴学追问", "action": "coach", "prompt": "..." }
  ]
}
```

Rules:

- `messages`: 4 to 6 items.
- Each message: under 90 Chinese characters.
- Roles must come from selected role ids.
- Messages must respond to each other, not speak independently.
- `board`: 2 to 4 items.
- `next`: 2 to 3 actions.
- Valid next actions: `coach`, `materials`, `animate`, `compiler`.

## Data Flow

- `openClassroomDiscussion(seedQuestion)` handles view switching and pre-fill.
- `renderAgentWorkspace()` adds the tutor entry button.
- `buildClassroomPrompt(question)` includes role settings and current scenario context.
- `requestClassroomDiscussion(question)` keeps using `/api/chat`.
- `parseClassroomAnswer()` keeps the existing JSON normalization and fallback behavior.

## Error Handling

- If the model call fails, use the existing fallback classroom discussion.
- If login is required, show login and preserve the seed question where possible.
- If JSON parsing fails, normalize to fallback messages using selected roles.
- If there is no seed question, use a chapter-specific placeholder question.

## Test Plan

- Syntax-check `server.js`.
- Syntax-check inline scripts in `prototype.html`.
- Verify clicking `课堂讨论` from 智能体伴学 opens classroom view.
- Verify current tutor input is copied into classroom input.
- Verify empty tutor input falls back to latest user message or chapter placeholder.
- Verify classroom discussion still generates with model fallback.
- Verify desktop and mobile classroom layouts have no horizontal overflow.

## Scope Boundaries

This iteration should not add a new database table, new backend endpoint, or persistent per-user custom role editor. Role setting words are static presets for now. A future version can allow teachers to edit role prompts.
