<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import AdminPageFrame from "../components/AdminPageFrame.vue";
import { adminApi, adminErrorMessage, formatDate } from "../api";
import type { AdminAuditEvent } from "../../shared/types";
import LoadingState from "../../shared/components/LoadingState.vue";
import ErrorState from "../../shared/components/ErrorState.vue";
import EmptyState from "../../shared/components/EmptyState.vue";
import RetryButton from "../../shared/components/RetryButton.vue";
import InlineNotice from "../../shared/components/InlineNotice.vue";

const page = ref(0); const size = 50; const total = ref(0); const items = ref<AdminAuditEvent[]>([]); const loading = ref(true); const error = ref(""); const selected = ref<AdminAuditEvent | null>(null);
const filters = reactive({ actorUserId: "", action: "", targetType: "", targetId: "", from: "", to: "" });
const filterError = ref("");
let listRequest = 0;

function toIsoDateTime(value: string, label: string, endOfMinute = false): string | undefined {
  if (!value) return undefined;
  const parsed = new Date(endOfMinute && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value) ? `${value}:59.999` : value);
  if (Number.isNaN(parsed.getTime())) throw new Error(`${label}格式无效`);
  return parsed.toISOString();
}

function buildQuery() {
  try {
    filterError.value = "";
    const actorUserId = String(filters.actorUserId).trim();
    if (actorUserId && (!/^\d+$/.test(actorUserId) || Number(actorUserId) < 1 || !Number.isSafeInteger(Number(actorUserId)))) {
      throw new Error("操作者 ID 必须是正整数");
    }
    const from = toIsoDateTime(filters.from, "开始时间");
    const to = toIsoDateTime(filters.to, "结束时间", true);
    if (from && to && new Date(from).getTime() > new Date(to).getTime()) throw new Error("开始时间不能晚于结束时间");
    return {
      page: page.value,
      size,
      actorUserId: actorUserId ? Number(actorUserId) : undefined,
      action: filters.action.trim(),
      targetType: filters.targetType.trim(),
      targetId: filters.targetId.trim(),
      from,
      to,
    };
  } catch (failure) {
    filterError.value = failure instanceof Error ? failure.message : "筛选条件无效";
    return null;
  }
}

async function load({ clearCurrentSelection = false } = {}): Promise<boolean> {
  const query = buildQuery();
  if (!query) return false;
  const request = ++listRequest;
  if (clearCurrentSelection) selected.value = null;
  loading.value = true;
  error.value = "";
  try {
    const result = await adminApi.auditEvents(query);
    if (request !== listRequest) return false;
    items.value = result.items;
    total.value = result.total;
    return true;
  } catch (failure) {
    if (request === listRequest) error.value = adminErrorMessage(failure, "读取审计日志");
    return false;
  } finally {
    if (request === listRequest) loading.value = false;
  }
}

function refresh() { void load({ clearCurrentSelection: true }); }
function applyFilters() { page.value = 0; void load({ clearCurrentSelection: true }); }
onMounted(load);
</script>

