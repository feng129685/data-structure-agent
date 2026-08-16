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
let loadRequest = 0;
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

function reload() {
  if (loading.value || saving.value || testing.value) return;
  if (isDirty.value && !window.confirm("模型配置还有未保存的更改，确定重新读取并丢弃这些更改吗？")) return;
  void load();
}

async function load(): Promise<boolean> {
  const request = ++loadRequest;
  loading.value = true;
  error.value = "";
  savedMessage.value = "";
  testMessage.value = "";
  try {
    const nextCapability = await adminApi.getModelConfig();
    if (request !== loadRequest) return false;
    capability.value = nextCapability;
    fill(nextCapability.configuration);
    return true;
  } catch (failure) {
    if (request === loadRequest) error.value = adminErrorMessage(failure, "读取模型配置");
    return false;
  } finally {
    if (request === loadRequest) loading.value = false;
  }
}

function numberValue(value: string, label: string, min: number, max: number, integer = false): number | undefined {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || (integer && !Number.isInteger(parsed)) || parsed < min || parsed > max) {
    throw new Error(`${label}必须${integer ? "是整数且" : ""}在 ${min} 到 ${max} 之间`);
  }
  return parsed;
}

function payloadFromForm(): Record<string, unknown> | null {
  try {
    if (!form.provider.trim() || !form.baseUrl.trim() || !form.model.trim()) {
      throw new Error("服务提供方、服务地址和模型标识均不能为空");
    }
    if (credentialRequired.value && !apiKey.value) {
      error.value = "保存未完成（API_KEY_REQUIRED）。首次配置、服务提供方或服务地址改变时必须重新输入 API Key。";
      return null;
    }
    const payload: Record<string, unknown> = {
      provider: form.provider.trim(),
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      enabled: form.enabled,
    };
    const temperature = numberValue(form.temperature, "温度", 0, 2);
    const maxOutputTokens = numberValue(form.maxOutputTokens, "最大输出令牌数", 1, 32768, true);
    const requestTimeoutMs = numberValue(form.requestTimeoutMs, "请求超时", 1000, 120000, true);
    const retryCount = numberValue(form.retryCount, "重试次数", 0, 5, true);
    const dailyTokenQuota = numberValue(form.dailyTokenQuota, "每日令牌额度", 0, 10000000, true);
    if (temperature !== undefined) payload.temperature = temperature;
    if (maxOutputTokens !== undefined) payload.maxOutputTokens = maxOutputTokens;
    if (requestTimeoutMs !== undefined) payload.requestTimeoutMs = requestTimeoutMs;
    if (retryCount !== undefined) payload.retryCount = retryCount;
    if (dailyTokenQuota !== undefined) payload.dailyTokenQuota = dailyTokenQuota;
    const effectiveQuota = dailyTokenQuota ?? loadedConfig.value?.dailyTokenQuota ?? 0;
    if (form.enabled && effectiveQuota < 1) throw new Error("启用配置前，每日令牌额度必须至少为 1");
    if (apiKey.value) payload.apiKey = apiKey.value;
    return payload;
  } catch (failure) {
    error.value = `保存未完成（VALIDATION_ERROR）。${failure instanceof Error ? failure.message : "请检查输入"}`;
    return null;
  }
}

async function save() {
  if (saving.value || testing.value) return;
  error.value = ""; savedMessage.value = ""; testMessage.value = "";
  const payload = payloadFromForm();
  if (!payload) return;
  saving.value = true;
  try {
    const updated = await adminApi.updateModelConfig(payload);
    capability.value = {
      available: updated.enabled && updated.dailyTokenQuota > 0,
      reason: updated.enabled ? (updated.dailyTokenQuota > 0 ? null : "PERSISTED_QUOTA_NOT_CONFIGURED") : "PERSISTED_CONFIGURATION_DISABLED",
      configuration: updated,
    };
    fill(updated);
    savedMessage.value = "配置已保存。API Key 仅在本次请求中使用，浏览器输入已清空。";
  } catch (failure) { error.value = adminErrorMessage(failure, "保存模型配置"); }
  finally { apiKey.value = ""; saving.value = false; }
}

