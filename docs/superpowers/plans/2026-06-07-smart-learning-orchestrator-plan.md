# Smart Learning Orchestrator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Build the A+C+I stable iteration: cleaner tutor replies plus a compact next-step orchestrator that routes students to animation, classroom discussion, materials, or C compiler.

**Architecture:** Keep the single-file SPA. Add pure classifier/formatter helpers in prototype.html, append a next-step card after assistant replies, and reuse existing routing functions for animation, classroom, materials, and compiler.

**Tech Stack:** Vanilla HTML/CSS/JavaScript, existing Node server, existing /api/chat, existing Chrome/CDP verification.

---

### Task 1: Add Static Verification

**Files:**
- Create: C:\Users\Lenovo\Desktop\智能体\data-structure-agent\scripts\verify-orchestrator-static.js

- [ ] Create a Node script that reads prototype.html and checks for these markers: uildLearningOrchestratorPlan, enderLearningOrchestratorCard, openCompilerFromOrchestrator, data-learn-action, learning-orchestrator-card, and ormatAssistantForLearning.
- [ ] Run 
ode scripts/verify-orchestrator-static.js and confirm it fails before implementation.

### Task 2: Add Orchestrator Helpers

**Files:**
- Modify: C:\Users\Lenovo\Desktop\智能体\data-structure-agent\prototype.html

- [ ] Add ormatAssistantForLearning(text) near softenAssistantMarkdown and call it from sendMessage before saving the assistant reply.
- [ ] Add uildLearningOrchestratorPlan(userMessage, assistantReply) near the animation suggestion helpers.
- [ ] Add openCompilerFromOrchestrator(seedQuestion) near openClassroomDiscussion.
- [ ] Ensure process-heavy questions recommend animation first, code-heavy questions include compiler, conceptual confusion includes classroom, and broad/review questions include materials.

### Task 3: Render and Bind Next-Step Card

**Files:**
- Modify: C:\Users\Lenovo\Desktop\智能体\data-structure-agent\prototype.html

- [ ] Add enderLearningOrchestratorCard(plan) and ttachLearningOrchestratorListeners(root).
- [ ] Store the plan on the assistant message as learningPlan after a reply.
- [ ] Update enderMessages() so assistant messages render the card when message.learningPlan exists.
- [ ] Bind card buttons to existing actions.

### Task 4: Polish Card Styling

**Files:**
- Modify: C:\Users\Lenovo\Desktop\智能体\data-structure-agent\prototype.html

- [ ] Add CSS for .learning-orchestrator-card, .learning-orchestrator-actions, and .learning-orchestrator-btn.
- [ ] Keep the card compact and rounded.
- [ ] Add reduced-motion-safe entrance using only transform and opacity.

### Task 5: Verification and Deployment

**Files:**
- Verify: C:\Users\Lenovo\Desktop\智能体\data-structure-agent\prototype.html
- Verify: C:\Users\Lenovo\Desktop\智能体\data-structure-agent\server.js

- [ ] Run 
ode scripts/verify-orchestrator-static.js and confirm it passes.
- [ ] Run 
ode --check server.js.
- [ ] Run inline script syntax check for prototype.html.
- [ ] Use browser/CDP to verify a stack/list question shows the next-step card and the buttons route to animation/classroom/materials/compiler.
- [ ] Verify desktop and mobile no horizontal overflow.
- [ ] Deploy prototype.html to /home/feng/sites/data-structure-agent/index.html, /home/feng/sites/data-structure-agent/prototype.html, /var/www/data-structure-agent/index.html, and /var/www/data-structure-agent/prototype.html.
- [ ] Verify production source contains the new markers and production browser flow works.
