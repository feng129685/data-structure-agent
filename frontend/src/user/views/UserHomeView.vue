<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { userApi } from "../runtime";
import UserFrame from "../components/UserFrame.vue";
import UserState from "../components/UserState.vue";
import { presentUserError, type UserErrorPresentation } from "../errors";
import type { Chapter, LearningProgress } from "../../shared/types/contracts";

const chapters = ref<Chapter[]>([]);
const progress = ref<LearningProgress | null>(null);
const loading = ref(true);
const error = ref<UserErrorPresentation | null>(null);
const updatedAt = ref<Date | null>(null);

const currentProgress = computed(() => progress.value?.chapters
  .filter((item) => item.lastActivityAt)
  .sort((left, right) => String(right.lastActivityAt).localeCompare(String(left.lastActivityAt)))[0] ?? null);
const currentChapter = computed(() => currentProgress.value
  ? chapters.value.find((chapter) => chapter.id === currentProgress.value?.chapterId) ?? null
  : null);
const hasCurrentChapter = computed(() => Boolean(currentChapter.value));
const actionTarget = computed(() => currentChapter.value ? { chapterId: currentChapter.value.id } : undefined);

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const [chapterData, progressData] = await Promise.all([userApi.listChapters(), userApi.getLearningProgress()]);
    chapters.value = chapterData;
    progress.value = progressData;
    updatedAt.value = new Date();
  } catch (cause) {
    error.value = presentUserError(cause);
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <UserFrame>
    <section class="user-page" aria-labelledby="user-home-title">
      <header class="user-page__heading">
        <div><p class="user-page__eyebrow">学习工作台</p><h1 id="user-home-title">继续学习</h1><p class="user-page__intro">从已保存的学习记录恢复到章节、资料与下一步操作。</p></div>
        <button class="user-action" type="button" :disabled="loading" @click="load">刷新</button>
      </header>

      <UserState v-if="loading && !progress" mode="loading" title="正在恢复学习位置" message="正在读取章节和学习记录。" />
      <UserState v-else-if="error && !progress" :mode="error.kind === 'permission' ? 'permission' : 'error'" :title="error.title" :message="error.message" retry-label="重新加载" @retry="load" />

      <template v-else>
        <section v-if="hasCurrentChapter" class="user-panel" aria-labelledby="continue-title">
          <p class="user-page__eyebrow">当前学习位置</p>
          <h2 id="continue-title">第 {{ currentChapter?.chapterNumber }} 章 {{ currentChapter?.title }}</h2>
          <p>{{ currentChapter?.summary }}</p>
          <p v-if="currentProgress?.lastActivityAt" class="user-list__meta">最近活动：{{ new Date(currentProgress.lastActivityAt).toLocaleString() }}</p>
          <div class="user-page__actions">
            <RouterLink class="user-action user-action--primary" :to="`/user/chapters/${currentChapter?.id}`">继续章节</RouterLink>
            <RouterLink class="user-action" :to="{ path: '/user/coach', query: actionTarget }">进入问答</RouterLink>
            <RouterLink class="user-action" :to="{ path: '/user/classroom', query: actionTarget }">进入课堂</RouterLink>
            <RouterLink class="user-action" :to="{ path: '/user/animation', query: { ...actionTarget, from: 'home' } }">打开动画</RouterLink>
            <RouterLink class="user-action" :to="{ path: '/user/code', query: actionTarget }">打开代码实验</RouterLink>
          </div>
        </section>
        <UserState v-else mode="empty" title="尚未形成可恢复的学习位置" message="学习活动会在查看资料、完成复盘等操作后由服务端记录。可以从章节目录开始。">
          <template #default><RouterLink class="user-action user-action--primary" to="/user/chapters">查看章节目录</RouterLink></template>
        </UserState>

        <section class="user-page__section" aria-labelledby="progress-title">
          <header><h2 id="progress-title">学习记录</h2><p>只展示服务端实际聚合的活动计数</p></header>
          <div v-if="progress?.chapters.length" class="user-list">
            <RouterLink v-for="item in progress.chapters" :key="item.chapterId" class="user-list__row" :to="`/user/chapters/${item.chapterId}`">
              <div><h3>第 {{ item.chapterNumber }} 章 {{ item.title }}</h3><p>问答 {{ item.chatCount }} · 课堂 {{ item.classroomCount }} · 动画 {{ item.animationCount }} · 代码 {{ item.codeRunCount }} · 其他活动 {{ item.eventCount }}</p></div>
              <span class="user-list__meta">{{ item.totalActivities }} 项活动</span>
            </RouterLink>
          </div>
          <UserState v-else mode="empty" title="暂无学习记录" message="当前接口没有返回已记录的学习活动。" />
        </section>

        <section class="user-panel" aria-labelledby="recent-title"><h2 id="recent-title">最近访问资料</h2><p>当前冻结契约没有提供“最近访问资料”查询接口，因此不会以本地或虚构数据替代。</p></section>
        <p v-if="error && progress" class="inline-notice inline-notice--warning">部分数据刷新失败：{{ error.message }}。当前仍展示上次成功读取的数据。</p>
        <p v-if="updatedAt" class="user-list__meta">本页数据读取时间：{{ updatedAt.toLocaleString() }}</p>
      </template>
    </section>
    <template #rail><div class="user-rail-list"><strong>数据来源</strong><p>GET /chapters</p><p>GET /learning/progress</p><strong>下一步</strong><p>从当前章节进入资料、问答、课堂、动画或代码实验。</p></div></template>
  </UserFrame>
</template>
