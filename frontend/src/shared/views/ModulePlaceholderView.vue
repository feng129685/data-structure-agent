<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import { auth } from "../../app/providers/runtime";
import ErrorState from "../components/ErrorState.vue";
import LoadingState from "../components/LoadingState.vue";
import OfflineState from "../components/OfflineState.vue";
import PermissionState from "../components/PermissionState.vue";
import RetryButton from "../components/RetryButton.vue";
import StatusBadge from "../components/StatusBadge.vue";

const route = useRoute();
const moduleName = computed(() => String(route.meta.module || "共享工作台"));
const requiresCapability = computed(() => Boolean(route.meta.requiresCapability));
const retainedSessionInterrupted = computed(() => Boolean(auth.state.user)
  && (auth.state.status === "offline" || auth.state.status === "error"));

function retryCapability(): void {
  void auth.loadCapabilities();
}

function retrySession(): void {
  void auth.restoreSession();
}
</script>

<template>
  <section class="module-placeholder" aria-labelledby="module-title">
    <div class="module-placeholder__eyebrow">{{ route.meta.layout === "admin" ? "管理端" : "学习端" }}</div>
    <h1 id="module-title">{{ moduleName }}</h1>
    <p>共享应用基础已就绪，业务页面将在后续模块中接入冻结后的 Spring v1 接口。</p>
    <LoadingState
      v-if="requiresCapability && auth.state.capabilityStatus === 'loading'"
      label="正在确认当前账号的模块能力"
    />
    <PermissionState
      v-else-if="requiresCapability && auth.state.capabilityStatus === 'forbidden'"
      title="没有使用此模块的权限"
      message="当前会话仍然有效，但服务已明确拒绝该模块能力。"
    />
    <ErrorState
      v-else-if="requiresCapability && auth.state.capabilityStatus === 'unavailable'"
      title="能力服务暂时不可用"
      message="当前页面位置已保留。服务恢复后可重新确认模块能力。"
    >
      <RetryButton label="重新确认能力" :on-retry="retryCapability" />
    </ErrorState>
    <ErrorState
      v-else-if="requiresCapability && auth.state.capabilityStatus === 'unknown'"
      title="模块能力尚未确认"
      message="需要先确认当前账号是否可以使用此模块。"
    >
      <RetryButton label="确认模块能力" :on-retry="retryCapability" />
    </ErrorState>
    <OfflineState
      v-else-if="auth.state.status === 'offline' && auth.state.user"
      title="当前离线，会话已保留"
      message="恢复网络后可重新验证会话，不会丢失当前导航位置。"
    >
      <RetryButton label="重新连接" :on-retry="retrySession" />
    </OfflineState>
    <ErrorState
      v-else-if="retainedSessionInterrupted"
      title="认证服务暂时不可用，会话已保留"
      message="当前导航位置不会丢失。服务恢复后可重新验证会话。"
    >
      <RetryButton label="重试验证" :on-retry="retrySession" />
    </ErrorState>
    <StatusBadge
      v-else
      :label="auth.state.user ? '会话已恢复' : '访客模式'"
      :tone="auth.state.user ? 'success' : 'neutral'"
    />
  </section>
</template>
