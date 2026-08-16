<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import AdminPageFrame from "../components/AdminPageFrame.vue";
import { adminApi, adminErrorMessage, formatDate } from "../api";
import type { BackgroundTask, BackgroundTaskStatus } from "../../shared/types";
import LoadingState from "../../shared/components/LoadingState.vue";
import ErrorState from "../../shared/components/ErrorState.vue";
import EmptyState from "../../shared/components/EmptyState.vue";
import RetryButton from "../../shared/components/RetryButton.vue";
import StatusBadge from "../../shared/components/StatusBadge.vue";
import InlineNotice from "../../shared/components/InlineNotice.vue";

const page = ref(0); const size = 20; const total = ref(0); const items = ref<BackgroundTask[]>([]); const loading = ref(true); const error = ref(""); const notice = ref(""); const actionBusy = ref<number | null>(null); const selected = ref<BackgroundTask | null>(null); const detailBusy = ref(false); const detailError = ref("");
const filters = reactive<{ status: string; taskType: string }>({ status: "", taskType: "" });
const statuses: BackgroundTaskStatus[] = ["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED"];
let listRequest = 0;
let detailRequest = 0;

function noticeTone(value: string): "success" | "danger" { return value.includes("未完成") || value.includes("失败") ? "danger" : "success"; }

function closeDetail() {
  detailRequest += 1;
  selected.value = null;
  detailBusy.value = false;
  detailError.value = "";
}

async function load({ clearCurrentSelection = false } = {}): Promise<boolean> {
  const request = ++listRequest;
  if (clearCurrentSelection) closeDetail();
  loading.value = true;
  error.value = "";
  try {
    const result = await adminApi.tasks({ page: page.value, size, status: filters.status, taskType: filters.taskType });
    if (request !== listRequest) return false;
    items.value = result.items;
    total.value = result.total;
    return true;
  } catch (failure) {
    if (request === listRequest) error.value = adminErrorMessage(failure, "读取后台任务");
    return false;
  } finally {
    if (request === listRequest) loading.value = false;
  }
}

function refresh() { notice.value = ""; void load({ clearCurrentSelection: true }); }
function applyFilters() { page.value = 0; notice.value = ""; void load({ clearCurrentSelection: true }); }
function tone(status: BackgroundTaskStatus): "success" | "danger" | "warning" | "neutral" { return status === "SUCCEEDED" ? "success" : status === "FAILED" || status === "CANCELED" ? "danger" : status === "RUNNING" ? "warning" : "neutral"; }

async function openTask(task: BackgroundTask) {
  if (detailBusy.value || actionBusy.value !== null) return;
  const request = ++detailRequest;
  selected.value = task;
  detailBusy.value = true;
  detailError.value = "";
  try {
    const detail = await adminApi.task(task.id);
    if (request !== detailRequest) return;
    selected.value = detail;
  } catch (failure) {
    if (request === detailRequest) detailError.value = adminErrorMessage(failure, "读取任务详情");
  } finally {
    if (request === detailRequest) detailBusy.value = false;
  }
}

async function runAction(task: BackgroundTask, operation: "retry" | "cancel") {
  if (actionBusy.value !== null) return;
  if (!window.confirm(`确认${operation === "retry" ? "重试" : "取消"}任务 #${task.id}？`)) return;
  actionBusy.value = task.id;
  notice.value = "";
  try {
    const updated = operation === "retry" ? await adminApi.retryTask(task.id) : await adminApi.cancelTask(task.id);
    items.value = items.value.map((item) => item.id === updated.id ? updated : item);
    if (selected.value?.id === updated.id) selected.value = updated;
    const refreshed = await load();
    notice.value = refreshed
      ? `任务 #${task.id} 已提交${operation === "retry" ? "重试" : "取消"}。`
      : `任务 #${task.id} 已提交${operation === "retry" ? "重试" : "取消"}，但任务列表刷新失败，请手动刷新确认进度。`;
    if (!refreshed) error.value = "";
  } catch (failure) {
    notice.value = adminErrorMessage(failure, operation === "retry" ? "重试任务" : "取消任务");
  } finally {
    actionBusy.value = null;
  }
}

