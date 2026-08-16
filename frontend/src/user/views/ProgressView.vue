<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import type { LearningProgress } from "../../shared/types/contracts";
import UserFrame from "../components/UserFrame.vue";
import UserState from "../components/UserState.vue";
import { presentUserError, type UserErrorPresentation } from "../errors";
import { userApi } from "../runtime";

const progress = ref<LearningProgress | null>(null);
const loading = ref(true);
const savingChapterId = ref<string | null | undefined>(undefined);
const loadError = ref<UserErrorPresentation | null>(null);
const saveError = ref<UserErrorPresentation | null>(null);
const savedMessage = ref("");
const lastReviewChapterId = ref<string | null>(null);
let loadVersion = 0;

const saving = computed(() => savingChapterId.value !== undefined);
const initialLoading = computed(() => loading.value && !progress.value);
const refreshing = computed(() => loading.value && Boolean(progress.value));

function formatActivityTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

async function load(): Promise<void> {
  const version = ++loadVersion;
  loading.value = true;
  loadError.value = null;
  try {
    const nextProgress = await userApi.getLearningProgress();
    if (version === loadVersion) progress.value = nextProgress;
  } catch (cause) {
    if (version === loadVersion) {
      loadError.value = presentUserError(cause);
      if (loadError.value.kind === "permission") progress.value = null;
    }
  } finally {
    if (version === loadVersion) loading.value = false;
  }
}

async function completeReview(chapterId: string | null = null): Promise<void> {
  if (saving.value) return;
  const normalizedChapterId = chapterId || null;
  savingChapterId.value = normalizedChapterId;
  lastReviewChapterId.value = normalizedChapterId;
  saveError.value = null;
  savedMessage.value = "";
  try {
    await userApi.recordLearningEvent({ eventType: "REVIEW_COMPLETED", chapterId: normalizedChapterId });
    savedMessage.value = "复盘完成已记录";
    await load();
  } catch (cause) {
    saveError.value = presentUserError(cause);
  } finally {
    savingChapterId.value = undefined;
  }
}

function retrySave(): Promise<void> {
  return completeReview(lastReviewChapterId.value);
}

onMounted(load);
</script>

<template>
  <UserFrame>
    <section class="user-page" aria-labelledby="progress-title">
      <header class="user-page__heading">
        <div>
          <p class="user-page__eyebrow">学习记录</p>
          <h1 id="progress-title">复盘</h1>
          <p class="user-page__intro">显示服务端聚合的真实学习活动，不用百分比或推测统计替代。</p>
        </div>
        <button class="user-action" data-testid="progress-refresh" type="button" :disabled="loading || saving" @click="load">刷新</button>
      </header>

      <UserState
        v-if="initialLoading"
        data-testid="progress-load-state"
        mode="loading"
        title="正在读取学习记录"
        message="正在汇总已保存的学习活动。"
      />
      <UserState
        v-else-if="loadError && !progress"
        data-testid="progress-load-state"
        :mode="loadError.kind === 'permission' ? 'permission' : 'error'"
        :title="loadError.title"
        :message="loadError.message"
        :retry-label="loadError.retryable ? '重新加载' : undefined"
        @retry="load"
      >
        <RouterLink v-if="loadError.kind === 'permission'" class="user-action user-action--primary" to="/login">前往登录</RouterLink>
      </UserState>

      <template v-else-if="progress">
        <p v-if="refreshing" class="user-list__meta" data-testid="progress-refreshing" role="status">正在刷新，继续显示上次成功读取的数据。</p>
        <section class="user-panel">
          <h2>已记录活动</h2>
          <p>{{ progress.totalActivities }} 项服务端学习活动</p>
          <div class="user-page__actions">
            <button class="user-action user-action--primary" data-testid="progress-complete-all" type="button" :disabled="saving || loading" @click="completeReview()">{{ saving && savingChapterId === null ? '正在保存…' : '完成本次总复盘' }}</button>
            <span v-if="savedMessage" class="user-list__meta" role="status">{{ savedMessage }}</span>
          </div>
        </section>

        <UserState
          v-if="saveError"
          data-testid="progress-save-state"
          :mode="saveError.kind === 'permission' ? 'permission' : 'error'"
          :title="saveError.title"
          :message="saveError.message"
          :retry-label="saveError.retryable ? '重新提交复盘' : undefined"
          @retry="retrySave"
        >
          <RouterLink v-if="saveError.kind === 'permission'" class="user-action user-action--primary" to="/login">前往登录</RouterLink>
        </UserState>

        <UserState
          v-if="!progress.chapters.length"
          data-testid="progress-empty-state"
          mode="empty"
          title="暂无章节复盘数据"
          message="查看资源、问答、课堂、动画或代码后，服务端才会返回相应记录。"
        />
        <div v-else class="user-list" data-testid="progress-list">
          <article v-for="chapter in progress.chapters" :key="chapter.chapterId" class="user-list__row">
            <div>
              <h3>第 {{ chapter.chapterNumber }} 章 {{ chapter.title }}</h3>
              <p>问答 {{ chapter.chatCount }} · 课堂 {{ chapter.classroomCount }} · 动画 {{ chapter.animationCount }} · 代码 {{ chapter.codeRunCount }} · 其他 {{ chapter.eventCount }}</p>
              <p v-if="chapter.lastActivityAt" class="user-list__meta user-list__meta--left">最近活动：{{ formatActivityTime(chapter.lastActivityAt) }}</p>
            </div>
            <div class="user-page__actions">
              <RouterLink class="user-action" :to="`/user/chapters/${chapter.chapterId}`">进入章节</RouterLink>
              <button class="user-action" :data-testid="`progress-complete-${chapter.chapterId}`" type="button" :disabled="saving || loading" @click="completeReview(chapter.chapterId)">{{ saving && savingChapterId === chapter.chapterId ? '正在保存…' : '完成复盘' }}</button>
            </div>
          </article>
        </div>

        <p v-if="loadError" class="inline-notice inline-notice--warning" role="alert">
          刷新失败：{{ loadError.message }}
          <button v-if="loadError.retryable" class="user-action" type="button" :disabled="loading || saving" @click="load">重新加载</button>
        </p>
      </template>
    </section>

    <template #rail>
      <div class="user-rail-list">
        <strong>真实接口</strong>
        <p>GET /learning/progress</p>
        <p>POST /learning/events</p>
        <strong>说明</strong>
        <p>当前契约没有提供薄弱项分析或学习曲线，因此不会伪造图表。</p>
      </div>
    </template>
  </UserFrame>
</template>
