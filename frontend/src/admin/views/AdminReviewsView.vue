<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import AdminPageFrame from "../components/AdminPageFrame.vue";
import { adminApi, adminErrorMessage, formatDate } from "../api";
import type { ReviewDetail, ReviewHistoryEvent, ReviewItem, ReviewStatus } from "../../shared/types";
import LoadingState from "../../shared/components/LoadingState.vue";
import ErrorState from "../../shared/components/ErrorState.vue";
import EmptyState from "../../shared/components/EmptyState.vue";
import RetryButton from "../../shared/components/RetryButton.vue";
import StatusBadge from "../../shared/components/StatusBadge.vue";
import InlineNotice from "../../shared/components/InlineNotice.vue";

const page = ref(0); const size = 20; const total = ref(0); const items = ref<ReviewItem[]>([]); const loading = ref(true); const error = ref(""); const notice = ref("");
const filters = reactive({ search: "", status: "", type: "" }); const selected = ref<ReviewDetail | null>(null); const history = ref<ReviewHistoryEvent[]>([]); const detailLoading = ref(false); const actionBusy = ref(false); const nextStatus = ref<ReviewStatus>("DRAFT"); const note = ref("");
const statuses: ReviewStatus[] = ["LEGACY_UNVERIFIED", "DRAFT", "PUBLISHED", "VERIFIED", "EXCLUDED"];
let listRequest = 0;
let detailRequest = 0;

function noticeTone(value: string): "success" | "danger" { return value.includes("未完成") || value.includes("失败") ? "danger" : "success"; }

function reviewKey(item: Pick<ReviewItem, "type" | "id">) { return `${item.type}:${item.id}`; }

function closeDetail() {
  detailRequest += 1;
  selected.value = null;
  history.value = [];
  detailLoading.value = false;
  note.value = "";
}

async function load({ clearCurrentSelection = false } = {}): Promise<boolean> {
  const request = ++listRequest;
  if (clearCurrentSelection) closeDetail();
  loading.value = true;
  error.value = "";
  try {
    const result = await adminApi.reviews({ page: page.value, size, search: filters.search, status: filters.status, type: filters.type });
    if (request !== listRequest) return false;
    items.value = result.items;
    total.value = result.total;
    return true;
  } catch (failure) {
    if (request === listRequest) error.value = adminErrorMessage(failure, "读取审核队列");
    return false;
  } finally {
    if (request === listRequest) loading.value = false;
  }
}

function refresh() { notice.value = ""; void load({ clearCurrentSelection: true }); }
function applyFilters() { page.value = 0; notice.value = ""; void load({ clearCurrentSelection: true }); }

async function openDetail(item: ReviewItem) {
  if (detailLoading.value || actionBusy.value) return;
  const request = ++detailRequest;
  detailLoading.value = true;
  selected.value = { item, sourceChain: [] };
  history.value = [];
  notice.value = "";
  try {
    const detail = await adminApi.review(item.type, item.id);
    if (request !== detailRequest) return;
    selected.value = detail;
    nextStatus.value = detail.item.status;
    note.value = "";
    try {
      const events = await adminApi.reviewHistory(item.type, item.id);
      if (request === detailRequest) history.value = events;
    } catch (failure) {
      if (request === detailRequest) notice.value = adminErrorMessage(failure, "读取审核历史");
    }
  } catch (failure) {
    if (request === detailRequest) {
      selected.value = null;
      notice.value = adminErrorMessage(failure, "读取审核详情");
    }
  } finally {
    if (request === detailRequest) detailLoading.value = false;
  }
}

