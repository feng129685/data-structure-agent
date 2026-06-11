# Interactive Multi-Agent Classroom Turns Design

## Goal

Upgrade the classroom discussion from a one-shot multi-role answer into an interactive classroom loop. A student should ask one data-structure question, hear a few role-based responses, then be paused at a meaningful point to answer a question. After the student responds, only the most relevant roles continue the discussion.

The feature should feel like a live class: the teacher guides, the assistant checks edge cases, classmates ask or expose confusion, and the system waits for the student at moments where participation improves learning.

## Confirmed Product Direction

Use a hybrid classroom flow:

- A stable default structure keeps every discussion understandable.
- A hidden classroom director can insert interaction points when the topic has a good pause moment.
- User feedback is handled in a classroom style: teacher judges the main idea, assistant adds edge cases, classmates ask follow-up questions.
- User answers can be either option-based or free-form. Simple concept checks use buttons; deeper reasoning uses text input.

## Recommended Approach

Use a hidden "classroom director" protocol inside the existing classroom feature. The director is not shown as a visible role. It decides:

- which visible roles should speak in the current turn,
- whether to pause for the student,
- what kind of student response is expected,
- which roles should respond after the student answers,
- when to summarize and offer next actions.

This is better than making every role reply every round, because it keeps the discussion readable and closer to a real classroom rhythm.

## Classroom Loop

The default loop is:

1. Student asks a question.
2. Teacher gives the core conclusion.
3. Assistant or one classmate adds one important angle.
4. Director decides whether to pause.
5. If pausing, the UI renders a classroom question card.
6. Student answers by choosing an option or typing.
7. Only 1 to 3 relevant roles respond.
8. The system either asks one more question or summarizes with next actions.

The classroom should not force all selected roles to speak every round. Selected roles are available voices, not mandatory speakers.

## Interaction Point Types

### Option Check

Use when the answer can be checked quickly.

Example:

Question: "链表头插法第一步应该做什么？"

Options:

- A. 先让新节点 next 指向原 head
- B. 先让 head 指向新节点

The UI should show option buttons and wait for one click.

### Free Answer

Use when the student needs to explain reasoning.

Example:

Question: "你能说说为什么先改 head 可能导致原链表丢失吗？"

The UI should show a compact text input and a submit button.

### Reflection Prompt

Use after a partial answer or confusion.

Example:

Question: "你刚才提到了 next，那 prev 在双链表里还需要怎么处理？"

This can be free-form by default.

## Front-End State Model

Extend `state.classroom` with a small turn state:

```js
{
  phase: "idle" | "opening" | "waiting" | "responding" | "summary",
  turnId: "short-id",
  pendingPrompt: {
    type: "choice" | "free",
    question: "...",
    options: [{ id: "A", text: "..." }],
    expected: "A",
    targetConcept: "链表指针顺序"
  },
  turnHistory: []
}
```

This state stays front-end only for this iteration and is saved with the existing local classroom state.

## Model Output Contract

The classroom model response should still be strict JSON, but with optional turn fields:

```json
{
  "phase": "waiting",
  "messages": [
    { "role": "teacher", "speaker": "主讲老师", "tag": "结论", "content": "..." },
    { "role": "assistant", "speaker": "助教", "tag": "边界", "content": "..." }
  ],
  "interaction": {
    "type": "choice",
    "question": "链表头插法第一步应该做什么？",
    "options": [
      { "id": "A", "text": "先让新节点 next 指向原 head" },
      { "id": "B", "text": "先让 head 指向新节点" }
    ],
    "expected": "A",
    "targetConcept": "链表指针顺序"
  },
  "board": [
    { "title": "当前结论", "body": "..." }
  ],
  "next": []
}
```

When responding to a student answer, the response should include:

```json
{
  "phase": "summary",
  "messages": [
    { "role": "teacher", "speaker": "主讲老师", "tag": "判断", "content": "..." },
    { "role": "assistant", "speaker": "助教", "tag": "补充", "content": "..." },
    { "role": "peer", "speaker": "同学", "tag": "追问", "content": "..." }
  ],
  "interaction": null,
  "board": [
    { "title": "本轮小结", "body": "..." }
  ],
  "next": [
    { "label": "生成动画演示", "action": "animate", "prompt": "..." },
    { "label": "回到伴学追问", "action": "coach", "prompt": "..." }
  ]
}
```

Rules:

- Initial turn should include 2 to 3 role messages before any pause.
- Follow-up turn should include 1 to 3 role messages.
- Do not make every selected role speak unless genuinely useful.
- `interaction` can be null when no pause is needed.
- Message content should stay short and classroom-like.
- Visible roles must come from the selected role ids.

## UI Design

Add a compact classroom question card inside the discussion stream when `state.classroom.pendingPrompt` exists.

The card should contain:

- a small label such as "等你回答",
- the interaction question,
- option buttons for `choice`,
- a compact text box for `free`,
- a submit button,
- a short hint that the class will continue after the student answers.

The card should visually match the current soft rounded classroom style and avoid adding another large panel.

## User Answer Handling

For choice questions:

- Clicking an option appends a user classroom message.
- The app calls the model with the original question, classroom history, pending prompt, selected option, and expected answer.
- If the model call fails, fallback messages should still judge the answer and explain the key point.

For free answers:

- Submitting text appends a user classroom message.
- The app asks the model to classify the answer as mostly correct, partially correct, or needs correction.
- The visible response should come from the most relevant 1 to 3 roles.

## Fallback Behavior

If the model cannot return valid JSON:

- For the initial question, use a deterministic fallback with teacher and assistant messages plus one choice interaction.
- For a student answer, use a deterministic fallback with teacher feedback, assistant edge-case note, and one next action.

Fallbacks should keep the classroom usable without pretending every role is live.

## Testing Plan

- Verify the prompt contract markers exist in `prototype.html`.
- Verify `normalizeClassroomResult` accepts `interaction` and `phase`.
- Verify classroom initial submission can render a waiting prompt.
- Verify choice click appends a user response and clears pending prompt.
- Verify free answer submit appends a user response and clears pending prompt.
- Verify follow-up response can include only a subset of selected roles.
- Verify desktop and mobile classroom views have no horizontal overflow.
- Run existing orchestrator and animation-loop verification scripts to avoid regressions.

## Scope Boundaries

This iteration does not need a new backend endpoint, database migration, voice/audio mode, teacher-editable role editor, or a full grading system. It only changes the front-end classroom state, prompt contract, rendering, and model/fallback handling.

