<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import type { Chapter, KnowledgeSearchResult } from "../../shared/types/contracts";
import UserFrame from "../components/UserFrame.vue";
import UserState from "../components/UserState.vue";
import { presentUserError, type UserErrorPresentation } from "../errors";
import { userApi } from "../runtime";

const route = useRoute();
const router = useRouter();
const query = ref(String(route.query.q || ""));
const chapterId = ref(String(route.query.chapterId || ""));
const limit = ref(4);
const chapters = ref<Chapter[]>([]);
const results = ref<KnowledgeSearchResult[]>([]);
const searchedQuery = ref("");
const loading = ref(false);
const loadingChapters = ref(true);
const error = ref<UserErrorPresentation | null>(null);
const validation = ref("");

async function loadChapters() {
  loadingChapters.value = true;
  try { chapters.value = await userApi.listChapters(); } catch { chapters.value = []; } finally { loadingChapters.value = false; }
}

async function search() {
  validation.value = "";
  error.value = null;
  const normalized = query.value.trim().replace(/\s+/g, " ");
  if (!normalized) { validation.value = "请输入要检索的知识点。"; return; }
  if (normalized.length > 500) { validation.value = "检索内容不能超过 500 个字符。"; return; }
  loading.value = true;
  try {
    const response = await userApi.searchKnowledge({ query: normalized, chapterId: chapterId.value || undefined, limit: limit.value });
    results.value = response.results;
    searchedQuery.value = response.query;
    await router.replace({ query: { ...(normalized ? { q: normalized } : {}), ...(chapterId.value ? { chapterId: chapterId.value } : {}) } });
  } catch (cause) {
    results.value = [];
    error.value = presentUserError(cause);
  } finally {
    loading.value = false;
  }
}

onMounted(async () => { await loadChapters(); if (query.value.trim()) await search(); });
</script>

<template>
  <UserFrame>
    <section class="user-page" aria-labelledby="knowledge-title">
      <header class="user-page__heading"><div><p class="user-page__eyebrow">已审核知识</p><h1 id="knowledge-title">知识检索</h1><p class="user-page__intro">只返回已发布、已审核且当前账号可见的课程证据。</p></div></header>
      <form class="user-form user-panel" role="search" @submit.prevent="search">
        <label>检索内容<input v-model="query" maxlength="501" placeholder="例如：顺序栈的入栈条件" autocomplete="off" /><span v-if="validation" class="user-field-error" role="alert">{{ validation }}</span></label>
        <div class="user-form__split">
          <label>章节范围<select v-model="chapterId" :disabled="loadingChapters"><option value="">全部可见章节</option><option v-for="chapter in chapters" :key="chapter.id" :value="chapter.id">第 {{ chapter.chapterNumber }} 章 {{ chapter.title }}</option></select></label>
          <label>返回数量<select v-model.number="limit"><option v-for="count in 6" :key="count" :value="count">{{ count }} 条</option></select></label>
        </div>
        <div class="user-page__actions"><button class="user-action user-action--primary" type="submit" :disabled="loading">{{ loading ? "正在检索…" : "检索知识" }}</button><span class="user-list__meta">查询最长 500 字，结果最多 6 条</span></div>
      </form>

      <UserState v-if="loading" mode="loading" title="正在检索课程知识" message="正在匹配已审核来源。" />
      <UserState v-else-if="error" :mode="error.kind === 'permission' ? 'permission' : 'error'" :title="error.title" :message="error.message" :retry-label="error.retryable ? '重新检索' : undefined" @retry="search" />
      <UserState v-else-if="searchedQuery && !results.length" mode="empty" title="没有找到可见结果" :message="`“${searchedQuery}”没有匹配到已审核且当前账号可见的知识。`" />
      <section v-else-if="results.length" class="user-page__section" aria-live="polite"><header><h2>检索结果</h2><p>{{ results.length }} 条已审核来源</p></header><div class="user-list">
        <article v-for="result in results" :key="result.id" class="user-list__row user-list__row--stacked"><div><h3>{{ result.title }}</h3><p>{{ result.excerpt }}</p><p class="user-list__meta user-list__meta--left">{{ result.sourceLabel }} · {{ result.locationLabel }} · {{ result.reviewStatus }} · 相关度 {{ result.score.toFixed(3) }}</p></div><RouterLink v-if="result.chapterId" class="user-action" :to="`/user/chapters/${result.chapterId}`">打开章节</RouterLink></article>
      </div></section>
      <UserState v-else mode="empty" title="输入知识点开始检索" message="可以限定到当前章节；未审核、未发布或无访问权限的材料不会出现。" />
    </section>
    <template #rail><div class="user-rail-list"><strong>数据来源</strong><p>GET /knowledge/search</p><strong>可见性</strong><p>服务端按发布状态、审核链和账号范围过滤结果。</p></div></template>
  </UserFrame>
</template>
