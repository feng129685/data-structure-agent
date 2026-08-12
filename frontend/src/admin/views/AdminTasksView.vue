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
const filters = reactive<{ status: string; taskType: string }>({ status: "", taskType: "STALE_TASK_RECOVERY" });
const statuses: BackgroundTaskStatus[] = ["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED"];
function noticeTone(value: string): "success" | "danger" { return value.includes("未完成") || value.includes("失败") ? "danger" : "success"; }
async function load() { loading.value = true; error.value = ""; try { const result = await adminApi.tasks({ page: page.value, size, status: filters.status, taskType: filters.taskType }); items.value = result.items; total.value = result.total; } catch (failure) { error.value = adminErrorMessage(failure, "读取后台任务"); } finally { loading.value = false; } }
function applyFilters() { page.value = 0; void load(); }
function tone(status: BackgroundTaskStatus): "success" | "danger" | "warning" | "neutral" { return status === "SUCCEEDED" ? "success" : status === "FAILED" || status === "CANCELED" ? "danger" : status === "RUNNING" ? "warning" : "neutral"; }
async function openTask(task: BackgroundTask) {
  selected.value = task; detailBusy.value = true; detailError.value = "";
  try { selected.value = await adminApi.task(task.id); }
  catch (failure) { detailError.value = adminErrorMessage(failure, "读取任务详情"); }
  finally { detailBusy.value = false; }
}
async function runAction(task: BackgroundTask, operation: "retry" | "cancel") { if (!window.confirm(`确认${operation === "retry" ? "重试" : "取消"}任务 #${task.id}？`)) return; actionBusy.value = task.id; notice.value = ""; try { const updated = operation === "retry" ? await adminApi.retryTask(task.id) : await adminApi.cancelTask(task.id); items.value = items.value.map((item) => item.id === updated.id ? updated : item); selected.value = updated; notice.value = `任务 #${task.id} 已提交${operation === "retry" ? "重试" : "取消"}。`; } catch (failure) { notice.value = adminErrorMessage(failure, operation === "retry" ? "重试任务" : "取消任务"); } finally { actionBusy.value = null; } }
async function recoverTimeouts() { actionBusy.value = -1; notice.value = ""; try { const task = await adminApi.recoverTimeouts(); notice.value = `超时恢复任务 #${task.id} 已提交。`; await load(); } catch (failure) { notice.value = adminErrorMessage(failure, "提交超时恢复任务"); } finally { actionBusy.value = null; } }
onMounted(load);
</script>

<template>
  <AdminPageFrame title="后台任务" description="后台任务页只允许提交服务端定义的 STALE_TASK_RECOVERY，并展示真实生命周期和安全 requestId。">
    <template #actions><button class="button button--small" type="button" :disabled="actionBusy !== null" @click="recoverTimeouts">恢复超时任务</button><button class="button button--small" type="button" :disabled="loading" @click="load">刷新</button></template>
    <form class="admin-toolbar" @submit.prevent="applyFilters"><label class="admin-field"><span>状态</span><select v-model="filters.status"><option value="">全部状态</option><option v-for="status in statuses" :key="status" :value="status">{{ status }}</option></select></label><label class="admin-field"><span>任务类型</span><select v-model="filters.taskType"><option value="">全部类型</option><option value="STALE_TASK_RECOVERY">STALE_TASK_RECOVERY</option></select></label><button class="button button--primary" type="submit">筛选</button></form>
    <InlineNotice v-if="notice" :message="notice" :tone="noticeTone(notice)" />
    <LoadingState v-if="loading" label="正在读取后台任务…" /><ErrorState v-else-if="error" title="后台任务读取失败" :message="error"><RetryButton @retry="load" /></ErrorState><EmptyState v-else-if="!items.length" title="暂无后台任务" message="当前筛选条件没有返回任务记录。" />
    <section v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>任务</th><th>状态</th><th>生命周期</th><th>结果</th><th>操作</th></tr></thead><tbody><tr v-for="task in items" :key="task.id"><td><strong>#{{ task.id }}</strong><div class="admin-muted">{{ task.taskType }} · requestId {{ task.requestId }}</div></td><td><StatusBadge :label="task.status" :tone="tone(task.status)" /><div v-if="task.failureCode" class="admin-danger">{{ task.failureCode }}</div></td><td><div>创建：{{ formatDate(task.createdAt) }}</div><div>开始：{{ formatDate(task.startedAt) }}</div><div>截止：{{ formatDate(task.deadlineAt) }}</div><div>心跳：{{ formatDate(task.heartbeatAt) }}</div><div>结束：{{ formatDate(task.finishedAt) }}</div></td><td>{{ task.resultCount ?? "未完成" }}<div class="admin-muted">尝试 {{ task.retryCount }}/{{ task.maxAttempts }}</div></td><td><div class="admin-table__actions"><button class="button button--small" type="button" :disabled="detailBusy && selected?.id === task.id" @click="openTask(task)">详情</button><button v-if="task.status === 'FAILED' && task.taskType === 'STALE_TASK_RECOVERY'" class="button button--small" type="button" :disabled="actionBusy === task.id" @click="runAction(task, 'retry')">重试</button><button v-if="task.status === 'PENDING' && task.taskType === 'STALE_TASK_RECOVERY'" class="button button--small" type="button" :disabled="actionBusy === task.id" @click="runAction(task, 'cancel')">取消</button></div></td></tr></tbody></table></section>
    <div class="admin-pagination"><span>第 {{ page + 1 }} 页 · 共 {{ total }} 条</span><div class="admin-pagination__actions"><button class="button button--small" type="button" :disabled="page === 0 || loading" @click="page--; load()">上一页</button><button class="button button--small" type="button" :disabled="(page + 1) * size >= total || loading" @click="page++; load()">下一页</button></div></div>
    <aside v-if="selected" class="admin-detail" aria-label="任务详情"><div class="admin-detail__header"><div><h2>任务 #{{ selected.id }}</h2><p>{{ selected.taskType }}</p></div><button class="button button--small" type="button" @click="selected = null; detailError = ''">关闭</button></div><LoadingState v-if="detailBusy" label="正在读取任务详情…" /><ErrorState v-else-if="detailError" title="任务详情读取失败" :message="detailError"><RetryButton @retry="openTask(selected)" /></ErrorState><dl v-else><dt>状态</dt><dd>{{ selected.status }}</dd><dt>失败原因</dt><dd>{{ selected.failureReason || "未记录" }}</dd><dt>请求 ID</dt><dd class="admin-code">{{ selected.requestId }}</dd><dt>请求人</dt><dd>{{ selected.requestedByUserId ?? "未记录" }}</dd></dl></aside>
  </AdminPageFrame>
</template>
