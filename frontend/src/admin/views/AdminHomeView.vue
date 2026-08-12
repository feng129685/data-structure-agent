<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import AdminPageFrame from "../components/AdminPageFrame.vue";
import { adminApi, adminErrorMessage } from "../api";
import type { AdminCapability, AdminModuleCapability } from "../../shared/types";
import LoadingState from "../../shared/components/LoadingState.vue";
import ErrorState from "../../shared/components/ErrorState.vue";
import EmptyState from "../../shared/components/EmptyState.vue";
import RetryButton from "../../shared/components/RetryButton.vue";
import StatusBadge from "../../shared/components/StatusBadge.vue";

const capability = ref<AdminCapability | null>(null);
const loading = ref(true);
const error = ref("");

const modules = computed(() => {
  const labels: Record<string, { title: string; description: string; path: string }> = {
    users: { title: "用户与角色", description: "查看安全用户投影，并执行需要确认的状态或角色变更。", path: "/admin/users" },
    reviewQueue: { title: "证据与资源审核", description: "审核资源、知识片段与演示快照的发布和核验状态。", path: "/admin/reviews" },
    backgroundTasks: { title: "后台任务", description: "查看维护任务生命周期，恢复超时任务并处理失败任务。", path: "/admin/tasks" },
    audit: { title: "审计日志", description: "只读查看管理员操作摘要、结果和 requestId。", path: "/admin/audit" },
    modelSettings: { title: "模型配置", description: "配置后端管理的模型连接，浏览器不会直接接触 provider。", path: "/admin/settings" },
  };
  return Object.entries(labels).map(([key, info]) => ({ key, ...info, status: capability.value?.modules?.[key] }));
});

async function load() {
  loading.value = true;
  error.value = "";
  try {
    capability.value = await adminApi.capabilities();
  } catch (failure) {
    error.value = adminErrorMessage(failure, "读取管理能力");
  } finally {
    loading.value = false;
  }
}

function statusLabel(status?: AdminModuleCapability) {
  if (!status) return "未返回状态";
  if (status.available) return "可用";
  if (status.status === "NOT_CONFIGURED") return "未配置";
  return "暂不可用";
}

function statusTone(status?: AdminModuleCapability): "success" | "warning" | "danger" | "neutral" {
  if (!status) return "neutral";
  return status.available ? "success" : status.status === "NOT_CONFIGURED" ? "warning" : "danger";
}

onMounted(load);
</script>

<template>
  <AdminPageFrame title="管理总览" description="从真实能力接口查看当前服务状态和管理入口。未提供聚合统计时不显示推测数量。">
    <template #actions><button class="button button--small" type="button" :disabled="loading" @click="load">刷新状态</button></template>
    <LoadingState v-if="loading" label="正在读取管理能力…" />
    <ErrorState v-else-if="error" title="管理能力读取失败" :message="error"><RetryButton @retry="load" /></ErrorState>
    <EmptyState v-else-if="!capability" title="暂无能力数据" message="服务未返回管理模块状态，请稍后重试。"><RetryButton @retry="load" /></EmptyState>
    <template v-else>
      <section class="admin-panel">
        <div class="admin-detail__header"><div><h2>Spring v1 服务</h2><p>服务版本和可用性来自 `/api/v1/admin/capabilities`。</p></div><StatusBadge :label="capability.service.status === 'AVAILABLE' ? '服务可用' : '服务不可用'" :tone="capability.service.status === 'AVAILABLE' ? 'success' : 'danger'" /></div>
        <dl><dt>版本</dt><dd class="admin-code">{{ capability.service.version }}</dd><dt>当前管理员</dt><dd class="admin-code">#{{ capability.userId }}</dd></dl>
      </section>
      <section class="admin-grid" aria-label="管理模块">
        <article v-for="item in modules" :key="item.key" class="admin-module">
          <div class="admin-module__meta"><span class="admin-code">{{ item.key }}</span><StatusBadge :label="statusLabel(item.status)" :tone="statusTone(item.status)" /></div>
          <h2>{{ item.title }}</h2><p>{{ item.description }}</p>
          <p v-if="item.status?.reason" class="admin-danger">原因：{{ item.status.reason }}</p>
          <RouterLink class="button button--small" :to="item.path">进入{{ item.title }}</RouterLink>
        </article>
      </section>
    </template>
  </AdminPageFrame>
</template>
