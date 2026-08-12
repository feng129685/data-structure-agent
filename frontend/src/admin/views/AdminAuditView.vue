<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import AdminPageFrame from "../components/AdminPageFrame.vue";
import { adminApi, adminErrorMessage, formatDate } from "../api";
import type { AdminAuditEvent } from "../../shared/types";
import LoadingState from "../../shared/components/LoadingState.vue";
import ErrorState from "../../shared/components/ErrorState.vue";
import EmptyState from "../../shared/components/EmptyState.vue";
import RetryButton from "../../shared/components/RetryButton.vue";

const page = ref(0); const size = 50; const total = ref(0); const items = ref<AdminAuditEvent[]>([]); const loading = ref(true); const error = ref(""); const selected = ref<AdminAuditEvent | null>(null);
const filters = reactive({ actorUserId: "", action: "", targetType: "", targetId: "", from: "", to: "" });
function toIsoDateTime(value: string): string | undefined {
  if (!value) return undefined;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toISOString();
}
async function load() { loading.value = true; error.value = ""; try { const result = await adminApi.auditEvents({ page: page.value, size, actorUserId: filters.actorUserId ? Number(filters.actorUserId) : undefined, action: filters.action, targetType: filters.targetType, targetId: filters.targetId, from: toIsoDateTime(filters.from), to: toIsoDateTime(filters.to) }); items.value = result.items; total.value = result.total; } catch (failure) { error.value = adminErrorMessage(failure, "读取审计日志"); } finally { loading.value = false; } }
function applyFilters() { page.value = 0; void load(); }
onMounted(load);
</script>

<template>
  <AdminPageFrame title="审计日志" description="审计记录为只读安全摘要，展示操作者、结果、前后状态和 requestId，不展示完整请求正文或凭据。">
    <template #actions><button class="button button--small" type="button" :disabled="loading" @click="load">刷新</button></template>
    <form class="admin-toolbar" @submit.prevent="applyFilters"><label class="admin-field"><span>操作者 ID</span><input v-model="filters.actorUserId" type="number" min="1" step="1" /></label><label class="admin-field"><span>操作</span><input v-model="filters.action" maxlength="64" placeholder="如 USER_ROLES_CHANGED" /></label><label class="admin-field"><span>目标类型</span><input v-model="filters.targetType" maxlength="64" placeholder="如 USER" /></label><label class="admin-field"><span>目标 ID</span><input v-model="filters.targetId" maxlength="160" /></label><label class="admin-field"><span>开始时间</span><input v-model="filters.from" type="datetime-local" /></label><label class="admin-field"><span>结束时间</span><input v-model="filters.to" type="datetime-local" /></label><button class="button button--primary" type="submit">筛选</button></form>
    <LoadingState v-if="loading" label="正在读取审计日志…" /><ErrorState v-else-if="error" title="审计日志读取失败" :message="error"><RetryButton @retry="load" /></ErrorState><EmptyState v-else-if="!items.length" title="暂无审计记录" message="当前筛选条件没有返回审计事件。" />
    <section v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>时间</th><th>操作与目标</th><th>结果</th><th>安全摘要</th><th>请求 ID</th></tr></thead><tbody><tr v-for="event in items" :key="event.id"><td>{{ formatDate(event.createdAt) }}</td><td><strong>{{ event.action }}</strong><div class="admin-muted">{{ event.targetType }} · {{ event.targetId }} · 操作者 #{{ event.actorUserId }}</div></td><td>{{ event.result }}</td><td><button class="button button--small" type="button" @click="selected = event">查看摘要</button></td><td class="admin-code">{{ event.requestId }}</td></tr></tbody></table></section>
    <div class="admin-pagination"><span>第 {{ page + 1 }} 页 · 共 {{ total }} 条</span><div class="admin-pagination__actions"><button class="button button--small" type="button" :disabled="page === 0 || loading" @click="page--; load()">上一页</button><button class="button button--small" type="button" :disabled="(page + 1) * size >= total || loading" @click="page++; load()">下一页</button></div></div>
    <aside v-if="selected" class="admin-detail"><div class="admin-detail__header"><div><h2>审计事件 #{{ selected.id }}</h2><p>{{ formatDate(selected.createdAt) }} · {{ selected.action }}</p></div><button class="button button--small" type="button" @click="selected = null">关闭</button></div><dl><dt>Before 摘要</dt><dd>{{ selected.beforeSummary }}</dd><dt>After 摘要</dt><dd>{{ selected.afterSummary }}</dd><dt>结果</dt><dd>{{ selected.result }}</dd><dt>Request ID</dt><dd class="admin-code">{{ selected.requestId }}</dd></dl></aside>
  </AdminPageFrame>
</template>
