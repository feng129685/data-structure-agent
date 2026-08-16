<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import type { AnimationDefinition, AnimationStep, DsvpEvidenceContext, DsvpRequest, DsvpStructure } from "../../shared/types/contracts";
import { createAnimationPlayback, type AnimationPlayback } from "../animation-playback";
import UserFrame from "../components/UserFrame.vue";
import UserState from "../components/UserState.vue";
import { presentUserError, type UserErrorPresentation } from "../errors";
import { userApi } from "../runtime";

const route = useRoute();
const animationSources = ["chapter", "coach", "classroom", "home"] as const;
type AnimationSource = typeof animationSources[number];
const apiSourceRef = "api/v1/animations/simulate";

function readQueryValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

const chapterId = computed(() => readQueryValue(route.query.chapterId));
const sessionId = computed(() => readQueryValue(route.query.sessionId));
const source = computed<AnimationSource | null>(() => {
  const value = readQueryValue(route.query.from);
  return animationSources.includes(value as AnimationSource) ? value as AnimationSource : null;
});
const hasValidChapterId = computed(() => chapterId.value.length > 0 && chapterId.value.length <= 64);
const hasValidClassroomSessionId = computed(() => sessionId.value.length > 0 && sessionId.value.length <= 160);
const hasLearningContext = computed(() => hasValidChapterId.value
  && source.value !== null
  && (source.value !== "classroom" || hasValidClassroomSessionId.value));
const returnTarget = computed(() => {
  if (source.value === "classroom") {
    return {
      path: "/user/classroom",
      query: {
        ...(hasValidChapterId.value ? { chapterId: chapterId.value } : {}),
        ...(hasValidClassroomSessionId.value ? { sessionId: sessionId.value } : {}),
      },
    };
  }
  if (!hasLearningContext.value || !source.value) {
    return hasValidChapterId.value ? `/user/chapters/${encodeURIComponent(chapterId.value)}` : "/user/chapters";
  }
  if (source.value === "home") return "/user/home";
  if (source.value === "coach") return { path: "/user/coach", query: { chapterId: chapterId.value } };
  return `/user/chapters/${encodeURIComponent(chapterId.value)}`;
});
const returnLabel = computed(() => source.value === "classroom" ? "返回课堂" : "返回学习位置");
const learningContextError = computed(() => source.value === "classroom" && !hasValidClassroomSessionId.value
  ? { title: "课堂来源缺少有效会话", message: "请返回课堂并从当前课堂会话重新打开算法舞台。" }
  : { title: "学习上下文不可用", message: "算法舞台不能单独打开。请从章节、课程问答、课堂或学习台的当前学习位置进入。" });
const structure = ref<DsvpStructure>("stack");
const operation = ref("push");
const initial = ref("1, 2");
const value = ref("3");
const definition = ref<AnimationDefinition | null>(null);
const animationRecordId = ref<string | null>(null);
const evidencePersisted = ref(false);
const loading = ref(false);
const error = ref<UserErrorPresentation | null>(null);
const player = ref<AnimationPlayback | null>(null);
const observed = ref("");
const observationStatus = ref("");
const savingObservation = ref(false);
const reducedMotion = typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;

const operations: Record<DsvpStructure, string[]> = {
  stack: ["push", "pop", "peek"], queue: ["enqueue", "dequeue", "peek"], sequential_list: ["insert", "delete", "merge"], linked_list: ["append", "insert", "delete", "find"], tree: ["traverse", "highlight"], graph: ["bfs", "dfs", "dijkstra", "highlight"], heap: ["insert", "extract", "peek"], hash: ["put", "get", "delete"], array: ["set", "insert", "delete", "swap", "get"],
};
const currentStep = computed<AnimationStep | null>(() => player.value?.state.currentStep || null);
const renderedValues = computed(() => replayValues(definition.value?.initial || [], definition.value?.steps || [], player.value?.state.index || 0));
const stageTitle = computed(() => definition.value?.title || "等待真实 DSVP 轨迹");

