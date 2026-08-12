<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import AdminPageFrame from "../components/AdminPageFrame.vue";
import { adminApi, adminErrorMessage, formatDate } from "../api";
import type { ModelConfig, ModelConfigCapability } from "../../shared/types";
import LoadingState from "../../shared/components/LoadingState.vue";
import ErrorState from "../../shared/components/ErrorState.vue";
import RetryButton from "../../shared/components/RetryButton.vue";
import InlineNotice from "../../shared/components/InlineNotice.vue";
import StatusBadge from "../../shared/components/StatusBadge.vue";

const capability = ref<ModelConfigCapability | null>(null);
const loading = ref(true);
const saving = ref(false);
const testing = ref(false);
const error = ref("");
const savedMessage = ref("");
const testMessage = ref("");
const testTone = ref<"neutral" | "success" | "warning" | "danger">("neutral");
const apiKey = ref("");
const loadedConfig = ref<ModelConfig | null>(null);
const loadedFormSignature = ref("");
const formInitialized = ref(false);
const form = reactive({
  provider: "", baseUrl: "", model: "", temperature: "", maxOutputTokens: "", requestTimeoutMs: "", retryCount: "", dailyTokenQuota: "", enabled: false,
});

const credentialRequired = computed(() => !loadedConfig.value?.apiKeyConfigured || form.provider.trim() !== (loadedConfig.value?.provider || "") || form.baseUrl.trim() !== (loadedConfig.value?.baseUrl || ""));
const dirtyConnection = computed(() => Boolean(loadedConfig.value && (form.provider.trim() !== loadedConfig.value.provider || form.baseUrl.trim() !== loadedConfig.value.baseUrl)));
const isDirty = computed(() => formInitialized.value && (apiKey.value.length > 0 || formSignature() !== loadedFormSignature.value));

function formSignature() {
  return JSON.stringify({
    provider: form.provider,
    baseUrl: form.baseUrl,
    model: form.model,
    temperature: form.temperature,
    maxOutputTokens: form.maxOutputTokens,
    requestTimeoutMs: form.requestTimeoutMs,
    retryCount: form.retryCount,
    dailyTokenQuota: form.dailyTokenQuota,
    enabled: form.enabled,
  });
}

function fill(config?: ModelConfig | null) {
  loadedConfig.value = config || null;
  form.provider = config?.provider || ""; form.baseUrl = config?.baseUrl || ""; form.model = config?.model || "";
  form.temperature = config ? String(config.temperature) : ""; form.maxOutputTokens = config ? String(config.maxOutputTokens) : "";
  form.requestTimeoutMs = config ? String(config.requestTimeoutMs) : ""; form.retryCount = config ? String(config.retryCount) : "";
  form.dailyTokenQuota = config ? String(config.dailyTokenQuota) : ""; form.enabled = config?.enabled ?? false;
  apiKey.value = "";
  loadedFormSignature.value = formSignature();
  formInitialized.value = true;
}

function warnBeforeUnload(event: BeforeUnloadEvent | Event) {
  if (!isDirty.value) return;
  event.preventDefault();
  (event as BeforeUnloadEvent).returnValue = "";
}

function reasonText(reason?: string | null) {
  const labels: Record<string, string> = {
    MASTER_KEY_UNAVAILABLE: "服务器未配置 MODEL_CONFIG_MASTER_KEY，需要部署环境完成加密根密钥配置。",
    NOT_CONFIGURED: "尚未保存模型配置，可以填写下面的表单。",
    PERSISTED_CONFIGURATION_DISABLED: "已保存的模型配置当前被禁用。",
    PERSISTED_QUOTA_NOT_CONFIGURED: "已保存配置没有可用的每日额度。",
    MODEL_CONFIG_UNAVAILABLE: "模型配置服务暂不可用，请稍后重试。",
  };
  return reason ? labels[reason] || `服务状态：${reason}` : "";
}

async function load() {
  loading.value = true; error.value = ""; savedMessage.value = "";
  try { capability.value = await adminApi.getModelConfig(); fill(capability.value.configuration); }
  catch (failure) { error.value = adminErrorMessage(failure, "读取模型配置"); }
  finally { loading.value = false; }
}

function numberOrUndefined(value: string): number | undefined { const trimmed = value.trim(); return trimmed ? Number(trimmed) : undefined; }

async function save() {
  if (saving.value || testing.value) return;
  error.value = ""; savedMessage.value = ""; testMessage.value = "";
  if (!form.provider.trim() || !form.baseUrl.trim() || !form.model.trim()) { error.value = "保存未完成（VALIDATION_ERROR）。Provider、Base URL 和 Model ID 均不能为空。"; return; }
  if (credentialRequired.value && !apiKey.value) { error.value = "保存未完成（API_KEY_REQUIRED）。首次配置、Provider 或 Base URL 改变时必须重新输入 API Key。"; return; }
  saving.value = true;
  const payload: Record<string, unknown> = { provider: form.provider.trim(), baseUrl: form.baseUrl.trim(), model: form.model.trim(), enabled: form.enabled };
  const numericFields: Array<[string, string]> = [["temperature", form.temperature], ["maxOutputTokens", form.maxOutputTokens], ["requestTimeoutMs", form.requestTimeoutMs], ["retryCount", form.retryCount], ["dailyTokenQuota", form.dailyTokenQuota]];
  for (const [key, value] of numericFields) { const parsed = numberOrUndefined(value); if (parsed !== undefined && Number.isFinite(parsed)) payload[key] = parsed; }
  if (apiKey.value) payload.apiKey = apiKey.value;
  try {
    const updated = await adminApi.updateModelConfig(payload);
    fill(updated); savedMessage.value = "配置已保存。API Key 仅在本次请求中使用，浏览器输入已清空。";
  } catch (failure) { error.value = adminErrorMessage(failure, "保存模型配置"); }
  finally { apiKey.value = ""; saving.value = false; }
}