async function recoverTimeouts() {
  if (actionBusy.value !== null) return;
  actionBusy.value = -1;
  notice.value = "";
  try {
    const task = await adminApi.recoverTimeouts();
    const refreshed = await load();
    notice.value = refreshed
      ? `超时恢复任务 #${task.id} 已提交。`
      : `超时恢复任务 #${task.id} 已提交，但任务列表刷新失败，请手动刷新确认进度。`;
    if (!refreshed) error.value = "";
  } catch (failure) {
    notice.value = adminErrorMessage(failure, "提交超时恢复任务");
  } finally {
    actionBusy.value = null;
  }
}
onMounted(load);
</script>

<template>
  <AdminPageFrame
    title="后台任务"
    description="后台任务页只允许提交服务端定义的 STALE_TASK_RECOVERY，并展示真实生命周期和安全 requestId。"
  >
    <template #actions>
      <button class="button button--small button--primary" type="button" :disabled="actionBusy !== null" @click="recoverTimeouts">恢复超时任务</button>
      <button class="button button--small" type="button" :disabled="loading || actionBusy !== null" @click="refresh">刷新</button>
    </template>

    <form class="admin-command-row admin-toolbar" @submit.prevent="applyFilters">
      <div class="admin-command-row__label"><span class="admin-kicker">任务筛选</span><strong>后台任务</strong></div>
      <label class="admin-field"><span>状态</span><select v-model="filters.status"><option value="">全部状态</option><option v-for="status in statuses" :key="status" :value="status">{{ status }}</option></select></label>
      <label class="admin-field"><span>任务类型</span><select v-model="filters.taskType"><option value="">全部类型</option><option value="STALE_TASK_RECOVERY">STALE_TASK_RECOVERY</option></select></label>
      <button class="button button--primary admin-command-row__submit" type="submit" :disabled="loading || actionBusy !== null">筛选<span aria-hidden="true">↗</span></button>
    </form>

    <InlineNotice v-if="notice" :message="notice" :tone="noticeTone(notice)" />
    <LoadingState v-if="loading" label="正在读取后台任务…" />
    <ErrorState v-else-if="error" title="后台任务读取失败" :message="error"><RetryButton @retry="load" /></ErrorState>
    <EmptyState v-else-if="!items.length" title="暂无后台任务" message="当前筛选条件没有返回任务记录。" />

    <template v-else>
      <div class="signal-strip admin-motion-enter" aria-label="后台任务摘要">
        <div class="signal-strip__item"><span class="signal-strip__label">任务总数</span><strong>{{ total }}</strong></div>
        <div class="signal-strip__item"><span class="signal-strip__label">当前页</span><strong>{{ items.length }} 条记录</strong></div>
        <div class="signal-strip__item"><span class="signal-strip__label">当前选择</span><strong>{{ selected ? selected.status : "未选择" }}</strong></div>
        <div class="signal-strip__item"><span class="signal-strip__label">恢复操作</span><strong>服务端任务</strong></div>
      </div>

      <div :class="{ 'data-rail': selected }">
        <section class="admin-data-surface admin-table-wrap admin-motion-enter" aria-label="后台任务">
          <header class="admin-data-surface__head"><div><p class="admin-kicker">执行记录</p><h2>任务列表</h2></div><span class="admin-data-surface__hint">requestId 全部来自服务端</span></header>
          <table class="admin-table admin-data-table admin-task-table">
            <thead><tr><th>任务</th><th>状态</th><th>生命周期</th><th>结果</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="task in items" :key="task.id" class="admin-data-row" :data-selected="selected?.id === task.id">
                <td><div class="admin-record"><span class="admin-record__index admin-code">#{{ task.id }}</span><div><strong>{{ task.taskType }}</strong><div class="admin-muted admin-code">requestId {{ task.requestId }}</div></div></div></td>
                <td><StatusBadge :label="task.status" :tone="tone(task.status)" /><div v-if="task.failureCode" class="admin-danger admin-data-note">{{ task.failureCode }}</div></td>
                <td><ul class="admin-timeline admin-timeline--compact"><li><span class="admin-timeline__node" aria-hidden="true"></span><span>创建 <time>{{ formatDate(task.createdAt) }}</time></span></li><li><span class="admin-timeline__node" aria-hidden="true"></span><span>开始 <time>{{ formatDate(task.startedAt) }}</time></span></li><li><span class="admin-timeline__node" aria-hidden="true"></span><span>截止 <time>{{ formatDate(task.deadlineAt) }}</time></span></li><li><span class="admin-timeline__node" aria-hidden="true"></span><span>心跳 <time>{{ formatDate(task.heartbeatAt) }}</time></span></li><li><span class="admin-timeline__node" aria-hidden="true"></span><span>结束 <time>{{ formatDate(task.finishedAt) }}</time></span></li></ul></td>
                <td><strong>{{ task.resultCount ?? "未完成" }}</strong><div class="admin-muted">尝试 {{ task.retryCount }}/{{ task.maxAttempts }}</div></td>
                <td><div class="admin-table__actions admin-action-cluster"><button class="button button--small" type="button" :disabled="detailBusy || actionBusy !== null" @click="openTask(task)">详情</button><button v-if="task.status === 'FAILED' && task.taskType === 'STALE_TASK_RECOVERY'" class="button button--small" type="button" :disabled="actionBusy !== null" @click="runAction(task, 'retry')">重试</button><button v-if="task.status === 'PENDING' && task.taskType === 'STALE_TASK_RECOVERY'" class="button button--small" type="button" :disabled="actionBusy !== null" @click="runAction(task, 'cancel')">取消</button></div></td>
              </tr>
            </tbody>
          </table>
        </section>

        <aside v-if="selected" class="admin-inspector admin-detail admin-motion-enter" aria-label="任务详情">
          <div class="admin-detail__header admin-inspector__header"><div><p class="admin-kicker">当前任务</p><h2>任务 #{{ selected.id }}</h2><p class="admin-code">{{ selected.taskType }}</p></div><button class="button button--small" type="button" @click="closeDetail">关闭</button></div>
          <LoadingState v-if="detailBusy" label="正在读取任务详情…" />
          <ErrorState v-else-if="detailError" title="任务详情读取失败" :message="detailError"><RetryButton @retry="openTask(selected)" /></ErrorState>
          <template v-else>
            <section class="admin-inspector__summary"><div><span class="admin-kicker">状态</span><StatusBadge :label="selected.status" :tone="tone(selected.status)" /></div><div><span class="admin-kicker">失败原因</span><strong>{{ selected.failureReason || "未记录" }}</strong></div></section>
            <dl><dt>状态</dt><dd>{{ selected.status }}</dd><dt>失败原因</dt><dd>{{ selected.failureReason || "未记录" }}</dd><dt>请求 ID</dt><dd class="admin-code">{{ selected.requestId }}</dd><dt>请求人</dt><dd>{{ selected.requestedByUserId ?? "未记录" }}</dd></dl>
          </template>
        </aside>
      </div>

      <div class="admin-pagination admin-pagination--rail"><span class="admin-code">第 {{ page + 1 }} 页 · {{ total }} 条记录</span><div class="admin-pagination__actions"><button class="button button--small" type="button" :disabled="page === 0 || loading || actionBusy !== null" @click="page--; notice = ''; load({ clearCurrentSelection: true })">上一页</button><button class="button button--small" type="button" :disabled="(page + 1) * size >= total || loading || actionBusy !== null" @click="page++; notice = ''; load({ clearCurrentSelection: true })">下一页</button></div></div>
    </template>
  </AdminPageFrame>
</template>