function normalizeOperation() { operation.value = operations[structure.value][0]; }
function parseInitial(): unknown[] { return initial.value.split(",").map((item) => item.trim()).filter(Boolean).map((item) => /^-?\d+(\.\d+)?$/.test(item) ? Number(item) : item); }
function jsonValue(text: string): unknown { const normalized = text.trim(); if (!normalized) return ""; if (/^-?\d+(\.\d+)?$/.test(normalized)) return Number(normalized); return normalized; }
function replayValues(base: unknown[], steps: AnimationStep[], count: number): unknown[] {
  const values = [...base];
  for (const step of steps.slice(0, count)) {
    const position = step.index == null ? values.length : Math.max(0, Math.min(step.index, values.length));
    if (["push", "enqueue", "append", "insert"].includes(step.op) && step.value !== undefined) values.splice(position, 0, step.value);
    else if (["pop", "dequeue", "delete", "extract"].includes(step.op)) values.splice(step.index ?? (step.op === "pop" ? values.length - 1 : 0), 1);
    else if (step.op === "set" && step.index != null) values[step.index] = step.value;
    else if (step.op === "swap" && step.i != null && step.j != null) [values[step.i], values[step.j]] = [values[step.j], values[step.i]];
  }
  return values;
}
function setDefinition(next: AnimationDefinition) {
  player.value?.dispose();
  definition.value = next;
  player.value = createAnimationPlayback(next.steps, 900, reducedMotion);
  observed.value = "";
  observationStatus.value = "";
}

function requestContext(): DsvpEvidenceContext {
  if (source.value === "classroom" && hasValidClassroomSessionId.value) {
    return {
      chapter_id: chapterId.value,
      classroom_session_id: sessionId.value,
      source_type: "CLASSROOM",
      source_ref: sessionId.value,
    };
  }
  return {
    chapter_id: chapterId.value,
    source_type: "API",
    source_ref: apiSourceRef,
  };
}

async function simulate() {
  if (!hasLearningContext.value || !source.value) return;
  loading.value = true;
  error.value = null;
  try {
    const params: Record<string, unknown> = { capacity: 12 };
    if (["push", "enqueue", "insert", "append", "set"].includes(operation.value)) params.value = jsonValue(value.value);
    const context = requestContext();
    const request: DsvpRequest = {
      version: "1.0",
      structure: structure.value,
      operation: operation.value,
      params,
      initial_state: { data: parseInitial(), metadata: { capacity: 12 } },
      context,
      chapterId: chapterId.value,
      source_ref: context.source_ref,
      ...(source.value === "classroom" && hasValidClassroomSessionId.value ? { classroomSessionId: sessionId.value } : {}),
    };
    const response = await userApi.simulateAnimation(request);
    animationRecordId.value = response.recordId || response.animationRecordId || null;
    evidencePersisted.value = response.evidencePersisted;
    setDefinition(response.animationData);
  } catch (cause) { error.value = presentUserError(cause); } finally { loading.value = false; }
}

async function saveObservation() {
  if (!observed.value.trim()) return;
  if (!animationRecordId.value) { observationStatus.value = "本次轨迹没有可保存的动画记录编号。"; return; }
  savingObservation.value = true;
  try { await userApi.saveObservation(animationRecordId.value, { observation: observed.value.trim() }); observationStatus.value = "观察已保存"; } catch (cause) { observationStatus.value = presentUserError(cause).message; } finally { savingObservation.value = false; }
}

watch(structure, normalizeOperation);
onBeforeUnmount(() => player.value?.dispose());
</script>

