<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import type { SseEvent } from "../../shared/api";
import type { AiReadiness, ChatResponse, ChatSession, ChatSessionSummary, ChatSource } from "../../shared/types/contracts";
import UserFrame from "../components/UserFrame.vue";
import UserState from "../components/UserState.vue";
import { presentUserError, type UserErrorPresentation } from "../errors";
import { userApi } from "../runtime";

type MessageState = "complete" | "streaming" | "stopped" | "error";

interface ConversationMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  sources: ChatSource[];
  state: MessageState;
  createdAt?: string;
  errorCode?: string;
}

interface ChatAttempt {
  prompt: string;
  chapterId?: string;
  sessionId?: string;
}

interface ChatError extends UserErrorPresentation {
  code?: string;
}

const route = useRoute();
const prompt = ref("");
const chapterId = ref(readQueryString(route.query.chapterId));
const messages = ref<ConversationMessage[]>([]);
const sessions = ref<ChatSessionSummary[]>([]);
const activeSessionId = ref<string | null>(null);
const readiness = ref<AiReadiness | null>(null);
const readinessLoading = ref(false);
const readinessError = ref<UserErrorPresentation | null>(null);
const sessionsLoading = ref(false);
const sessionsError = ref<UserErrorPresentation | null>(null);
const sessionLoading = ref(false);
const sessionError = ref<UserErrorPresentation | null>(null);
const chatError = ref<ChatError | null>(null);
const deletingSessionId = ref<string | null>(null);
const deleteConfirmationId = ref<string | null>(null);
const retryAttempt = ref<ChatAttempt | null>(null);
const sendPhase = ref<"idle" | "checking" | "streaming">("idle");
const newConversationButton = ref<HTMLButtonElement | null>(null);
const sessionButtons = new Map<string, HTMLButtonElement>();

let messageSequence = 0;
let activeController: AbortController | null = null;
let sessionLoadVersion = 0;
let readinessLoadVersion = 0;

const isBusy = computed(() => sendPhase.value !== "idle");
const isStreaming = computed(() => sendPhase.value === "streaming");
const canSubmit = computed(() => Boolean(prompt.value.trim()) && !isBusy.value);
const activeSession = computed(() => sessions.value.find((item) => item.id === activeSessionId.value) ?? null);
const readinessSummary = computed(() => {
  if (readinessLoading.value) return "正在核验当前问答条件";
  if (readinessError.value) return readinessError.value.message;
  if (!readiness.value) return "尚未读取当前问答条件";
  return readiness.value.allowFormalGeneration ? "当前可开始正式问答" : "当前暂不能开始正式问答";
});
const readinessTone = computed(() => {
  if (readinessLoading.value) return "neutral";
  if (readinessError.value || !readiness.value?.allowFormalGeneration) return "warning";
  return "success";
});

watch(
  () => route.query.chapterId,
  (value) => {
    if (!isBusy.value) chapterId.value = readQueryString(value);
  },
);

onMounted(() => {
  void refreshReadiness();
  void loadSessions();
});

onBeforeUnmount(() => {
  activeController?.abort();
  activeController = null;
});

function readQueryString(value: unknown): string {
  if (typeof value === "string") return value.trim();
  if (Array.isArray(value) && typeof value[0] === "string") return value[0].trim();
  return "";
}

function nextMessageId(): string {
  messageSequence += 1;
  return `chat-message-${messageSequence}`;
}

function makeAttempt(value: string): ChatAttempt {
  const selectedChapterId = chapterId.value.trim();
  return {
    prompt: value,
    ...(selectedChapterId ? { chapterId: selectedChapterId } : {}),
    ...(activeSessionId.value ? { sessionId: activeSessionId.value } : {}),
  };
}

function updateMessage(id: string, update: Partial<ConversationMessage>): void {
  const message = messages.value.find((item) => item.id === id);
  if (message) Object.assign(message, update);
}

function chatErrorFrom(cause: unknown): ChatError {
  const error = cause as { code?: unknown } | null;
  return { ...presentUserError(cause), ...(typeof error?.code === "string" ? { code: error.code } : {}) };
}