async function testConnection() {
  if (testing.value || saving.value) return;
  error.value = "";
  testMessage.value = "";
  if (!loadedConfig.value) {
    testMessage.value = "尚未保存模型配置，无法执行连接测试。";
    testTone.value = "warning";
    return;
  }
  if (isDirty.value) {
    testMessage.value = "当前表单有未保存的更改。连接测试只验证已保存的服务端配置，请先保存后再测试。";
    testTone.value = "warning";
    return;
  }
  testing.value = true;
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
  <AdminPageFrame title="模型设置" description="配置服务端托管的模型连接、配额与连接测试。浏览器不会读取或保存 API Key。">
    <template #actions>
      <StatusBadge :label="loadedConfig ? (loadedConfig.enabled ? '已启用' : '已停用') : '待配置'" :tone="loadedConfig?.enabled ? 'success' : 'warning'" />
      <button class="button button--small" type="button" :disabled="loading || saving || testing" @click="reload">重新读取</button>
    </template>
    <LoadingState v-if="loading" label="正在读取模型配置…" />
    <ErrorState v-else-if="error && !capability" title="模型配置不可读取" :message="error"><RetryButton @retry="load" /></ErrorState>
    <template v-else>
      <section class="admin-hero-rail admin-panel admin-motion-enter" aria-labelledby="model-configuration-status">
        <span class="admin-hero-rail__index" aria-hidden="true"></span>
        <div class="admin-hero-rail__body">
          <div class="admin-hero-rail__heading">
            <div>
              <p class="admin-kicker">模型连接</p>
              <h2 id="model-configuration-status">配置状态</h2>
              <p>凭据仅在保存时发送到服务端，页面不会回填已保存的 API Key。</p>
            </div>
            <StatusBadge :label="capability?.available ? '可用' : '待配置'" :tone="capability?.available ? 'success' : 'warning'" />
          </div>
          <div class="signal-strip" aria-label="模型配置状态">
            <div class="signal-strip__item"><span class="signal-strip__label">服务状态</span><strong>{{ capability?.available ? "可用" : "未配置" }}</strong></div>
            <div class="signal-strip__item"><span class="signal-strip__label">凭据状态</span><strong>{{ loadedConfig?.apiKeyConfigured ? "已加密" : "待配置" }}</strong></div>
            <div class="signal-strip__item"><span class="signal-strip__label">最近测试</span><strong>{{ loadedConfig?.lastConnectionTestStatus || "未测试" }}</strong></div>
            <div class="signal-strip__item"><span class="signal-strip__label">更新时间</span><strong>{{ formatDate(loadedConfig?.updatedAt) }}</strong></div>
          </div>
        </div>
        <span class="admin-hero-rail__pulse" :aria-label="capability?.available ? '模型配置可用' : '模型配置待处理'"></span>
      </section>
      <InlineNotice v-if="capability?.reason" :message="reasonText(capability.reason)" :tone="capability.reason === 'NOT_CONFIGURED' ? 'warning' : capability.reason === 'MASTER_KEY_UNAVAILABLE' ? 'danger' : 'neutral'" />
      <InlineNotice v-if="dirtyConnection && loadedConfig?.apiKeyConfigured" message="服务提供方或服务地址已改变，保存前必须重新输入 API Key。" tone="warning" />
      <InlineNotice v-if="isDirty" message="当前表单有未保存的更改。连接测试只验证已保存的服务端配置，请先保存后再测试。" tone="warning" />
      <InlineNotice v-if="error" :message="error" tone="danger" />
      <InlineNotice v-if="savedMessage" :message="savedMessage" tone="success" />
      <InlineNotice v-if="testMessage" :message="testMessage" :tone="testTone" />
      <section class="admin-panel admin-panel--focus panel-enter">
        <div class="admin-panel__header"><div><p class="admin-kicker">编辑连接</p><h2>模型连接配置</h2><p>未配置时字段保持为空，不展示虚构的 provider 或模型名称。</p></div></div>
        <form class="admin-form" aria-label="模型连接配置" @submit.prevent="save">
          <div class="admin-form__grid">
            <label class="admin-field"><span>服务提供方（Provider）</span><input v-model="form.provider" autocomplete="off" maxlength="128" required /></label>
            <label class="admin-field"><span>模型标识（Model ID）</span><input v-model="form.model" autocomplete="off" maxlength="512" required /></label>
            <label class="admin-field admin-field--full"><span>服务地址（Base URL）</span><input v-model="form.baseUrl" type="url" autocomplete="off" maxlength="2048" required /></label>
            <label class="admin-field admin-field--full"><span>API Key（仅本次保存使用）</span><input v-model="apiKey" type="password" autocomplete="new-password" maxlength="4096" :placeholder="loadedConfig?.apiKeyConfigured ? '已配置，留空表示不更换' : '首次保存必须填写'" /></label>
            <label class="admin-field"><span>温度（Temperature）</span><input v-model="form.temperature" type="number" min="0" max="2" step="0.01" /></label>
            <label class="admin-field"><span>最大输出令牌数</span><input v-model="form.maxOutputTokens" type="number" min="1" max="32768" step="1" /></label>
            <label class="admin-field"><span>请求超时（毫秒）</span><input v-model="form.requestTimeoutMs" type="number" min="1000" max="120000" step="1" /></label>
            <label class="admin-field"><span>重试次数</span><input v-model="form.retryCount" type="number" min="0" max="5" step="1" /></label>
            <label class="admin-field"><span>每日令牌额度</span><input v-model="form.dailyTokenQuota" type="number" min="0" max="10000000" step="1" /></label>
            <label class="admin-check"><input v-model="form.enabled" type="checkbox" /> <span>启用持久化配置（额度为零时服务端会拒绝）</span></label>
          </div>
          <div class="admin-form__actions"><button class="button button--primary" type="submit" :disabled="saving || testing || capability?.reason === 'MASTER_KEY_UNAVAILABLE'">{{ saving ? "保存中…" : "保存配置" }}</button><button class="button" type="button" :disabled="testing || saving || isDirty || !loadedConfig" @click="testConnection">{{ testing ? "测试中…" : "测试连接（已保存配置）" }}</button><span v-if="loadedConfig?.lastConnectionTestStatus" class="admin-muted">最近测试：{{ loadedConfig.lastConnectionTestStatus }} · {{ formatDate(loadedConfig.lastConnectionTestedAt) }}</span></div>
        </form>
      </section>
      <section class="admin-panel admin-panel--quiet panel-enter" style="--panel-delay: 90ms">
        <div class="admin-panel__header"><div><p class="admin-kicker">安全与额度</p><h2>运行边界</h2></div></div>
        <div class="guardrail-grid"><div><span>凭据</span><strong>只在服务端加密存储</strong><small>API Key 不会被回填到表单。</small></div><div><span>额度</span><strong>{{ loadedConfig?.dailyTokenQuota ?? "未配置" }}</strong><small>每日 token 配额由后端结算。</small></div><div><span>变更时间</span><strong>{{ formatDate(loadedConfig?.updatedAt) }}</strong><small>所有保存动作进入管理员审计。</small></div></div>
      </section>
    </template>
  </AdminPageFrame>
</template>
