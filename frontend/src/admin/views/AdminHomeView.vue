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
let loadRequest = 0;

const modules = computed(() => {
  const labels: Record<string, { title: string; description: string; path: string }> = {
    users: { title: "用户与角色", description: "查看安全用户投影，并执行需要确认的状态或角色变更。", path: "/admin/users" },
    reviewQueue: { title: "证据与资源审核", description: "审核资源、知识片段与演示快照的发布和核验状态。", path: "/admin/reviews" },
    backgroundTasks: { title: "后台任务", description: "查看维护任务生命周期，恢复超时任务并处理失败任务。", path: "/admin/tasks" },
    audit: { title: "审计日志", description: "只读查看管理员操作摘要、结果和 requestId。", path: "/admin/audit" },
    modelSettings: { title: "模型配置", description: "配置后端管理的模型连接，浏览器不会直接接触 provider。", path: "/admin/settings" },
    mailSettings: { title: "邮件发送设置", description: "配置服务端托管的验证码邮件投递、模板和连接测试。", path: "/admin/mail" },
  };
  return Object.entries(labels).map(([key, info]) => ({ key, ...info, status: capability.value?.modules?.[key] }));
});

async function load(): Promise<boolean> {
  const request = ++loadRequest;
  loading.value = true;
  error.value = "";
  try {
    const nextCapability = await adminApi.capabilities();
    if (request !== loadRequest) return false;
    capability.value = nextCapability;
    return true;
  } catch (failure) {
    if (request === loadRequest) error.value = adminErrorMessage(failure, "读取管理能力");
    return false;
  } finally {
    if (request === loadRequest) loading.value = false;
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

function capabilityReason(reason?: string | null) {
  const explanations: Record<string, string> = {
    NOT_CONFIGURED: "该模块尚未完成服务端配置。",
    MASTER_KEY_UNAVAILABLE: "部署环境缺少用于加密配置的主密钥。",
    MODEL_CONFIG_UNAVAILABLE: "模型配置服务暂时不可用。",
    MAIL_CONFIG_UNAVAILABLE: "邮件配置服务暂时不可用。",
    PERSISTED_CONFIGURATION_DISABLED: "已保存配置当前处于停用状态。",
    PERSISTED_QUOTA_NOT_CONFIGURED: "已保存配置尚未设置可用额度。",
  };
  return reason ? explanations[reason] || `服务返回状态：${reason}` : "";
}

onMounted(load);
</script>

<template>
  <AdminPageFrame
    title="管理总览"
    description="查看服务状态和可用的管理模块。"
  >
    <template #actions>
      <button class="button button--small" type="button" :disabled="loading" @click="load">刷新</button>
    </template>

    <LoadingState v-if="loading" label="正在读取管理能力…" />
    <ErrorState v-else-if="error" title="管理能力读取失败" :message="error"><RetryButton @retry="load" /></ErrorState>
    <EmptyState v-else-if="!capability" title="暂无能力数据" message="服务未返回管理模块状态，请稍后重试。"><RetryButton @retry="load" /></EmptyState>

    <template v-else>
      <section class="admin-hero-rail admin-panel admin-panel--focus admin-motion-enter" aria-labelledby="service-status-title">
        <span class="admin-hero-rail__index" aria-hidden="true"></span>
        <div class="admin-hero-rail__body">
          <div class="admin-hero-rail__heading">
            <div>
              <p class="admin-kicker">运行状态</p>
              <h2 id="service-status-title">服务状态</h2>
              <p>版本：{{ capability.service.version }}</p>
            </div>
            <StatusBadge :label="capability.service.status === 'AVAILABLE' ? '服务可用' : '服务不可用'" :tone="capability.service.status === 'AVAILABLE' ? 'success' : 'danger'" />
          </div>
          <dl class="admin-signal-grid" aria-label="服务摘要">
            <div><dt>服务</dt><dd>{{ capability.service.status === 'AVAILABLE' ? '正常响应' : '需要处理' }}</dd></div>
            <div><dt>模块</dt><dd>{{ modules.length }} 个管理入口</dd></div>
            <div><dt>配置</dt><dd>以服务端状态为准</dd></div>
          </dl>
        </div>
        <span class="admin-hero-rail__pulse" :aria-label="capability.service.status === 'AVAILABLE' ? '服务可用' : '服务不可用'"></span>
      </section>

      <section class="admin-module-rail" aria-labelledby="admin-module-title">
        <header class="admin-section-head">
          <div><p class="admin-kicker">管理入口</p><h2 id="admin-module-title">管理模块</h2></div>
          <span class="admin-section-head__count">{{ modules.length }} 个模块</span>
        </header>
        <div class="admin-rail-grid">
          <article v-for="item in modules" :key="item.key" class="admin-rail admin-motion-enter">
            <div class="admin-rail__topline">
              <StatusBadge :label="statusLabel(item.status)" :tone="statusTone(item.status)" />
            </div>
            <div class="admin-rail__content">
              <h3>{{ item.title }}</h3>
              <p>{{ item.description }}</p>
              <p v-if="item.status?.reason" class="admin-danger">原因：{{ capabilityReason(item.status.reason) }}</p>
            </div>
            <RouterLink class="button button--small admin-rail__action" :to="item.path">进入{{ item.title }}</RouterLink>
          </article>
        </div>
      </section>
    </template>
  </AdminPageFrame>
</template>