function streamErrorFrom(value: unknown): ChatError {
  const record = asRecord(value);
  const code = typeof record?.code === "string" ? record.code : "CHAT_STREAM_ERROR";
  const serverMessage = typeof record?.message === "string" ? record.message.trim() : "";
  const evidenceUnavailable = code === "CHAT_EVIDENCE_UNAVAILABLE";
  return {
    kind: evidenceUnavailable ? "validation" : "service",
    title: evidenceUnavailable ? "当前问题缺少可用课程依据" : "本次回答未完成",
    message: serverMessage || (evidenceUnavailable ? "请调整问题或切换到已有审核资料的章节后再试。" : "服务已结束本次回答，请稍后重试。"),
    retryable: true,
    code,
  };
}

function unfinishedStreamError(): ChatError {
  return {
    kind: "service",
    title: "回答流未正常结束",
    message: "服务没有返回完整答案。可以重试本次问题。",
    retryable: true,
    code: "CHAT_STREAM_INCOMPLETE",
  };
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function parsedEventValue(event: SseEvent<unknown>): unknown {
  if (event.parsed !== undefined) return event.parsed;
  try {
    return JSON.parse(event.data) as unknown;
  } catch {
    return event.data;
  }
}

function isChatSource(value: unknown): value is ChatSource {
  const source = asRecord(value);
  return Boolean(source
    && typeof source.id === "string"
    && typeof source.chapterId === "string"
    && typeof source.title === "string"
    && typeof source.content === "string"
    && typeof source.source === "string"
    && (typeof source.pageLabel === "string" || source.pageLabel === null)
    && typeof source.score === "number"
    && typeof source.evidenceHash === "string");
}

function sourcesFrom(value: unknown): ChatSource[] {
  const record = asRecord(value);
  const candidates = Array.isArray(value) ? value : Array.isArray(record?.sources) ? record.sources : [];
  return candidates.filter(isChatSource);
}

function deltaFrom(value: unknown): string {
  if (typeof value === "string") return value;
  const record = asRecord(value);
  if (typeof record?.content === "string") return record.content;
  if (typeof record?.delta === "string") return record.delta;
  return "";
}

function responseFrom(value: unknown): ChatResponse | null {
  const record = asRecord(value);
  if (!record || typeof record.answer !== "string") return null;
  const sessionId = typeof record.sessionId === "string" || record.sessionId === null ? record.sessionId : undefined;
  return {
    answer: record.answer,
    sessionId,
    sources: sourcesFrom(record.sources),
    persisted: record.persisted === true,
  };
}

function historyMessages(session: ChatSession): ConversationMessage[] {
  return session.messages.map((message) => ({
    id: `history-${message.id}`,
    role: message.role,
    content: message.content,
    sources: message.sources,
    state: "complete",
    createdAt: message.createdAt,
  }));
}

function readinessInput(promptValue = ""): { operation: "CHAT"; chapterId?: string; prompt?: string } {
  const selectedChapterId = chapterId.value.trim();
  const selectedPrompt = promptValue.trim();
  return {
    operation: "CHAT",
    ...(selectedChapterId ? { chapterId: selectedChapterId } : {}),
    ...(selectedPrompt ? { prompt: selectedPrompt } : {}),
  };
}

async function refreshReadiness(promptValue = ""): Promise<AiReadiness | null> {
  const version = ++readinessLoadVersion;
  readinessLoading.value = true;
  readinessError.value = null;
  try {
    const value = await userApi.getReadiness(readinessInput(promptValue));
    if (version !== readinessLoadVersion) return readiness.value;
    readiness.value = value;
    return value;
  } catch (cause) {
    if (version !== readinessLoadVersion) return readiness.value;
    readinessError.value = presentUserError(cause);
    return null;
  } finally {
    if (version === readinessLoadVersion) readinessLoading.value = false;
  }
}

async function loadSessions(): Promise<void> {
  sessionsLoading.value = true;
  sessionsError.value = null;
  try {
    sessions.value = await userApi.listChatSessions();
    if (activeSessionId.value && !sessions.value.some((item) => item.id === activeSessionId.value)) {
      activeSessionId.value = null;
    }
  } catch (cause) {
    sessionsError.value = presentUserError(cause);
  } finally {
    sessionsLoading.value = false;
  }
}

async function openSession(sessionId: string): Promise<void> {
  if (isBusy.value) return;
  const version = ++sessionLoadVersion;
  activeSessionId.value = sessionId;
  sessionLoading.value = true;
  sessionError.value = null;
  chatError.value = null;
  retryAttempt.value = null;
  try {
    const session = await userApi.getChatSession(sessionId);
    if (version !== sessionLoadVersion) return;
    messages.value = historyMessages(session);
    if (session.chapterId) chapterId.value = session.chapterId;
  } catch (cause) {
    if (version !== sessionLoadVersion) return;
    sessionError.value = presentUserError(cause);
  } finally {
    if (version === sessionLoadVersion) sessionLoading.value = false;
  }
}

function startNewConversation(): void {
  if (isBusy.value) return;
  sessionLoadVersion += 1;
  activeSessionId.value = null;
  messages.value = [];
  sessionError.value = null;
  chatError.value = null;
  retryAttempt.value = null;
}

function requestDelete(sessionId: string): void {
  deleteConfirmationId.value = sessionId;
}

function cancelDelete(): void {
  deleteConfirmationId.value = null;
}

function setSessionButton(id: string, element: Element | null): void {
  if (element instanceof HTMLButtonElement) sessionButtons.set(id, element);
  else sessionButtons.delete(id);
}

async function deleteSession(sessionId: string): Promise<void> {
  if (isBusy.value || deletingSessionId.value) return;
  deletingSessionId.value = sessionId;
  sessionsError.value = null;
  try {
    await userApi.deleteChatSession(sessionId);
    const deletedIndex = sessions.value.findIndex((item) => item.id === sessionId);
    sessions.value = sessions.value.filter((item) => item.id !== sessionId);
    if (activeSessionId.value === sessionId) startNewConversation();
    deleteConfirmationId.value = null;
    await nextTick();
    const nextSession = sessions.value[deletedIndex] ?? sessions.value[deletedIndex - 1];
    if (nextSession) sessionButtons.get(nextSession.id)?.focus();
    else newConversationButton.value?.focus();
  } catch (cause) {
    sessionsError.value = presentUserError(cause);
  } finally {
    deletingSessionId.value = null;
  }
}

function readinessBlockedMessage(value: AiReadiness): string {
  if (value.blockingReasons.length) return value.blockingReasons.join("；");
  if (!value.modelAvailable) return "模型服务当前不可用。";
  if (value.evidenceRequired && !value.evidenceAvailable) return "当前问题缺少可用课程依据。";
  return "当前问答条件尚未满足。";
}

function appendSessionFromResponse(response: ChatResponse, attempt: ChatAttempt): void {
  const nextSessionId = response.sessionId ?? attempt.sessionId;
  if (nextSessionId) activeSessionId.value = nextSessionId;
  void loadSessions();
}

async function consumeStream(attempt: ChatAttempt, replyId: string, signal: AbortSignal): Promise<void> {
  const response = await userApi.streamChat(attempt, signal);
  let completed = false;
  let streamedError: ChatError | null = null;

  streamEvents: for await (const event of response.events as AsyncGenerator<SseEvent<unknown>>) {
    const value = parsedEventValue(event);
    if (event.event === "sources") {
      updateMessage(replyId, { sources: sourcesFrom(value) });
      continue;
    }
    if (event.event === "delta") {
      const delta = deltaFrom(value);
      if (delta) {
        const current = messages.value.find((item) => item.id === replyId);
        updateMessage(replyId, { content: `${current?.content ?? ""}${delta}` });
      }
      continue;
    }
    if (event.event === "done") {
      const completedResponse = responseFrom(value);
      if (!completedResponse) {
        streamedError = unfinishedStreamError();
      } else {
        updateMessage(replyId, {
          content: completedResponse.answer,
          sources: completedResponse.sources,
          state: "complete",
          errorCode: undefined,
        });
        appendSessionFromResponse(completedResponse, attempt);
        completed = true;
      }
      break streamEvents;
    }
    if (event.event === "error") {
      streamedError = streamErrorFrom(value);
      break streamEvents;
    }
  }

  if (signal.aborted) {
    updateMessage(replyId, { state: "stopped" });
    return;
  }
  if (streamedError) {
    updateMessage(replyId, { state: "error", errorCode: streamedError.code });
    chatError.value = streamedError;
    return;
  }
  if (!completed) {
    const incomplete = unfinishedStreamError();
    updateMessage(replyId, { state: "error", errorCode: incomplete.code });
    chatError.value = incomplete;
  }
}

async function runAttempt(attempt: ChatAttempt): Promise<void> {
  if (isBusy.value) return;
  retryAttempt.value = attempt;
  chatError.value = null;
  sendPhase.value = "checking";

  const currentReadiness = await refreshReadiness(attempt.prompt);
  if (!currentReadiness) {
    chatError.value = readinessError.value ? { ...readinessError.value } : {
      kind: "unknown",
      title: "无法确认问答条件",
      message: "请稍后重试。",
      retryable: true,
    };
    sendPhase.value = "idle";
    return;
  }
  if (!currentReadiness.allowFormalGeneration) {
    chatError.value = {
      kind: "validation",
      title: "当前不能开始正式问答",
      message: readinessBlockedMessage(currentReadiness),
      retryable: true,
      code: "AI_READINESS_BLOCKED",
    };
    sendPhase.value = "idle";
    return;
  }

  const controller = new AbortController();
  activeController = controller;
  sendPhase.value = "streaming";
  const replyId = nextMessageId();
  messages.value.push({ id: replyId, role: "assistant", content: "", sources: [], state: "streaming" });

  try {
    await consumeStream(attempt, replyId, controller.signal);
  } catch (cause) {
    if (controller.signal.aborted || (cause as { name?: string } | null)?.name === "AbortError") {
      updateMessage(replyId, { state: "stopped" });
    } else {
      const error = chatErrorFrom(cause);
      updateMessage(replyId, { state: "error", errorCode: error.code });
      chatError.value = error;
    }
  } finally {
    if (activeController === controller) activeController = null;
    sendPhase.value = "idle";
  }
}

async function submitPrompt(): Promise<void> {
  const value = prompt.value.trim();
  if (!value || isBusy.value) return;
  const attempt = makeAttempt(value);
  messages.value.push({ id: nextMessageId(), role: "user", content: value, sources: [], state: "complete" });
  prompt.value = "";
  await runAttempt(attempt);
}

async function retryLastAttempt(): Promise<void> {
  if (!retryAttempt.value || isBusy.value) return;
  await runAttempt(retryAttempt.value);
}

function stopGeneration(): void {
  activeController?.abort();
}

function formatSessionTime(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString("zh-CN", { dateStyle: "short", timeStyle: "short" });
}

function quotaLabel(value: AiReadiness["quotaStatus"]): string {
  return {
    AVAILABLE: "配额可用",
    EXHAUSTED: "今日配额已用完",
    CONCURRENCY_LIMITED: "并发处理中",
    NOT_CONFIGURED: "配额未配置",
  }[value];
}
</script>

<template>
  <UserFrame>
    <section class="user-page" aria-labelledby="chat-title">
      <header class="user-page__heading">
        <div>
          <p class="user-page__eyebrow">课程陪练</p>
          <h1 id="chat-title">课程问答</h1>
          <p class="user-page__intro">回答仅在当前账户、已审核课程依据和模型配额均满足时生成。</p>
        </div>
        <div class="user-page__actions">
          <button ref="newConversationButton" class="user-action" data-testid="chat-new-conversation" type="button" :disabled="isBusy" @click="startNewConversation">新建对话</button>
          <RouterLink v-if="chapterId.trim()" class="user-action" data-testid="chat-animation" :to="{ path: '/user/animation', query: { chapterId: chapterId.trim(), from: 'coach' } }">查看本章算法</RouterLink>
          <button class="user-action" type="button" :disabled="readinessLoading || isBusy" @click="refreshReadiness()">刷新状态</button>
        </div>
      </header>

      <section class="user-panel user-chat__readiness" aria-live="polite" data-testid="chat-readiness">
        <div>
          <p class="user-page__eyebrow">生成条件</p>
          <h2>{{ readinessSummary }}</h2>
        </div>
        <div v-if="readiness" class="user-chat__readiness-grid">
          <span :data-state="readinessTone">{{ readiness.modelAvailable ? "模型可用" : "模型不可用" }}</span>
          <span :data-state="readinessTone">{{ readiness.evidenceRequired ? (readiness.evidenceAvailable ? "课程依据可用" : "课程依据不足") : "本操作不要求课程依据" }}</span>
          <span :data-state="readinessTone">{{ quotaLabel(readiness.quotaStatus) }}</span>
        </div>
        <p v-if="readiness && !readiness.allowFormalGeneration" class="inline-notice inline-notice--warning">{{ readinessBlockedMessage(readiness) }}</p>
        <UserState
          v-if="readinessError"
          :mode="readinessError.kind === 'permission' ? 'permission' : 'error'"
          :title="readinessError.title"
          :message="readinessError.message"
          :retry-label="readinessError.retryable ? '重新读取' : undefined"
          @retry="refreshReadiness()"
        />
      </section>

      <section class="user-chat" aria-label="课程问答内容">
        <UserState v-if="sessionLoading" mode="loading" title="正在载入历史会话" message="正在读取属于当前账户的对话记录。" />
        <UserState
          v-else-if="sessionError"
          :mode="sessionError.kind === 'permission' ? 'permission' : 'error'"
          :title="sessionError.title"
          :message="sessionError.message"
          :retry-label="sessionError.retryable ? '重新读取' : undefined"
          @retry="activeSessionId && openSession(activeSessionId)"
        />
        <UserState v-else-if="!messages.length" mode="empty" title="开始一段课程问答" message="输入问题后，将先核验当前课程依据和模型配额。" />
        <div v-else class="user-chat__messages" aria-live="polite" aria-relevant="additions text">
          <article v-for="message in messages" :key="message.id" class="user-chat__message" :data-role="message.role" :data-state="message.state">
            <p>{{ message.content || (message.state === 'streaming' ? '正在生成回答…' : '') }}</p>
            <small v-if="message.role === 'assistant' && message.state === 'streaming'">正在生成</small>
            <small v-else-if="message.role === 'assistant' && message.state === 'stopped'">已停止生成</small>
            <small v-else-if="message.role === 'assistant' && message.state === 'error'">回答未完成{{ message.errorCode ? ` · ${message.errorCode}` : '' }}</small>
            <time v-if="message.createdAt" class="user-chat__message-time" :datetime="message.createdAt">发送于 {{ formatSessionTime(message.createdAt) }}</time>
            <div v-if="message.sources.length" class="user-source-list" aria-label="课程依据">
              <details v-for="source in message.sources" :key="`${source.id}-${source.evidenceHash}`">
                <summary>{{ source.title }}<span v-if="source.pageLabel"> · {{ source.pageLabel }}</span></summary>
                <p>{{ source.content }}</p>
                <p>{{ source.source }}</p>
              </details>
            </div>
          </article>
        </div>

        <form class="user-form user-chat__form" @submit.prevent="submitPrompt">
          <label>
            章节范围（可选）
            <input v-model="chapterId" name="chapterId" type="text" maxlength="64" autocomplete="off" :disabled="isBusy" placeholder="例如：stack" />
          </label>
          <label>
            问题
            <textarea v-model="prompt" data-testid="chat-prompt" name="prompt" maxlength="4000" :disabled="isBusy" placeholder="输入你希望结合课程资料理解的问题" required />
          </label>
          <div class="user-page__actions">
            <button class="user-action user-action--primary" data-testid="chat-send" type="submit" :disabled="!canSubmit">{{ sendPhase === 'checking' ? '正在核验…' : '发送问题' }}</button>
            <button v-if="isStreaming" class="user-action" data-testid="chat-stop" type="button" @click="stopGeneration">停止</button>
            <button v-if="chatError?.retryable && retryAttempt" class="user-action" data-testid="chat-retry" type="button" :disabled="isBusy" @click="retryLastAttempt">重试本次问题</button>
          </div>
        </form>

        <UserState
          v-if="chatError"
          :mode="chatError.kind === 'permission' ? 'permission' : 'error'"
          :title="chatError.title"
          :message="chatError.message"
          :retry-label="chatError.retryable && retryAttempt ? '重试本次问题' : undefined"
          @retry="retryLastAttempt"
        />
      </section>
    </section>

    <template #rail>
      <div class="user-rail-list">
        <div class="user-chat__rail-heading"><strong>历史会话</strong><button class="user-chat__quiet-action" type="button" :disabled="sessionsLoading || isBusy" @click="loadSessions">刷新</button></div>
        <p v-if="activeSession">当前会话：{{ activeSession.title }}</p>
        <UserState v-if="sessionsLoading" mode="loading" title="正在读取会话" message="" />
        <UserState
          v-else-if="sessionsError"
          :mode="sessionsError.kind === 'permission' ? 'permission' : 'error'"
          :title="sessionsError.title"
          :message="sessionsError.message"
          :retry-label="sessionsError.retryable ? '重新读取' : undefined"
          @retry="loadSessions"
        />
        <p v-else-if="!sessions.length">暂未保存课程问答会话。</p>
        <div v-else class="user-chat__sessions">
          <article v-for="session in sessions" :key="session.id" class="user-chat__session" :data-active="session.id === activeSessionId">
            <button :ref="(element) => setSessionButton(session.id, element as Element | null)" class="user-chat__session-open" data-testid="chat-session" type="button" :disabled="isBusy || deletingSessionId === session.id" @click="openSession(session.id)">
              <strong>{{ session.title }}</strong>
              <span>{{ session.messageCount }} 条消息 · {{ formatSessionTime(session.updatedAt) }}</span>
            </button>
            <div class="user-chat__session-actions">
              <button v-if="deleteConfirmationId !== session.id" class="user-chat__quiet-action" data-testid="chat-request-delete" type="button" :disabled="isBusy || deletingSessionId === session.id" @click="requestDelete(session.id)">删除</button>
              <template v-else>
                <button class="user-chat__quiet-action user-chat__quiet-action--danger" data-testid="chat-confirm-delete" type="button" :disabled="deletingSessionId === session.id" @click="deleteSession(session.id)">{{ deletingSessionId === session.id ? '删除中…' : '确认' }}</button>
                <button class="user-chat__quiet-action" type="button" :disabled="deletingSessionId === session.id" @click="cancelDelete">取消</button>
              </template>
            </div>
          </article>
        </div>
        <strong>当前范围</strong>
        <p>{{ chapterId || '全部已授权章节' }}</p>
      </div>
    </template>
  </UserFrame>
</template>

<style scoped>
.user-chat__readiness { display: grid; gap: .75rem; }
.user-chat__readiness h2 { margin: 0; font-size: 1rem; }
.user-chat__readiness-grid { display: flex; flex-wrap: wrap; gap: .45rem; }
.user-chat__readiness-grid span { padding: .3rem .45rem; border: 1px solid var(--line); border-radius: var(--radius-sm); color: var(--text-muted); font-family: var(--font-mono); font-size: .74rem; }
.user-chat__readiness-grid span[data-state="success"] { border-color: color-mix(in srgb, var(--accent) 58%, var(--line)); color: var(--accent-strong); }
.user-chat__readiness-grid span[data-state="warning"] { border-color: color-mix(in srgb, var(--warning) 58%, var(--line)); color: var(--warning); }
.user-chat__form { border-top: 1px solid var(--line); padding-top: 1rem; }
.user-chat__message[data-state="error"] { border-color: color-mix(in srgb, var(--danger) 48%, var(--line)); }
.user-chat__message[data-state="stopped"] { border-style: dashed; }
.user-chat__message-time { color: var(--text-muted); font-size: .72rem; }
.user-chat__rail-heading { display: flex; align-items: center; justify-content: space-between; gap: .5rem; }
.user-chat__quiet-action { min-height: 2rem; padding: .25rem .45rem; border: 0; background: transparent; color: var(--text-muted); cursor: pointer; font-size: .75rem; }
.user-chat__quiet-action:hover { color: var(--text); text-decoration: underline; }
.user-chat__quiet-action:disabled { cursor: not-allowed; opacity: .55; text-decoration: none; }
.user-chat__quiet-action--danger { color: var(--danger); }
.user-chat__sessions { display: grid; border-top: 1px solid var(--line); }
.user-chat__session { display: grid; gap: .35rem; padding: .55rem 0; border-bottom: 1px solid var(--line); }
.user-chat__session[data-active="true"] { border-left: 2px solid var(--accent); padding-left: .45rem; }
.user-chat__session-open { display: grid; gap: .2rem; padding: 0; border: 0; background: transparent; color: var(--text); cursor: pointer; text-align: left; }
.user-chat__session-open:disabled { cursor: not-allowed; opacity: .55; }
.user-chat__session-open strong { font-size: .82rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.user-chat__session-open span { color: var(--text-muted); font-size: .72rem; }
.user-chat__session-actions { display: flex; gap: .2rem; }
@media (max-width: 760px) { .user-chat__readiness-grid { display: grid; grid-template-columns: 1fr; } }
</style>
