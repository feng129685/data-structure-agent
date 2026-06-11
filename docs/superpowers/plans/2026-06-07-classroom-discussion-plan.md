# Multi-Agent Classroom Discussion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a clear one-click classroom discussion entry from 智能体伴学 and make the classroom use distinct role setting prompts.

**Architecture:** Keep the existing single-file SPA architecture. Modify `prototype.html` only: add a tutor entry button, a helper to open classroom with a seed question, role prompt fields, a stronger classroom prompt contract, and focused classroom CSS.

**Tech Stack:** Vanilla HTML/CSS/JavaScript, existing `/api/chat`, existing browser/CDP verification.

---

### Task 1: Classroom Entry From Tutor

**Files:**
- Modify: `C:\Users\Lenovo\Desktop\智能体\data-structure-agent\prototype.html`

- [ ] Add a compact `课堂讨论` action inside `renderAgentWorkspace()`.
- [ ] Add `getLatestUserQuestion()` to find the latest user message in the current session.
- [ ] Add `openClassroomDiscussion(seedQuestion)` to prefill `els.classroomInput`, switch to `classroom`, and preserve state.
- [ ] Bind `[data-open-classroom]` after rendering the agent workspace.

### Task 2: Role Setting Words

**Files:**
- Modify: `C:\Users\Lenovo\Desktop\智能体\data-structure-agent\prototype.html`

- [ ] Add a `prompt` field to each `classroomRoles` item.
- [ ] Update `buildClassroomPrompt(question)` so selected roles include `name`, `job`, and `prompt`.
- [ ] Keep role ids unchanged: `teacher`, `ta`, `curious`, `mistake`, `summarizer`.

### Task 3: Discussion-First Classroom UI

**Files:**
- Modify: `C:\Users\Lenovo\Desktop\智能体\data-structure-agent\prototype.html`

- [ ] Add CSS that makes classroom layout simpler and discussion-first.
- [ ] Visually downplay long role/mode descriptions.
- [ ] Keep role controls available but compact.
- [ ] Preserve board and next action panels.

### Task 4: Verification

**Files:**
- Verify: `C:\Users\Lenovo\Desktop\智能体\data-structure-agent\prototype.html`
- Verify: `C:\Users\Lenovo\Desktop\智能体\data-structure-agent\server.js`

- [ ] Run `node --check server.js`.
- [ ] Run inline script syntax check with `new Function(...)`.
- [ ] Use browser/CDP to verify `coach -> classroom` opens and pre-fills input.
- [ ] Verify desktop and mobile classroom overflow is `0`.
- [ ] Deploy `prototype.html` to server after local verification.
