# Interactive Classroom Turns Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the classroom discussion feature into a readable interactive classroom loop with a student answer checkpoint.

**Architecture:** Keep the feature inside the existing single-file SPA. Extend front-end classroom state, normalize the model JSON contract, render one compact interaction card inside the discussion stream, and add fallback behavior so the loop still works when the model response is unavailable or invalid.

**Tech Stack:** Vanilla HTML/CSS/JavaScript in `prototype.html`, Node verification scripts.

---

### Task 1: Add Static Verification

**Files:**
- Create: `scripts/verify-classroom-turns-static.js`

- [ ] Add a Node script that reads `prototype.html` and checks for the new classroom turn markers: `normalizeClassroomInteraction`, `renderClassroomInteractionCard`, `submitClassroomAnswer`, `buildClassroomFollowupPrompt`, `requestClassroomFollowup`, `generateClassroomAnswerFallback`, `data-classroom-choice`, `classroom-interaction-card`, and `pendingPrompt`.

- [ ] Run `node scripts\verify-classroom-turns-static.js`; it should fail before implementation and pass after implementation.

### Task 2: Extend Classroom State Safely

**Files:**
- Modify: `prototype.html`

- [ ] Add `phase`, `turnId`, `pendingPrompt`, and `turnHistory` to `defaultState.classroom`.

- [ ] Update `sanitizeClassroomState(savedClassroom)` so older saved state still loads and unsafe pending prompts are discarded.

### Task 3: Normalize Model Turn Output

**Files:**
- Modify: `prototype.html`

- [ ] Add `normalizeClassroomInteraction(result.interaction)` for `choice` and `free` prompts.

- [ ] Update `normalizeClassroomResult(result)` to return `{ phase, messages, interaction, board, next }` and allow `interaction: null`.

### Task 4: Update Prompt Contract

**Files:**
- Modify: `prototype.html`

- [ ] Update `buildClassroomPrompt(question)` so the hidden director can return an `interaction` and 2-3 short opening role messages.

- [ ] Add `buildClassroomFollowupPrompt(answerPayload)` and `requestClassroomFollowup(answerPayload)` for the second turn.

### Task 5: Render the Waiting Card

**Files:**
- Modify: `prototype.html`

- [ ] Add CSS for `.classroom-interaction-card`, `.classroom-choice-btn`, `.classroom-free-answer`, and related compact states.

- [ ] Add `renderClassroomInteractionCard(prompt)` and insert it into `renderClassroomDiscussion()` when `state.classroom.pendingPrompt` exists.

### Task 6: Handle Student Answers

**Files:**
- Modify: `prototype.html`

- [ ] Add `submitClassroomAnswer(answerPayload)` for both choice and free answers.

- [ ] Bind choice buttons and free-answer submit from the rendered card.

- [ ] Add `generateClassroomAnswerFallback(answerPayload)` and update initial fallback to include one choice checkpoint.

### Task 7: Verify

**Files:**
- Modify: `prototype.html`

- [ ] Run `node scripts\verify-classroom-turns-static.js`.

- [ ] Run existing static checks for orchestrator and animation loop.

- [ ] Run `node --check server.js`.

- [ ] Validate inline scripts from `prototype.html` with `new Function`.

- [ ] Open the local app in the browser and verify the classroom page can display the interaction card without layout clutter.