<template>
  <AdminPageFrame
    title="审计日志"
    description="审计记录为只读安全摘要，展示操作者、结果、前后状态和 requestId，不展示完整请求正文或凭据。"
  >
    <template #actions>
      <span class="admin-header-readout admin-code">只读 · {{ total }} 条</span>
      <button class="button button--small admin-command" type="button" :disabled="loading" @click="refresh"><span class="admin-command__dot" aria-hidden="true"></span>刷新</button>
    </template>

    <form class="admin-command-row admin-toolbar admin-command-row--audit" @submit.prevent="applyFilters">
      <div class="admin-command-row__label"><span class="admin-kicker">事件筛选</span><strong>安全检索</strong></div>
      <label class="admin-field"><span>操作者 ID</span><input v-model="filters.actorUserId" type="number" min="1" step="1" /></label>
      <label class="admin-field"><span>操作</span><input v-model="filters.action" maxlength="64" placeholder="如 USER_ROLES_CHANGED" /></label>
      <label class="admin-field"><span>目标类型</span><input v-model="filters.targetType" maxlength="64" placeholder="如 USER" /></label>
      <label class="admin-field"><span>目标 ID</span><input v-model="filters.targetId" maxlength="160" /></label>
      <label class="admin-field"><span>开始时间</span><input v-model="filters.from" type="datetime-local" /></label>
      <label class="admin-field"><span>结束时间</span><input v-model="filters.to" type="datetime-local" /></label>
      <button class="button button--primary admin-command-row__submit" type="submit">筛选<span aria-hidden="true">↗</span></button>
    </form>

    <InlineNotice v-if="filterError" :message="filterError" tone="warning" />
    <LoadingState v-if="loading" label="正在读取审计日志…" />
    <ErrorState v-else-if="error" title="审计日志读取失败" :message="error"><RetryButton @retry="load" /></ErrorState>
    <EmptyState v-else-if="!items.length" title="暂无审计记录" message="当前筛选条件没有返回审计事件。" />

    <section v-else class="admin-data-surface admin-table-wrap admin-motion-enter" aria-label="审计日志">
      <header class="admin-data-surface__head"><div><p class="admin-kicker">不可变记录</p><h2>操作时间轴</h2></div><span class="admin-data-surface__hint">仅显示摘要，不展开请求正文</span></header>
      <table class="admin-table admin-data-table admin-audit-table">
        <thead><tr><th>时间</th><th>操作与目标</th><th>结果</th><th>安全摘要</th><th>请求 ID</th></tr></thead>
        <tbody>
          <tr v-for="event in items" :key="event.id" class="admin-data-row">
            <td class="admin-code admin-nowrap">{{ formatDate(event.createdAt) }}</td>
            <td><div class="admin-record"><span class="admin-record__index admin-code">#{{ event.id }}</span><div><strong>{{ event.action }}</strong><div class="admin-muted">{{ event.targetType }} · {{ event.targetId }} · 操作者 #{{ event.actorUserId }}</div></div></div></td>
            <td><span class="admin-result-mark" aria-hidden="true">●</span>{{ event.result }}</td>
            <td><button class="button button--small admin-action-button" type="button" @click="selected = event">查看摘要<span aria-hidden="true">↗</span></button></td>
            <td class="admin-code admin-nowrap">{{ event.requestId }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <div class="admin-pagination admin-pagination--rail"><span class="admin-code">第 {{ page + 1 }} 页 · {{ total }} 条事件</span><div class="admin-pagination__actions"><button class="button button--small" type="button" :disabled="page === 0 || loading" @click="page--; load({ clearCurrentSelection: true })">上一页</button><button class="button button--small" type="button" :disabled="(page + 1) * size >= total || loading" @click="page++; load({ clearCurrentSelection: true })">下一页</button></div></div>

    <aside v-if="selected" class="admin-inspector admin-detail admin-motion-enter">
      <div class="admin-detail__header admin-inspector__header"><div><p class="admin-kicker">事件摘要</p><h2>审计事件 #{{ selected.id }}</h2><p class="admin-code">{{ formatDate(selected.createdAt) }} · {{ selected.action }}</p></div><button class="button button--small" type="button" @click="selected = null">关闭</button></div>
      <section class="admin-inspector__summary"><div><span class="admin-kicker">结果</span><strong>{{ selected.result }}</strong></div><div><span class="admin-kicker">操作者</span><strong>#{{ selected.actorUserId }}</strong></div><div><span class="admin-kicker">目标</span><strong>{{ selected.targetType }} · {{ selected.targetId }}</strong></div></section>
      <dl><dt>变更前摘要</dt><dd>{{ selected.beforeSummary }}</dd><dt>变更后摘要</dt><dd>{{ selected.afterSummary }}</dd><dt>结果</dt><dd>{{ selected.result }}</dd><dt>Request ID</dt><dd class="admin-code">{{ selected.requestId }}</dd></dl>
    </aside>
  </AdminPageFrame>
</template>
