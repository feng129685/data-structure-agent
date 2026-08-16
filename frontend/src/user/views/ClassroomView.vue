<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import type { ClassroomAction, ClassroomScript, ClassroomSession } from "../../shared/types/contracts";
import UserFrame from "../components/UserFrame.vue";
import UserState from "../components/UserState.vue";
import { presentUserError, type UserErrorPresentation } from "../errors";
import { userApi } from "../runtime";

const route = useRoute();
const router = useRouter();
const chapterId = computed(() => String(route.query.chapterId || ""));
const recoverySessionId = computed(() => String(route.query.sessionId || ""));
const scripts = ref<ClassroomScript[]>([]);
const session = ref<ClassroomSession | null>(null);
const answer = ref("");
const loading = ref(true);
const acting = ref(false);
const error = ref<UserErrorPresentation | null>(null);

const stateLabels: Record<string, string> = { OPENING: "开场", EXPLAIN: "讲解", QUESTION: "提问", WAITING: "等待回答", DISCUSS: "讨论", BLACKBOARD: "黑板演示", SUMMARY: "总结" };
const stageEntries = computed(() => Object.entries(session.value?.stage || {}).filter(([, value]) => value !== null && value !== undefined));
const allowedActions = computed<ClassroomAction[]>(() => {
  if (!session.value) return [];
  if (session.value.paused) return ["RESUME"];
  const actions: ClassroomAction[] = ["PAUSE"];
  if (session.value.state === "WAITING") actions.unshift("ANSWER");
  if (session.value.state !== "SUMMARY") actions.push("CONTINUE", "FINISH");
  return actions;
});
const actionLabels: Record<ClassroomAction, string> = { ANSWER: "提交回答", PAUSE: "暂停课堂", RESUME: "继续课堂", CONTINUE: "下一步", FINISH: "结束并总结" };

async function load() {
  loading.value = true;
  error.value = null;
  try {
    scripts.value = await userApi.listClassroomScripts(chapterId.value || undefined);
    const sessionId = recoverySessionId.value;
    if (sessionId) session.value = await userApi.getClassroomSession(sessionId);
  } catch (cause) { error.value = presentUserError(cause); } finally { loading.value = false; }
}

async function start(scriptId: string) {
  acting.value = true;
  error.value = null;
  try {
    const nextSession = await userApi.startClassroom(scriptId);
    session.value = nextSession;
    await router.replace({ query: { ...route.query, sessionId: nextSession.id } });
  } catch (cause) { error.value = presentUserError(cause); } finally { acting.value = false; }
}

async function act(action: ClassroomAction) {
  if (!session.value) return;
  if (action === "ANSWER" && !answer.value.trim()) { error.value = { kind: "validation", title: "请先填写课堂回答", message: "课堂回答不能为空。", retryable: false }; return; }
  acting.value = true;
  error.value = null;
  try {
    session.value = await userApi.actInClassroom(session.value.id, { action, ...(action === "ANSWER" ? { content: answer.value.trim() } : {}) });
    if (action === "ANSWER") answer.value = "";
  } catch (cause) { error.value = presentUserError(cause); } finally { acting.value = false; }
}

onMounted(load);
</script>

<template>
  <UserFrame>
    <section class="user-page" aria-labelledby="classroom-title">
      <header class="user-page__heading"><div><p class="user-page__eyebrow">课堂状态机</p><h1 id="classroom-title">课堂学习</h1><p class="user-page__intro">课堂阶段、动作与会话归属均由 Spring v1 服务端确认。</p></div><RouterLink class="user-action" :to="chapterId ? `/user/chapters/${chapterId}` : '/user/chapters'">返回章节</RouterLink></header>
      <p class="inline-notice inline-notice--warning">课堂脚本与会话已接入；冻结契约没有 PPT 页面图像或播放计划接口，因此这里不会显示静态课件或调用旧 Node 播放接口。</p>
      <UserState v-if="loading" mode="loading" title="正在加载课堂" message="正在读取当前章节的已发布课堂脚本。" />
      <UserState v-else-if="error && !session && (!scripts.length || recoverySessionId)" :mode="error.kind === 'permission' ? 'permission' : 'error'" :title="error.title" :message="error.message" :retry-label="error.retryable ? '重新加载' : undefined" @retry="load" />

      <template v-else>
        <section v-if="!session" class="user-page__section"><header><h2>可用课堂脚本</h2><p>{{ chapterId ? '已限定当前章节' : '全部可见章节' }}</p></header>
          <UserState v-if="!scripts.length" mode="empty" title="暂无可用课堂脚本" message="当前章节没有返回已发布且可访问的课堂脚本。" />
          <div v-else class="user-list"><div v-for="script in scripts" :key="script.id" class="user-list__row"><div><h3>{{ script.title }}</h3><p>章节 {{ script.chapterId }} · 版本 {{ script.versionLabel }}</p></div><button class="user-action user-action--primary" :data-testid="`classroom-start-${script.id}`" type="button" :disabled="acting" @click="start(script.id)">开始课堂</button></div></div>
        </section>

        <template v-else>
          <section class="user-panel classroom-stage" aria-live="polite"><div class="user-page__actions"><span class="user-status">{{ stateLabels[session.state] || session.state }}</span><span class="user-list__meta">{{ session.paused ? '已暂停' : '进行中' }} · 会话 {{ session.id }}</span></div><dl class="user-kv"><template v-for="([key, value]) in stageEntries" :key="key"><dt>{{ key }}</dt><dd>{{ typeof value === 'string' ? value : JSON.stringify(value) }}</dd></template></dl></section>
          <section v-if="session.state === 'WAITING' && !session.paused" class="user-form user-panel"><label>我的回答<textarea v-model="answer" data-testid="classroom-answer" maxlength="4000" placeholder="根据当前课堂问题作答"></textarea></label></section>
          <p v-if="session.answerEvaluation" class="inline-notice" :data-tone="session.answerEvaluation.status === 'CORRECT' ? 'success' : 'warning'">{{ session.answerEvaluation.feedback }}<span v-if="session.answerEvaluation.misconception">：{{ session.answerEvaluation.misconception }}</span></p>
          <p v-if="error" class="inline-notice" data-tone="danger" role="alert">{{ error.message }}</p>
          <div class="user-page__actions"><RouterLink class="user-action" data-testid="classroom-animation" :to="{ path: '/user/animation', query: { chapterId, from: 'classroom', sessionId: session.id } }">查看算法舞台</RouterLink><button v-for="action in allowedActions" :key="action" class="user-action" :class="{ 'user-action--primary': action === 'ANSWER' || action === 'CONTINUE' }" :data-testid="`classroom-action-${action}`" type="button" :disabled="acting" @click="act(action)">{{ actionLabels[action] }}</button></div>
          <section v-if="session.summary" class="user-panel"><h2>课堂总结</h2><p>{{ session.summary }}</p></section>
        </template>
      </template>
    </section>
    <template #rail><div class="user-rail-list"><strong>真实接口</strong><p>GET /classroom/scripts</p><p>POST /classroom/sessions</p><p>GET /classroom/sessions/{id}</p><p>POST /classroom/sessions/{id}/actions</p><strong>上下文</strong><p>{{ chapterId || '未限定章节' }}</p></div></template>
  </UserFrame>
</template>