<template>
  <UserFrame>
    <section class="user-page" aria-labelledby="animation-title">
      <header class="user-page__heading"><div><p class="user-page__eyebrow">DSVP 真实轨迹</p><h1 id="animation-title">算法舞台</h1><p class="user-page__intro">只渲染服务端模拟接口返回的步骤和状态，不用循环动画伪造算法过程。</p></div><RouterLink class="user-action" :data-testid="hasLearningContext ? 'animation-return' : 'animation-context-return'" :to="returnTarget">{{ returnLabel }}</RouterLink></header>
      <UserState v-if="!hasLearningContext" mode="error" :title="learningContextError.title" :message="learningContextError.message" />
      <template v-else>
        <form class="user-form user-panel" @submit.prevent="simulate"><div class="user-form__split"><label>数据结构<select v-model="structure" data-testid="animation-structure"><option v-for="name in Object.keys(operations)" :key="name" :value="name">{{ name }}</option></select></label><label>操作<select v-model="operation" data-testid="animation-operation"><option v-for="name in operations[structure]" :key="name" :value="name">{{ name }}</option></select></label></div><div class="user-form__split"><label>初始数据，逗号分隔<input v-model="initial" data-testid="animation-initial" /></label><label>操作值<input v-model="value" data-testid="animation-value" :disabled="!['push','enqueue','insert','append','set'].includes(operation)" /></label></div><button class="user-action user-action--primary" data-testid="animation-run" type="submit" :disabled="loading">{{ loading ? '正在生成轨迹…' : '运行模拟' }}</button></form>
        <UserState v-if="loading" mode="loading" title="正在生成真实算法轨迹" message="服务端正在验证请求、学习上下文和 DSVP 状态。" />
        <UserState v-else-if="error" :mode="error.kind === 'permission' ? 'permission' : 'error'" :title="error.title" :message="error.message" :retry-label="error.retryable ? '重新运行' : undefined" @retry="simulate" />
        <section v-else-if="definition && player" class="user-stage" aria-label="算法舞台"><header class="user-stage__top"><div><p>{{ definition.type }} · {{ definition.description }}</p><h2>{{ stageTitle }}</h2></div><span>第 {{ player.state.index }} / {{ definition.steps.length }} 步</span></header><div class="user-stage__viewport"><div class="user-stage__data"><span v-for="(item, index) in renderedValues" :key="`${index}-${String(item)}`" class="user-stage__node" :data-current="currentStep?.index === index">{{ typeof item === 'object' ? JSON.stringify(item) : item }}</span><p v-if="!renderedValues.length" class="user-stage__note">当前结构为空</p></div></div><p class="user-stage__note">{{ currentStep ? `${currentStep.label}：${currentStep.note}` : '从第 1 步开始查看状态变化。' }}</p><p v-if="reducedMotion" class="user-stage__note">已遵循系统的减少动态偏好，仅支持手动单步查看。</p><div class="user-stage__toolbar"><button type="button" :disabled="player.state.index === 0" @click="player.previous">上一步</button><button type="button" :disabled="player.state.index >= definition.steps.length" @click="player.next">下一步</button><button v-if="!reducedMotion && !player.state.playing" type="button" :disabled="player.state.index >= definition.steps.length" @click="player.play">播放</button><button v-else-if="!reducedMotion" type="button" @click="player.pause">暂停</button><button type="button" @click="player.reset">重置</button><label>速度<select :value="player.state.speed" @change="player.setSpeed(Number(($event.target as HTMLSelectElement).value))"><option :value="0.5">0.5 倍</option><option :value="1">1 倍</option><option :value="1.5">1.5 倍</option><option :value="2">2 倍</option></select></label></div></section>
        <p v-if="definition" class="inline-notice" :data-tone="evidencePersisted ? 'success' : 'warning'">{{ evidencePersisted ? '证据已记录' : '轨迹已生成，但服务端未确认保存证据' }}</p>
        <section v-if="definition" class="user-form user-panel"><label>本次观察<textarea v-model="observed" data-testid="animation-observation" maxlength="2000" placeholder="记录你观察到的状态变化"></textarea></label><div class="user-page__actions"><button class="user-action" data-testid="animation-save-observation" type="button" :disabled="!observed.trim() || savingObservation || !animationRecordId" @click="saveObservation">{{ savingObservation ? '正在保存…' : '保存观察' }}</button><span v-if="observationStatus" class="user-list__meta">{{ observationStatus }}</span></div></section>
        <UserState v-else mode="empty" title="选择结构和操作开始模拟" message="舞台只会在服务端返回验证通过的 DSVP 轨迹后显示。" />
      </template>
    </section>
    <template #rail><div class="user-rail-list"><strong>真实接口</strong><p>POST /animations/simulate</p><strong>学习上下文</strong><p>{{ hasLearningContext ? `${chapterId} · ${source}` : '未验证，已禁止模拟' }}</p><strong>控制</strong><p>播放、暂停、单步、重置和速度均只作用于已返回轨迹。</p></div></template>
  </UserFrame>
</template>