async function updateStatus() {
  if (!selected.value || actionBusy.value || detailLoading.value) return;
  const item = selected.value.item;
  if (item.status === nextStatus.value) {
    notice.value = "目标状态与当前状态相同，未提交变更。";
    return;
  }
  if (!window.confirm(`确认将 ${item.title} 变更为 ${nextStatus.value}？来源链完整性和发布规则仍由服务端最终校验。`)) return;
  const key = reviewKey(item);
  actionBusy.value = true;
  notice.value = "";
  try {
    const updated = await adminApi.updateReviewStatus(item.type, item.id, { status: nextStatus.value, note: note.value || null });
    items.value = items.value.map((entry) => entry.id === updated.id && entry.type === updated.type ? updated : entry);
    if (selected.value && reviewKey(selected.value.item) === key) selected.value.item = updated;
    const refreshed = await load();
    try {
      const events = await adminApi.reviewHistory(updated.type, updated.id);
      if (selected.value && reviewKey(selected.value.item) === key) history.value = events;
      notice.value = refreshed ? "审核状态已更新。" : "审核状态已更新，但审核队列刷新失败，请手动刷新确认。";
    } catch (failure) {
      notice.value = refreshed
        ? `审核状态已更新，但${adminErrorMessage(failure, "读取审核历史")}`
        : `审核状态已更新，但审核队列刷新失败；${adminErrorMessage(failure, "读取审核历史")}`;
    }
    if (!refreshed) error.value = "";
  } catch (failure) {
    notice.value = adminErrorMessage(failure, "更新审核状态");
  } finally {
    actionBusy.value = false;
  }
}
function tone(status: ReviewStatus): "success" | "danger" | "warning" | "neutral" { return status === "VERIFIED" || status === "PUBLISHED" ? "success" : status === "EXCLUDED" ? "danger" : status === "DRAFT" ? "warning" : "neutral"; }
onMounted(load);
</script>

