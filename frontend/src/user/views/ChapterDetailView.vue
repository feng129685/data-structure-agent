<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { userApi } from "../runtime";
import UserFrame from "../components/UserFrame.vue";
import UserState from "../components/UserState.vue";
import { presentUserError, type UserErrorPresentation } from "../errors";
import type { Chapter, Resource } from "../../shared/types/contracts";

const route = useRoute();
const chapterId = computed(() => String(route.params.chapterId));
const chapter = ref<Chapter | null>(null);
const resources = ref<Resource[]>([]);
const loading = ref(true);
const error = ref<UserErrorPresentation | null>(null);

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const chapters = await userApi.listChapters();
    chapter.value = chapters.find((item) => item.id === chapterId.value) ?? null;
    if (!chapter.value) { error.value = { kind: "not-found", title: "章节不可访问", message: "该章节未发布、已移除，或当前账号没有课程访问范围。", retryable: false }; return; }
    resources.value = await userApi.listResources(chapterId.value);
  } catch (cause) { error.value = presentUserError(cause); } finally { loading.value = false; }
}
onMounted(load);
</script>

<template>
  <UserFrame>
    <section class="user-page" aria-labelledby="chapter-detail-title">
      <header class="user-page__heading"><div><p class="user-page__eyebrow">当前章节</p><h1 id="chapter-detail-title">{{ chapter ? `第 ${chapter.chapterNumber} 章 ${chapter.title}` : '章节详情' }}</h1><p class="user-page__intro">{{ chapter?.summary || '读取已发布章节和受权限控制的课程资源。' }}</p></div><RouterLink class="user-action" to="/user/chapters">返回目录</RouterLink></header>
      <UserState v-if="loading" mode="loading" title="正在加载章节资料" message="正在读取章节与资源可见性。" />
      <UserState v-else-if="error" :mode="error.kind === 'permission' ? 'permission' : 'error'" :title="error.title" :message="error.message" :retry-label="error.retryable ? '重新加载' : undefined" @retry="load" />
      <template v-else-if="chapter">
        <section class="user-panel"><h2>章节目标</h2><p>当前冻结的章节接口仅提供标题和摘要，未返回可展示的章节目标。不会以静态或推测内容补充。</p><div class="user-page__actions"><RouterLink class="user-action" :to="{ path: '/user/coach', query: { chapterId } }">在本章提问</RouterLink><RouterLink class="user-action" :to="{ path: '/user/classroom', query: { chapterId } }">进入本章课堂</RouterLink><RouterLink class="user-action" :to="{ path: '/user/animation', query: { chapterId, from: 'chapter' } }">演示本章算法</RouterLink><RouterLink class="user-action" :to="{ path: '/user/code', query: { chapterId } }">在本章运行代码</RouterLink><RouterLink class="user-action" :to="{ path: '/user/knowledge', query: { chapterId } }">检索本章知识</RouterLink></div></section>
        <section class="user-page__section"><header><h2>已发布资源</h2><p>资源仅在章节发布且当前账号有访问范围时返回。</p></header><UserState v-if="!resources.length" mode="empty" title="本章暂无可访问资源" message="这可能表示章节为空，或资源仍未发布。" /><div v-else class="user-list"><RouterLink v-for="resource in resources" :key="resource.id" class="user-list__row" :to="`/user/resources/${resource.id}`"><div><h3>{{ resource.title }}</h3><p>{{ resource.description || '该资源没有提供摘要。' }}</p></div><span class="user-list__meta">{{ resource.type }} · {{ resource.licenseScope }}</span></RouterLink></div></section>
      </template>
    </section>
    <template #rail><div class="user-rail-list"><strong>数据来源</strong><p>GET /chapters</p><p>GET /chapters/{chapterId}/resources</p><strong>学习位置</strong><p>{{ chapterId }}</p></div></template>
  </UserFrame>
</template>