async function testConnection() {
  if (testing.value || saving.value) return;
  error.value = ""; testMessage.value = ""; testing.value = true;
  try {
    const result = await adminApi.testModelConnection();
    testMessage.value = result.connected ? `连接测试返回 ${result.code}。这只表示本次探测结果，不代表持续可用。` : `连接测试返回 ${result.code}，请根据状态检查后端配置。`;
    testTone.value = result.connected ? "success" : "warning";
    if (capability.value?.configuration) capability.value.configuration.lastConnectionTestStatus = result.code;
  } catch (failure) { testMessage.value = adminErrorMessage(failure, "测试模型连接"); testTone.value = "danger"; }
  finally { testing.value = false; }
}

onBeforeRouteLeave(() => !isDirty.value || window.confirm("模型配置还有未保存的更改，确定离开此页面吗？"));
onMounted(() => {
  window.addEventListener("beforeunload", warnBeforeUnload);
  void load();
});
onBeforeUnmount(() => window.removeEventListener("beforeunload", warnBeforeUnload));
</script>

<template>
  <AdminPageFrame title="模型配置" description="配置由 Spring 后端加密保存并代为访问 provider。浏览器不会读取、保存或直接发送模型 API Key。">
    <template #actions><button class="button button--small" type="button" :disabled="loading || saving" @click="load">重新读取</button></template>
    <LoadingState v-if="loading" label="正在读取模型配置…" />
    <ErrorState v-else-if="error && !capability" title="模型配置不可读取" :message="error"><RetryButton @retry="load" /></ErrorState>
    <template v-else>
      <InlineNotice v-if="capability?.reason" :message="reasonText(capability.reason)" :tone="capability.reason === 'NOT_CONFIGURED' ? 'warning' : capability.reason === 'MASTER_KEY_UNAVAILABLE' ? 'danger' : 'neutral'" />
      <InlineNotice v-if="dirtyConnection && loadedConfig?.apiKeyConfigured" message="Provider 或 Base URL 已改变，保存前必须重新输入 API Key。" tone="warning" />
      <InlineNotice v-if="error" :message="error" tone="danger" />
      <InlineNotice v-if="savedMessage" :message="savedMessage" tone="success" />
      <InlineNotice v-if="testMessage" :message="testMessage" :tone="testTone" />
      <section class="admin-panel">
        <div class="admin-detail__header"><div><h2>连接参数</h2><p>未配置时字段保持为空，不展示虚构的 provider 或模型名称。</p></div><StatusBadge :label="loadedConfig?.enabled ? '已启用' : '未启用'" :tone="loadedConfig?.enabled ? 'success' : 'neutral'" /></div>
        <form class="admin-form" @submit.prevent="save">
          <div class="admin-form__grid">
            <label class="admin-field"><span>Provider</span><input v-model="form.provider" autocomplete="off" maxlength="128" required /></label>
            <label class="admin-field"><span>Model ID</span><input v-model="form.model" autocomplete="off" maxlength="512" required /></label>
            <label class="admin-field admin-field--full"><span>Base URL</span><input v-model="form.baseUrl" type="url" autocomplete="off" maxlength="2048" required /></label>
            <label class="admin-field admin-field--full"><span>API Key（仅本次保存使用）</span><input v-model="apiKey" type="password" autocomplete="new-password" maxlength="4096" :placeholder="loadedConfig?.apiKeyConfigured ? '已配置，留空表示不更换' : '首次保存必须填写'" /></label>
            <label class="admin-field"><span>Temperature</span><input v-model="form.temperature" type="number" min="0" max="2" step="0.01" /></label>
            <label class="admin-field"><span>Max output tokens</span><input v-model="form.maxOutputTokens" type="number" min="1" max="32768" step="1" /></label>
            <label class="admin-field"><span>Request timeout (ms)</span><input v-model="form.requestTimeoutMs" type="number" min="1000" max="120000" step="1" /></label>
            <label class="admin-field"><span>Retry count</span><input v-model="form.retryCount" type="number" min="0" max="5" step="1" /></label>
            <label class="admin-field"><span>Daily token quota</span><input v-model="form.dailyTokenQuota" type="number" min="0" max="10000000" step="1" /></label>
            <label class="admin-check"><input v-model="form.enabled" type="checkbox" /> <span>启用持久化配置（额度为零时服务端会拒绝）</span></label>
          </div>
          <div class="admin-form__actions"><button class="button button--primary" type="submit" :disabled="saving || testing || capability?.reason === 'MASTER_KEY_UNAVAILABLE'">{{ saving ? "保存中…" : "保存配置" }}</button><button class="button" type="button" :disabled="testing || saving" @click="testConnection">{{ testing ? "测试中…" : "测试连接" }}</button><span v-if="loadedConfig?.lastConnectionTestStatus" class="admin-muted">最近测试：{{ loadedConfig.lastConnectionTestStatus }} · {{ formatDate(loadedConfig.lastConnectionTestedAt) }}</span></div>
        </form>
      </section>
    </template>
  </AdminPageFrame>
</template>
