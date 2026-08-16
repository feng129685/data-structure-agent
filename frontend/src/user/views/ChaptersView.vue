<script setup lang="ts">
import { onMounted, ref } from "vue";
import { userApi } from "../runtime";
import UserFrame from "../components/UserFrame.vue";
import UserState from "../components/UserState.vue";
import { presentUserError, type UserErrorPresentation } from "../errors";
import type { Chapter } from "../../shared/types/course";

const chapters = ref<Chapter[]>([]);
const loading = ref(true);
const error = ref<UserErrorPresentation | null>(null);

async function load() {
  loading.value = true;
  error.value = null;
  try { chapters.value = await userApi.listChapters(); } catch (cause) { error.value = presentUserError(cause); } finally { loading.value = false; }
}
onMounted(load);
</script>

<template>
  <UserFrame>
    <section class="user-page" aria-labelledby="chapters-title">
      <header class="user-page__heading"><div><p class="user-page__eyebrow">主线学习</p><h1 id="chapters-title">课程章节</h1><p class="user-page__intro">章节顺序、内容与资源均由已发布课程数据决定。</p></div><button class="user-action" type="button" :disabled="loading" @click="load">刷新目录</button></header>
      <UserState v-if="loading" mode="loading" title="正在读取章节目录" message="正在确认已发布章节。" />
      <UserState v-else-if="error" :mode="error.kind === 'permission' ? 'permission' : 'error'" :title="error.title" :message="error.message" retry-label="重试" @retry="load" />
      <UserState v-else-if="!chapters.length" mode="empty" title="暂无已发布章节" message="服务端当前没有返回可访问的课程章节。" />
      <div v-else class="user-list" aria-label="章节目录">
        <RouterLink v-for="chapter in chapters" :key="chapter.id" class="user-list__row" :to="`/user/chapters/${chapter.id}`"><div><h3>第 {{ chapter.chapterNumber }} 章 {{ chapter.title }}</h3><p>{{ chapter.summary }}</p></div><span class="user-list__meta">打开章节</span></RouterLink>
      </div>
    </section>
    <template #rail><div class="user-rail-list"><strong>主线学习</strong><p>从章节进入审核资源、课程问答、课堂和代码实验。</p><strong>数据来源</strong><p>GET /chapters</p></div></template>
  </UserFrame>
</template>