<template>
  <AdminPageFrame
    title="证据与资源审核"
    description="审核队列只展示后端返回的真实项目、来源链和历史。VERIFIED 的完整性规则由服务端决定。"
  >
    <template #actions>
      <span class="admin-header-readout admin-code">共 {{ total }} 项</span>
      <button class="button button--small admin-command" type="button" :disabled="loading || actionBusy" @click="refresh"><span class="admin-command__dot" aria-hidden="true"></span>刷新</button>
    </template>

    <form class="admin-command-row admin-toolbar" @submit.prevent="applyFilters">
      <div class="admin-command-row__label"><span class="admin-kicker">审核筛选</span><strong>审核队列</strong></div>
      <label class="admin-field admin-field--wide"><span>搜索</span><input v-model="filters.search" maxlength="160" placeholder="标题或标识" /></label>
      <label class="admin-field"><span>类型</span><select v-model="filters.type"><option value="">全部类型</option><option value="RESOURCE">RESOURCE</option><option value="KNOWLEDGE_CHUNK">KNOWLEDGE_CHUNK</option><option value="PRESENTATION_MANIFEST">PRESENTATION_MANIFEST</option><option value="PRESENTATION_PAGE">PRESENTATION_PAGE</option><option value="DSVP_REQUEST_SNAPSHOT">DSVP_REQUEST_SNAPSHOT</option></select></label>
      <label class="admin-field"><span>状态</span><select v-model="filters.status"><option value="">全部状态</option><option v-for="status in statuses" :key="status" :value="status">{{ status }}</option></select></label>
      <button class="button button--primary admin-command-row__submit" type="submit" :disabled="loading || actionBusy">筛选<span aria-hidden="true">↗</span></button>
    </form>

    <InlineNotice v-if="notice" :message="notice" :tone="noticeTone(notice)" />
    <LoadingState v-if="loading" label="正在读取审核队列…" />
    <ErrorState v-else-if="error" title="审核队列读取失败" :message="error"><RetryButton @retry="load" /></ErrorState>
    <EmptyState v-else-if="!items.length" title="审核队列为空" message="当前筛选条件没有待处理项目。" />

    <template v-else>
      <div class="signal-strip admin-motion-enter" aria-label="审核队列摘要">
        <div class="signal-strip__item"><span class="signal-strip__label">项目总数</span><strong>{{ total }}</strong></div>
        <div class="signal-strip__item"><span class="signal-strip__label">当前页</span><strong>{{ items.length }} 个项目</strong></div>
        <div class="signal-strip__item"><span class="signal-strip__label">当前选择</span><strong>{{ selected ? selected.item.status : "未选择" }}</strong></div>
        <div class="signal-strip__item"><span class="signal-strip__label">来源链</span><strong>服务端核验</strong></div>
      </div>

      <div :class="{ 'data-rail': selected }">
        <section class="admin-data-surface admin-table-wrap admin-motion-enter" aria-label="审核队列">
          <header class="admin-data-surface__head"><div><p class="admin-kicker">审核清单</p><h2>审核队列</h2></div><span class="admin-data-surface__hint">来源链完整性由服务端最终校验</span></header>
          <table class="admin-table admin-data-table">
            <thead><tr><th>项目</th><th>状态</th><th>来源链</th><th>更新时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="item in items" :key="`${item.type}-${item.id}`" class="admin-data-row" :data-selected="selected && selected.item.id === item.id && selected.item.type === item.type">
                <td><div class="admin-record"><span class="admin-record__index admin-code">{{ item.id }}</span><div><strong>{{ item.title }}</strong><div class="admin-muted admin-code">{{ item.type }}</div></div></div></td>
                <td><StatusBadge :label="item.status" :tone="tone(item.status)" /></td>
                <td><StatusBadge :label="item.sourceComplete ? '完整' : '不完整'" :tone="item.sourceComplete ? 'success' : 'warning'" /></td>
                <td class="admin-code admin-nowrap">{{ formatDate(item.updatedAt) }}</td>
                <td><button class="button button--small admin-action-button" type="button" data-action="open-review" :disabled="detailLoading || actionBusy" @click="openDetail(item)">查看详情<span aria-hidden="true">↗</span></button></td>
              </tr>
            </tbody>
          </table>
        </section>

        <aside v-if="selected" class="admin-inspector admin-detail admin-motion-enter" aria-label="审核详情">
          <div class="admin-detail__header admin-inspector__header"><div><p class="admin-kicker">当前项目</p><h2>{{ selected.item.title }}</h2><p class="admin-code">{{ selected.item.type }} · {{ selected.item.id }}</p></div><button class="button button--small" type="button" @click="closeDetail">关闭</button></div>
          <div v-if="detailLoading" data-state="review-detail-loading"><LoadingState label="正在读取来源链…" /></div>
          <template v-else>
            <section class="admin-inspector__summary"><div><span class="admin-kicker">当前状态</span><StatusBadge :label="selected.item.status" :tone="tone(selected.item.status)" /></div><div><span class="admin-kicker">章节</span><strong>{{ selected.item.chapterId || "未关联" }}</strong></div><div><span class="admin-kicker">来源链</span><strong>{{ selected.item.sourceComplete ? "完整" : "不完整" }}</strong></div></section>
            <section class="admin-inspector__section"><div class="admin-section-head admin-section-head--compact"><h3>来源链</h3><span class="admin-code">{{ selected.sourceChain.length }} 个节点</span></div><ul class="admin-list admin-timeline"><li v-for="source in selected.sourceChain" :key="`${source.type}-${source.id}`"><span class="admin-timeline__node" aria-hidden="true"></span><div><strong>{{ source.title }}</strong><div class="admin-muted admin-code">{{ source.type }} · {{ source.id }} · {{ source.status }}</div></div></li><li v-if="!selected.sourceChain.length" class="admin-muted">服务未返回来源链。</li></ul></section>
            <section class="admin-inspector__section"><div class="admin-section-head admin-section-head--compact"><h3>状态变更</h3><span class="admin-code">受保护写入</span></div><div class="admin-form__grid"><label class="admin-field"><span>目标状态</span><select v-model="nextStatus" data-field="next-status" :disabled="actionBusy"><option v-for="status in statuses" :key="status" :value="status">{{ status }}</option></select></label><label class="admin-field"><span>审核备注</span><input v-model="note" maxlength="500" placeholder="可选，说明审核依据" :disabled="actionBusy" /></label></div><button class="button button--primary admin-inspector__submit" type="button" data-action="update-review" :disabled="actionBusy" @click="updateStatus">{{ actionBusy ? "提交中…" : "提交状态变更" }}<span aria-hidden="true">↗</span></button></section>
            <section class="admin-inspector__section"><div class="admin-section-head admin-section-head--compact"><h3>审核历史</h3><span class="admin-code">历史记录</span></div><ul class="admin-list admin-timeline"><li v-for="event in history" :key="event.id"><span class="admin-timeline__node" aria-hidden="true"></span><div><strong>{{ event.previousStatus }} → {{ event.nextStatus }}</strong><div class="admin-muted">{{ formatDate(event.createdAt) }} · {{ event.note || "无备注" }}</div><div class="admin-muted admin-code">requestId {{ event.requestId }}</div></div></li><li v-if="!history.length" class="admin-muted">暂无审核历史。</li></ul></section>
          </template>
        </aside>
      </div>

      <div class="admin-pagination admin-pagination--rail"><span class="admin-code">第 {{ page + 1 }} 页 · 共 {{ total }} 项</span><div class="admin-pagination__actions"><button class="button button--small" type="button" :disabled="page === 0 || loading || actionBusy" @click="page--; notice = ''; load({ clearCurrentSelection: true })">上一页</button><button class="button button--small" type="button" :disabled="(page + 1) * size >= total || loading || actionBusy" @click="page++; notice = ''; load({ clearCurrentSelection: true })">下一页</button></div></div>
    </template>
  </AdminPageFrame>
</template>
