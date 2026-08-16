<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import AdminPageFrame from "../components/AdminPageFrame.vue";
import LiquidMetalButton from "../components/LiquidMetalButton.vue";
import { adminApi, adminErrorMessage, formatDate } from "../api";
import { auth } from "../../app/providers/runtime";
import type { MailConfig, MailConfigCapability, MailSecurityMode, UpdateMailConfigRequest } from "../../shared/types";
import LoadingState from "../../shared/components/LoadingState.vue";
import ErrorState from "../../shared/components/ErrorState.vue";
import RetryButton from "../../shared/components/RetryButton.vue";
import InlineNotice from "../../shared/components/InlineNotice.vue";
import StatusBadge from "../../shared/components/StatusBadge.vue";

const DEFAULT_TEMPLATE = `<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>{{site_name}} 邮箱验证码</title></head>
<body style="margin:0;padding:24px;background:#eef0eb;color:#181a18;font-family:Georgia,Times New Roman,serif;">
  <div style="display:none;max-height:0;overflow:hidden;opacity:0;">你的 {{site_name}} 登录验证码是 {{code}}</div>
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="width:100%;background:#eef0eb;border-collapse:collapse;"><tr><td align="center">
    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="width:100%;max-width:640px;overflow:hidden;border:1px solid #cfd3ce;border-radius:8px;background:#fbfbf8;border-collapse:separate;">
      <tr><td style="padding:26px 30px;border-bottom:1px solid #d7dad5;"><table role="presentation" cellspacing="0" cellpadding="0" style="border-collapse:collapse;"><tr>
        <td width="64" style="width:64px;vertical-align:middle;"><div style="display:block;width:52px;height:52px;border-radius:14px;background:#181a18;color:#fbfbf8;text-align:center;font:700 18px/52px Georgia,serif;">ds</div></td>
        <td style="vertical-align:middle;"><div style="color:#181a18;font-family:Bookman Old Style,Georgia,serif;font-size:23px;font-weight:700;line-height:1.1;">{{site_name}}</div><div style="margin-top:5px;color:#646a65;font-size:12px;line-height:1.2;">{{site_name}} ACCOUNT VERIFICATION</div></td>
      </tr></table></td></tr>
      <tr><td style="padding:34px 30px 30px;"><div style="color:#555c56;font-size:12px;font-weight:700;line-height:1.4;">邮箱安全验证</div><h1 style="margin:9px 0 14px;color:#181a18;font-size:30px;font-weight:500;line-height:1.2;">验证你的邮箱</h1><p style="margin:0 0 22px;color:#4f5650;font-size:15px;line-height:1.75;">你正在登录 {{site_name}}。请使用下面的验证码完成验证：</p>
        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="width:100%;border-radius:6px;background:#181a18;border-collapse:separate;"><tr><td align="center" style="padding:24px 18px;"><div style="margin-bottom:9px;color:#aeb4ae;font-size:11px;line-height:1.2;">VERIFICATION CODE</div><div style="color:#fbfbf8;font-family:Bookman Old Style,Georgia,serif;font-size:36px;font-weight:700;line-height:1;letter-spacing:7px;">{{code}}</div></td></tr></table>
        <p style="margin:22px 0 0;color:#343a35;font-size:14px;line-height:1.7;">验证码将在 <strong>{{expires_minutes}} 分钟</strong>后失效，请勿转发给他人。</p><p style="margin:8px 0 0;color:#737a74;font-size:13px;line-height:1.7;">如果不是你本人操作，可以忽略这封邮件。</p>
      </td></tr><tr><td style="padding:18px 30px;border-top:1px solid #d7dad5;background:#f1f2ee;color:#747b75;font-size:12px;line-height:1.6;">此邮件由 {{site_name}} 自动发送，请勿直接回复。</td></tr>
    </table>
  </td></tr></table>
</body></html>`;

type MailForm = {
  siteName: string;
  enabled: boolean;
  smtpHost: string;
  smtpPort: string;
  securityMode: MailSecurityMode;
  smtpUsername: string;
  smtpPassword: string;
  clearSmtpPassword: boolean;
  fromEmail: string;
  fromName: string;
  connectionTimeoutSeconds: string;
  verificationTtlMinutes: string;
  resendIntervalSeconds: string;
  sessionTtlDays: string;
  verificationSubject: string;
  verificationTemplateHtml: string;
};

const capability = ref<MailConfigCapability | null>(null);
const loadedConfig = ref<MailConfig | null>(null);
const loading = ref(true);
const saving = ref(false);
const testing = ref(false);
const sending = ref(false);
const error = ref("");
const savedMessage = ref("");
const testMessage = ref("");
const testTone = ref<"neutral" | "success" | "warning" | "danger">("neutral");
const mailMessage = ref("");
const mailTone = ref<"neutral" | "success" | "warning" | "danger">("neutral");
const testRecipient = ref("");
const formInitialized = ref(false);
const loadedFormSignature = ref("");
let loadRequest = 0;

const form = reactive<MailForm>({
  siteName: "",
  enabled: false,
  smtpHost: "",
  smtpPort: "465",
  securityMode: "SSL",
  smtpUsername: "",
  smtpPassword: "",
  clearSmtpPassword: false,
  fromEmail: "",
  fromName: "",
  connectionTimeoutSeconds: "12",
  verificationTtlMinutes: "10",
  resendIntervalSeconds: "60",
  sessionTtlDays: "30",
  verificationSubject: "",
  verificationTemplateHtml: "",
});

const actorEmail = computed(() => auth.state.user?.email || "");
const isDirty = computed(() => formInitialized.value && (formSignature() !== loadedFormSignature.value || form.smtpPassword.length > 0 || form.clearSmtpPassword));
const previewSubject = computed(() => renderTemplate(form.verificationSubject || "邮件验证码", false));
const previewSrcdoc = computed(() => {
  const template = form.verificationTemplateHtml.trim();
  if (!template) return "<!doctype html><html lang=\"zh-CN\"><body style=\"padding:24px;font-family:sans-serif;color:#687073\">暂无模板预览</body></html>";
  return renderTemplate(template, true);
});

function formSignature() {
  return JSON.stringify({
    siteName: form.siteName,
    enabled: form.enabled,
    smtpHost: form.smtpHost,
    smtpPort: form.smtpPort,
    securityMode: form.securityMode,
    smtpUsername: form.smtpUsername,
    fromEmail: form.fromEmail,
    fromName: form.fromName,
    connectionTimeoutSeconds: form.connectionTimeoutSeconds,
    verificationTtlMinutes: form.verificationTtlMinutes,
    resendIntervalSeconds: form.resendIntervalSeconds,
    sessionTtlDays: form.sessionTtlDays,
    verificationSubject: form.verificationSubject,
    verificationTemplateHtml: form.verificationTemplateHtml,
  });
}

function fill(config?: MailConfig | null) {
  loadedConfig.value = config || null;
  form.siteName = config?.siteName || "";
  form.enabled = config?.enabled ?? false;
  form.smtpHost = config?.smtpHost || "";
  form.smtpPort = String(config?.smtpPort || 465);
  form.securityMode = config?.securityMode || "SSL";
  form.smtpUsername = config?.smtpUsername || "";
  form.smtpPassword = "";
  form.clearSmtpPassword = false;
  form.fromEmail = config?.fromEmail || "";
  form.fromName = config?.fromName || "";
  form.connectionTimeoutSeconds = String(config?.connectionTimeoutSeconds || 12);
  form.verificationTtlMinutes = String(config?.verificationTtlMinutes || 10);
  form.resendIntervalSeconds = String(config?.resendIntervalSeconds || 60);
  form.sessionTtlDays = String(config?.sessionTtlDays || 30);
  form.verificationSubject = config?.verificationSubject || "";
  form.verificationTemplateHtml = config?.verificationTemplateHtml || "";
  loadedFormSignature.value = formSignature();
  formInitialized.value = true;
  if (!testRecipient.value && actorEmail.value) testRecipient.value = actorEmail.value;
}

function renderTemplate(value: string, html: boolean) {
  const siteName = html ? escapeHtml(form.siteName || "数据结构智能体") : (form.siteName || "数据结构智能体");
  const code = "123456";
  const expires = html ? escapeHtml(form.verificationTtlMinutes || "10") : (form.verificationTtlMinutes || "10");
  const rendered = value
    .replaceAll("{{site_name}}", siteName)
    .replaceAll("{{code}}", code)
    .replaceAll("{{expires_minutes}}", expires);
  if (!html) return rendered;
  return stripUnsafeMarkup(rendered);
}

function escapeHtml(value: string) {
  return value.replace(/[&<>\"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" }[character] || character));
}

function stripUnsafeMarkup(value: string) {
  if (typeof DOMParser === "undefined") return value.replace(/<script[\s\S]*?<\/script>/gi, "");
  const document = new DOMParser().parseFromString(value, "text/html");
  document.querySelectorAll("script, iframe, object, embed, base, meta[http-equiv]").forEach((node) => node.remove());
  document.querySelectorAll("*").forEach((element) => {
    [...element.attributes].forEach((attribute) => {
      if (attribute.name.toLowerCase().startsWith("on") || attribute.name.toLowerCase() === "srcdoc") element.removeAttribute(attribute.name);
      if (["href", "src", "xlink:href"].includes(attribute.name.toLowerCase()) && /^\s*(javascript|vbscript):/i.test(attribute.value)) element.removeAttribute(attribute.name);
    });
  });
  return `<!doctype html>${document.documentElement.outerHTML}`;
}

function warnBeforeUnload(event: BeforeUnloadEvent | Event) {
  if (!isDirty.value) return;
  event.preventDefault();
  (event as BeforeUnloadEvent).returnValue = "";
}

function reasonText(reason?: string | null) {
  const labels: Record<string, string> = {
    MASTER_KEY_UNAVAILABLE: "服务端未配置邮件配置加密主密钥，保存功能暂不可用。",
    MAIL_CONFIG_UNAVAILABLE: "邮件配置服务暂不可用，请检查部署环境后重试。",
  };
  return reason ? labels[reason] || `服务状态：${reason}` : "";
}

function numberValue(value: string, label: string, min: number, max: number): number {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) throw new Error(`${label}必须在 ${min} 到 ${max} 之间`);
  return parsed;
}

function makePayload(): UpdateMailConfigRequest {
  const payload: UpdateMailConfigRequest = {
    siteName: form.siteName.trim(),
    enabled: form.enabled,
    smtpHost: form.smtpHost.trim(),
    smtpPort: numberValue(form.smtpPort, "SMTP 端口", 1, 65535),
    securityMode: form.securityMode,
    smtpUsername: form.smtpUsername.trim(),
    clearSmtpPassword: form.clearSmtpPassword,
    fromEmail: form.fromEmail.trim(),
    fromName: form.fromName.trim(),
    connectionTimeoutSeconds: numberValue(form.connectionTimeoutSeconds, "连接超时", 1, 30),
    verificationTtlMinutes: numberValue(form.verificationTtlMinutes, "验证码有效期", 1, 60),
    resendIntervalSeconds: numberValue(form.resendIntervalSeconds, "重发间隔", 5, 3600),
    sessionTtlDays: numberValue(form.sessionTtlDays, "登录会话周期", 1, 90),
    verificationSubject: form.verificationSubject.trim(),
    verificationTemplateHtml: form.verificationTemplateHtml,
  };
  if (form.smtpPassword) payload.smtpPassword = form.smtpPassword;
  return payload;
}

function validateForm(): UpdateMailConfigRequest | null {
  try {
    if (!form.siteName.trim() || !form.fromName.trim() || !form.verificationSubject.trim() || !form.verificationTemplateHtml.trim()) {
      throw new Error("站点名称、发件人名称、邮件主题和 HTML 模板不能为空");
    }
    if (form.clearSmtpPassword && form.smtpPassword) throw new Error("清除密码时不能同时填写新密码");
    if (form.enabled && (!form.smtpHost.trim() || !form.fromEmail.trim())) throw new Error("启用真实邮件发送时必须填写 SMTP 主机和发件人邮箱");
    return makePayload();
  } catch (failure) {
    error.value = `保存未完成（VALIDATION_ERROR）。${failure instanceof Error ? failure.message : "请检查输入"}`;
    return null;
  }
}

function reload() {
  if (loading.value || saving.value || testing.value || sending.value) return;
  if (isDirty.value && !window.confirm("邮件配置还有未保存的更改，确定重新读取并丢弃这些更改吗？")) return;
  void load();
}

function currentIdentityMatchesLoadedConfig() {
  const config = loadedConfig.value;
  return Boolean(config
    && form.smtpHost.trim() === config.smtpHost
    && Number(form.smtpPort) === config.smtpPort
    && form.securityMode === config.securityMode
    && form.smtpUsername.trim() === config.smtpUsername);
}

function canTestCurrentConnection(): boolean {
  if (!form.smtpUsername.trim() || form.smtpPassword || form.clearSmtpPassword) return true;
  return Boolean(loadedConfig.value?.smtpPasswordConfigured && currentIdentityMatchesLoadedConfig());
}

async function load(): Promise<boolean> {
  const request = ++loadRequest;
  loading.value = true;
  error.value = "";
  savedMessage.value = "";
  testMessage.value = "";
  mailMessage.value = "";
  try {
    const nextCapability = await adminApi.getMailConfig();
    if (request !== loadRequest) return false;
    capability.value = nextCapability;
    fill(nextCapability.configuration);
    return true;
  } catch (failure) {
    if (request === loadRequest) error.value = adminErrorMessage(failure, "读取邮件配置");
    return false;
  } finally {
    if (request === loadRequest) loading.value = false;
  }
}

async function save() {
  if (saving.value || testing.value || sending.value) return;
  error.value = "";
  savedMessage.value = "";
  testMessage.value = "";
  mailMessage.value = "";
  const payload = validateForm();
  if (!payload) return;
  saving.value = true;
  try {
    const updated = await adminApi.updateMailConfig(payload);
    fill(updated);
    savedMessage.value = "SMTP 设置已保存。密码输入仅用于本次请求，页面不会回填。";
  } catch (failure) {
    error.value = adminErrorMessage(failure, "保存邮件配置");
  } finally {
    saving.value = false;
  }
}

async function testConnection() {
  if (saving.value || testing.value || sending.value) return;
  error.value = "";
  testMessage.value = "";
  const payload = validateForm();
  if (!payload) return;
  if (!canTestCurrentConnection()) {
    testMessage.value = "当前 SMTP 连接身份已改变且没有输入密码，无法使用未保存设置测试连接。请填写对应密码或先保存设置。";
    testTone.value = "warning";
    return;
  }
  testing.value = true;
  try {
    const result = await adminApi.testMailConnection(payload);
    testMessage.value = result.connected ? `连接测试返回 ${result.code}。` : `连接测试返回 ${result.code}，请检查 SMTP 参数。`;
    testTone.value = result.connected ? "success" : "warning";
  } catch (failure) {
    testMessage.value = adminErrorMessage(failure, "测试邮件连接");
    testTone.value = "danger";
  } finally {
    testing.value = false;
  }
}

async function sendTestMail() {
  if (saving.value || testing.value || sending.value) return;
  error.value = "";
  mailMessage.value = "";
  const payload = validateForm();
  if (!payload) return;
  if (!canTestCurrentConnection()) {
    mailMessage.value = "当前 SMTP 连接身份已改变且没有输入密码，无法发送测试邮件。请填写对应密码或先保存设置。";
    mailTone.value = "warning";
    return;
  }
  const recipient = (testRecipient.value || actorEmail.value).trim().toLowerCase();
  if (!recipient) {
    mailMessage.value = "发送测试邮件前需要当前管理员邮箱。";
    mailTone.value = "warning";
    return;
  }
  sending.value = true;
  try {
    const result = await adminApi.sendTestMail(payload, recipient);
    mailMessage.value = result.sent ? `测试邮件已发送到 ${recipient}。` : `测试邮件未发送（${result.code}）。`;
    mailTone.value = result.sent ? "success" : "warning";
  } catch (failure) {
    mailMessage.value = adminErrorMessage(failure, "发送测试邮件");
    mailTone.value = "danger";
  } finally {
    sending.value = false;
  }
}

onBeforeRouteLeave(() => !isDirty.value || window.confirm("邮件配置还有未保存的更改，确定离开此页面吗？"));
onMounted(() => {
  window.addEventListener("beforeunload", warnBeforeUnload);
  if (actorEmail.value) testRecipient.value = actorEmail.value;
  void load();
});
onBeforeUnmount(() => window.removeEventListener("beforeunload", warnBeforeUnload));
</script>

<template>
  <AdminPageFrame title="邮件设置" description="配置发件账户、验证码和邮件模板。">
    <template #actions>
      <StatusBadge :label="loadedConfig?.smtpPasswordConfigured ? '密码已配置' : '密码未配置'" :tone="loadedConfig?.smtpPasswordConfigured ? 'success' : 'neutral'" />
      <button class="button button--small" type="button" :disabled="loading || saving || testing || sending" @click="reload">重新读取</button>
    </template>

    <LoadingState v-if="loading" label="正在读取邮件配置…" />
    <ErrorState v-else-if="error && !capability" title="邮件配置不可读取" :message="error"><RetryButton @retry="load" /></ErrorState>
    <template v-else>
      <InlineNotice v-if="capability?.reason" :message="reasonText(capability.reason)" :tone="capability.reason === 'MASTER_KEY_UNAVAILABLE' || capability.reason === 'MAIL_CONFIG_UNAVAILABLE' ? 'danger' : 'neutral'" />
      <InlineNotice v-if="isDirty" message="当前表单有未保存的更改。重新读取会丢弃这些更改；测试操作会使用当前表单设置。" tone="warning" />
      <InlineNotice v-if="error" :message="error" tone="danger" />
      <InlineNotice v-if="savedMessage" :message="savedMessage" tone="success" />
      <InlineNotice v-if="testMessage" :message="testMessage" :tone="testTone" />
      <InlineNotice v-if="mailMessage" :message="mailMessage" :tone="mailTone" />

      <form class="mail-config mail-operations" @submit.prevent="save">
        <section class="mail-card mail-card--delivery">
          <header class="mail-card__header">
            <div><h2>发送账户</h2><p>用于验证码和测试邮件投递。</p></div>
            <label class="mail-toggle">
              <input v-model="form.enabled" type="checkbox" />
              <span class="mail-toggle__control" aria-hidden="true"><span></span></span>
              <span>启用真实邮件发送</span>
            </label>
          </header>
          <div class="mail-grid mail-grid--two">
            <label class="admin-field"><span>站点名称</span><input v-model="form.siteName" autocomplete="organization" maxlength="128" required /></label>
            <label class="admin-field"><span>SMTP 主机</span><input v-model="form.smtpHost" autocomplete="url" maxlength="253" placeholder="smtp.example.com" /></label>
            <label class="admin-field"><span>SMTP 端口</span><input v-model="form.smtpPort" type="number" min="1" max="65535" step="1" required /></label>
            <label class="admin-field"><span>安全模式</span><select v-model="form.securityMode"><option value="SSL">SSL</option><option value="STARTTLS">STARTTLS</option><option value="NONE">NONE</option></select></label>
            <label class="admin-field"><span>SMTP 用户名</span><input v-model="form.smtpUsername" autocomplete="username" maxlength="320" /></label>
            <label class="admin-field"><span>SMTP 密码</span><input v-model="form.smtpPassword" name="smtp-password" type="password" autocomplete="new-password" maxlength="4096" placeholder="留空表示保留当前值" /><small v-if="loadedConfig?.smtpPasswordConfigured">已配置，留空不会覆盖。</small></label>
            <label class="admin-field"><span>发件人邮箱</span><input v-model="form.fromEmail" type="email" autocomplete="email" maxlength="254" placeholder="no-reply@example.com" /></label>
            <label class="admin-field"><span>发件人名称</span><input v-model="form.fromName" autocomplete="organization" maxlength="128" required /></label>
            <label class="admin-field"><span>连接超时（秒）</span><input v-model="form.connectionTimeoutSeconds" type="number" min="1" max="30" step="1" required /></label>
            <label class="admin-check mail-clear-password"><input v-model="form.clearSmtpPassword" name="clear-smtp-password" type="checkbox" :disabled="Boolean(form.smtpPassword)" /><span>清除当前 SMTP 密码</span></label>
          </div>
          <div class="mail-card__actions">
            <span v-if="loadedConfig?.lastConnectionTestStatus" class="mail-last-test">最近测试：{{ loadedConfig.lastConnectionTestStatus }} · {{ formatDate(loadedConfig.lastConnectionTestedAt) }}</span>
            <div class="mail-card__action-buttons">
              <LiquidMetalButton variant="quiet" :disabled="saving || testing || sending" @click="testConnection">{{ testing ? "测试中…" : "测试连接" }}</LiquidMetalButton>
              <LiquidMetalButton type="submit" :disabled="saving || testing || sending || capability?.reason === 'MASTER_KEY_UNAVAILABLE'">{{ saving ? "保存中…" : "保存设置" }}</LiquidMetalButton>
            </div>
          </div>
        </section>

        <section class="mail-card mail-card--policy">
          <header class="mail-card__header"><div><h2>验证码策略</h2><p>控制验证与会话有效期。</p></div></header>
          <div class="mail-grid mail-grid--policy">
            <label class="admin-field"><span>验证码有效期（分钟）</span><input v-model="form.verificationTtlMinutes" type="number" min="1" max="60" step="1" required /></label>
            <label class="admin-field"><span>再次发送间隔（秒）</span><input v-model="form.resendIntervalSeconds" type="number" min="5" max="3600" step="1" required /></label>
            <label class="admin-field"><span>登录会话周期（天）</span><input v-model="form.sessionTtlDays" type="number" min="1" max="90" step="1" required /></label>
          </div>
        </section>

        <section class="mail-card mail-card--template">
          <header class="mail-card__header"><div><h2>验证码模板</h2><p>支持 <code v-pre>{{site_name}}</code>、<code v-pre>{{code}}</code>、<code v-pre>{{expires_minutes}}</code>。</p></div></header>
          <div class="mail-template-grid">
            <div class="mail-template-editor">
              <label class="admin-field"><span>邮件主题</span><input v-model="form.verificationSubject" maxlength="300" required /></label>
              <label class="admin-field"><span>HTML 模板</span><textarea v-model="form.verificationTemplateHtml" class="mail-template-textarea" spellcheck="false" maxlength="100000" required /></label>
            </div>
            <div class="mail-preview" aria-label="验证码邮件预览">
              <div class="mail-preview__subject"><span>主题预览</span><strong>{{ previewSubject }}</strong></div>
              <iframe title="验证码邮件预览" class="mail-preview__frame" sandbox="" :srcdoc="previewSrcdoc" />
            </div>
          </div>
        </section>

        <section class="mail-card mail-card--test">
          <header class="mail-card__header"><div><h2>测试投递</h2><p>使用当前设置发送到当前管理员邮箱。</p></div></header>
          <div class="mail-test-row">
            <label class="admin-field"><span>测试收件人</span><input v-model="testRecipient" type="email" :readonly="Boolean(actorEmail)" :placeholder="actorEmail || '当前管理员邮箱'" required /></label>
            <LiquidMetalButton :disabled="saving || testing || sending || !testRecipient" @click="sendTestMail">{{ sending ? "发送中…" : "发送测试邮件" }}</LiquidMetalButton>
          </div>
        </section>
      </form>
    </template>
  </AdminPageFrame>
</template>

<style scoped>
:deep(.admin-page[data-admin-view="邮件设置"]) { animation: mail-page-in 200ms cubic-bezier(0.16, 1, 0.3, 1) both; }

:deep(.admin-page[data-admin-view="邮件设置"] .admin-page__header) {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  padding: 18px 20px;
  border: 1px solid rgba(35, 69, 76, 0.16);
  border-top-color: rgba(255, 255, 255, 0.96);
  border-right-color: rgba(226, 102, 151, 0.28);
  border-bottom-color: rgba(65, 173, 132, 0.26);
  border-left-color: rgba(57, 193, 221, 0.32);
  border-radius: 8px;
  background: linear-gradient(118deg, rgba(255, 255, 255, 0.82), rgba(249, 253, 252, 0.58) 48%, rgba(229, 244, 240, 0.48));
  box-shadow: inset 1px 0 0 rgba(57, 193, 221, 0.2), inset -1px 0 0 rgba(226, 102, 151, 0.17), inset 0 1px 0 rgba(255, 255, 255, 0.98), 0 10px 26px rgba(32, 61, 65, 0.06);
  -webkit-backdrop-filter: blur(18px) saturate(1.18);
  backdrop-filter: blur(18px) saturate(1.18);
}

:deep(.admin-page[data-admin-view="邮件设置"] .admin-page__header::before),
:deep(.admin-page[data-admin-view="邮件设置"] .admin-page__header::after) {
  position: absolute;
  z-index: 0;
  content: "";
  pointer-events: none;
}

:deep(.admin-page[data-admin-view="邮件设置"] .admin-page__header::before) {
  inset: 0;
  background: linear-gradient(110deg, rgba(255, 255, 255, 0.54), transparent 42%, rgba(255, 255, 255, 0.24));
}

:deep(.admin-page[data-admin-view="邮件设置"] .admin-page__header::after) {
  inset: 0;
  padding: 1px;
  border-radius: inherit;
  background: linear-gradient(118deg, rgba(57, 193, 221, 0.62), rgba(255, 255, 255, 0.82) 30%, rgba(255, 255, 255, 0.12) 58%, rgba(226, 102, 151, 0.56));
  opacity: 0.58;
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
  mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  mask-composite: exclude;
}

:deep(.admin-page[data-admin-view="邮件设置"] .admin-page__header > *) { position: relative; z-index: 1; }

.mail-operations {
  --mail-ink: #183036;
  --mail-muted: #5d7076;
  --mail-line: rgba(35, 69, 76, 0.16);
  --mail-line-strong: rgba(29, 83, 91, 0.31);
  --mail-cyan: rgba(57, 193, 221, 0.7);
  --mail-rose: rgba(226, 102, 151, 0.62);
  --mail-mint: rgba(65, 173, 132, 0.5);
  --mail-ease: cubic-bezier(0.16, 1, 0.3, 1);
  display: grid;
  gap: 14px;
}

.mail-operations .mail-card {
  position: relative;
  isolation: isolate;
  display: grid;
  min-width: 0;
  gap: 18px;
  overflow: hidden;
  padding: 22px;
  border: 1px solid var(--mail-line);
  border-top-color: rgba(255, 255, 255, 0.96);
  border-right-color: rgba(226, 102, 151, 0.3);
  border-bottom-color: rgba(65, 173, 132, 0.28);
  border-left-color: rgba(57, 193, 221, 0.34);
  border-radius: 8px;
  background:
    linear-gradient(118deg, rgba(255, 255, 255, 0.82), rgba(249, 253, 252, 0.6) 48%, rgba(229, 244, 240, 0.5)),
    rgba(255, 255, 255, 0.64);
  box-shadow:
    inset 1px 0 0 rgba(57, 193, 221, 0.24),
    inset -1px 0 0 rgba(226, 102, 151, 0.2),
    inset 0 1px 0 rgba(255, 255, 255, 0.96),
    0 10px 26px rgba(32, 61, 65, 0.07);
  -webkit-backdrop-filter: blur(18px) saturate(1.22);
  backdrop-filter: blur(18px) saturate(1.22);
  animation: mail-surface-in 200ms var(--mail-ease) both;
}

.mail-operations .mail-card::before,
.mail-operations .mail-card::after {
  position: absolute;
  z-index: 0;
  content: "";
  pointer-events: none;
}

.mail-operations .mail-card::before {
  inset: 0;
  background: linear-gradient(112deg, rgba(255, 255, 255, 0.56), transparent 34%, rgba(255, 255, 255, 0.18) 62%, rgba(255, 255, 255, 0.44));
  opacity: 0.72;
}

.mail-operations .mail-card::after {
  inset: 0;
  padding: 1px;
  border-radius: inherit;
  background: linear-gradient(118deg, var(--mail-cyan), rgba(255, 255, 255, 0.86) 29%, rgba(255, 255, 255, 0.12) 58%, var(--mail-rose));
  opacity: 0.62;
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
  mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  mask-composite: exclude;
}

.mail-operations .mail-card > * {
  position: relative;
  z-index: 1;
}

.mail-operations .mail-card--policy { animation-delay: 32ms; }
.mail-operations .mail-card--template { animation-delay: 64ms; }
.mail-operations .mail-card--test { animation-delay: 96ms; }

.mail-operations .mail-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding-bottom: 15px;
  border-bottom: 1px solid rgba(35, 69, 76, 0.12);
}

.mail-operations .mail-card__header h2 {
  margin: 0;
  color: var(--mail-ink);
  font-family: inherit;
  font-size: 20px;
  font-weight: 700;
  font-optical-sizing: auto;
  letter-spacing: 0;
  line-height: 1.25;
}

.mail-operations .mail-card__header p {
  max-width: 680px;
  margin: 6px 0 0;
  color: var(--mail-muted);
  font-size: 13px;
  line-height: 1.6;
}

.mail-operations .mail-grid {
  display: grid;
  gap: 14px;
  margin: 0;
}

.mail-operations .mail-grid--two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.mail-operations .mail-grid--policy { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.mail-operations .mail-grid--two .mail-clear-password { align-self: end; }

.mail-operations .admin-field {
  gap: 7px;
  color: var(--mail-muted);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.4;
}

.mail-operations .admin-field :is(input, select, textarea) {
  min-height: 42px;
  border-color: rgba(35, 69, 76, 0.19);
  border-top-color: rgba(255, 255, 255, 0.94);
  border-right-color: rgba(226, 102, 151, 0.2);
  border-bottom-color: rgba(65, 173, 132, 0.2);
  border-left-color: rgba(57, 193, 221, 0.24);
  border-radius: 7px;
  background:
    linear-gradient(110deg, rgba(255, 255, 255, 0.78), rgba(246, 252, 250, 0.52)),
    rgba(255, 255, 255, 0.52);
  box-shadow:
    inset 1px 0 0 rgba(57, 193, 221, 0.12),
    inset -1px 0 0 rgba(226, 102, 151, 0.11),
    inset 0 1px 0 rgba(255, 255, 255, 0.88);
  color: var(--mail-ink);
  transition: border-color 160ms ease, box-shadow 160ms ease, background-color 160ms ease, transform 160ms var(--mail-ease);
}

.mail-operations .admin-field :is(input, select, textarea):hover { border-color: var(--mail-line-strong); }

.mail-operations .admin-field :is(input, select, textarea):focus {
  border-color: rgba(20, 117, 132, 0.72);
  box-shadow: 0 0 0 3px rgba(48, 169, 191, 0.16), inset 1px 0 0 rgba(57, 193, 221, 0.22), inset -1px 0 0 rgba(226, 102, 151, 0.18);
  transform: translateY(-1px);
}

.mail-operations .admin-field :is(input, select, textarea)[readonly] {
  background: rgba(239, 245, 244, 0.8);
  color: #66777b;
}

.mail-operations .admin-field small {
  color: var(--mail-muted);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.5;
}

.mail-operations .mail-toggle {
  display: inline-flex;
  align-items: center;
  align-self: start;
  min-height: 38px;
  gap: 9px;
  color: var(--mail-ink);
  cursor: pointer;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.4;
  white-space: nowrap;
}

.mail-operations .mail-toggle input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  clip-path: inset(50%);
  white-space: nowrap;
}

.mail-operations .mail-toggle__control {
  position: relative;
  display: inline-flex;
  align-items: center;
  width: 38px;
  height: 22px;
  flex: 0 0 auto;
  padding: 2px;
  border: 1px solid rgba(35, 69, 76, 0.25);
  border-radius: 999px;
  background: rgba(232, 239, 238, 0.88);
  box-shadow: inset 1px 0 0 rgba(57, 193, 221, 0.15), inset -1px 0 0 rgba(226, 102, 151, 0.1), inset 0 1px 0 rgba(255, 255, 255, 0.88);
  transition: background-color 180ms ease, border-color 180ms ease, box-shadow 180ms ease;
}

.mail-operations .mail-toggle__control > span {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(23, 48, 54, 0.2), inset 1px 0 0 rgba(57, 193, 221, 0.2), inset -1px 0 0 rgba(226, 102, 151, 0.16);
  transform: translateX(0);
  transition: transform 180ms var(--mail-ease), box-shadow 180ms ease;
}

.mail-operations .mail-toggle input:checked + .mail-toggle__control {
  border-color: rgba(10, 112, 99, 0.66);
  background: linear-gradient(120deg, #168b84, #0c6963);
  box-shadow: inset 1px 0 0 rgba(79, 232, 242, 0.64), inset -1px 0 0 rgba(255, 145, 192, 0.56), inset 0 1px 0 rgba(218, 255, 251, 0.46);
}

.mail-operations .mail-toggle input:checked + .mail-toggle__control > span {
  transform: translateX(16px);
  box-shadow: 0 1px 4px rgba(2, 62, 58, 0.28), inset 1px 0 0 rgba(255, 255, 255, 0.82);
}

.mail-operations .mail-toggle input:focus-visible + .mail-toggle__control { box-shadow: 0 0 0 3px rgba(48, 169, 191, 0.22); }

.mail-operations .mail-clear-password {
  display: inline-flex;
  align-items: center;
  min-height: 42px;
  gap: 8px;
  color: var(--mail-muted);
  font-size: 13px;
}

.mail-operations .mail-clear-password input {
  width: 17px;
  height: 17px;
  margin: 0;
  accent-color: #0d746d;
}

.mail-operations .mail-card__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0;
  padding-top: 15px;
  border-top: 1px solid rgba(35, 69, 76, 0.12);
}

.mail-operations .mail-card__action-buttons {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.mail-operations .mail-last-test {
  min-width: 0;
  color: var(--mail-muted);
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.mail-operations .button {
  border-top-color: rgba(255, 255, 255, 0.9);
  border-right-color: rgba(226, 102, 151, 0.48);
  border-bottom-color: rgba(65, 173, 132, 0.42);
  border-left-color: rgba(57, 193, 221, 0.58);
  --liquid-rim-cyan: rgba(57, 193, 221, 0.72);
  --liquid-rim-pink: rgba(226, 102, 151, 0.68);
  --liquid-rim-mint: rgba(65, 173, 132, 0.48);
  --liquid-rim-gold: rgba(232, 179, 85, 0.42);
  transition: transform 160ms var(--mail-ease), border-color 160ms ease, box-shadow 160ms ease, background 160ms ease;
}

.mail-operations .button::after { opacity: 0.86; }

.mail-operations .button--primary {
  border-top-color: rgba(213, 255, 250, 0.76);
  border-right-color: rgba(255, 145, 192, 0.78);
  border-bottom-color: rgba(2, 70, 64, 0.92);
  border-left-color: rgba(79, 232, 242, 0.88);
}

.mail-operations .mail-template-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.02fr) minmax(340px, 0.98fr);
  gap: 20px;
  min-width: 0;
}

.mail-operations .mail-template-editor { display: grid; min-width: 0; gap: 14px; }

.mail-operations .mail-template-textarea {
  min-height: 348px;
  font-family: var(--admin-ui, system-ui, sans-serif);
  font-size: 13px;
  line-height: 1.6;
}

.mail-operations .mail-preview {
  display: grid;
  align-content: start;
  min-width: 0;
  gap: 10px;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.mail-operations .mail-preview__subject {
  display: grid;
  min-width: 0;
  gap: 3px;
  padding: 0 2px 2px;
}

.mail-operations .mail-preview__subject span {
  color: var(--mail-muted);
  font-size: 12px;
  line-height: 1.45;
}

.mail-operations .mail-preview__subject strong {
  color: var(--mail-ink);
  font-size: 14px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.mail-operations .mail-preview__frame {
  display: block;
  width: 100%;
  min-height: 360px;
  box-sizing: border-box;
  border: 1px solid rgba(35, 69, 76, 0.18);
  border-top-color: rgba(255, 255, 255, 0.94);
  border-right-color: rgba(226, 102, 151, 0.24);
  border-bottom-color: rgba(65, 173, 132, 0.24);
  border-left-color: rgba(57, 193, 221, 0.28);
  border-radius: 7px;
  background: #ffffff;
  box-shadow: inset 1px 0 0 rgba(57, 193, 221, 0.1), inset -1px 0 0 rgba(226, 102, 151, 0.1), 0 8px 20px rgba(32, 61, 65, 0.05);
}

.mail-operations .mail-test-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 12px;
}

.mail-operations .mail-test-row .button { min-height: 42px; }
.mail-operations :deep(.liquid-metal-button) { min-height: 42px; }

.mail-operations code {
  padding: 1px 5px;
  border: 1px solid rgba(35, 69, 76, 0.15);
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.58);
  color: var(--mail-ink);
  font-family: var(--admin-ui, system-ui, sans-serif);
  font-size: 0.92em;
  overflow-wrap: anywhere;
}

@keyframes mail-surface-in {
  from { opacity: 0; transform: translateY(8px) scale(0.992); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

@keyframes mail-page-in {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (hover: hover) and (pointer: fine) {
  .mail-operations .mail-card {
    transition: border-color 180ms ease, box-shadow 180ms ease, transform 180ms var(--mail-ease);
    will-change: transform;
  }

  .mail-operations .mail-card:hover {
    border-color: rgba(23, 108, 115, 0.28);
    box-shadow: inset 1px 0 0 rgba(57, 193, 221, 0.32), inset -1px 0 0 rgba(226, 102, 151, 0.27), inset 0 1px 0 rgba(255, 255, 255, 0.98), 0 14px 28px rgba(32, 61, 65, 0.09);
    transform: translateY(-1px);
  }

  .mail-operations .button:not(:disabled):hover { transform: translateY(-1px); }
}

@media (max-width: 980px) {
  .mail-operations .mail-template-grid { grid-template-columns: 1fr; }
  .mail-operations .mail-preview__frame { min-height: 320px; }
}

@media (max-width: 720px) {
  .mail-operations .mail-card { padding: 18px; }
  .mail-operations .mail-card__header { flex-direction: column; gap: 12px; }
  .mail-operations .mail-grid--two,
  .mail-operations .mail-grid--policy,
  .mail-operations .mail-test-row { grid-template-columns: 1fr; }
  .mail-operations .mail-card__actions { align-items: stretch; flex-direction: column; }
  .mail-operations .mail-card__action-buttons { justify-content: stretch; }
  .mail-operations .mail-card__action-buttons .button,
  .mail-operations .mail-test-row .button,
  .mail-operations :deep(.liquid-metal-button) { width: 100%; }
}

@media (prefers-reduced-motion: reduce) {
  :deep(.admin-page[data-admin-view="邮件设置"]) { animation: none; }

  .mail-operations .mail-card,
  .mail-operations .mail-card--policy,
  .mail-operations .mail-card--template,
  .mail-operations .mail-card--test {
    animation: none;
    transition: none;
    transform: none;
  }

  .mail-operations .mail-toggle__control,
  .mail-operations .mail-toggle__control > span,
  .mail-operations .admin-field :is(input, select, textarea),
  .mail-operations .button { transition: none; }
}

@media (prefers-reduced-transparency: reduce) {
  :deep(.admin-page[data-admin-view="邮件设置"] .admin-page__header),
  .mail-operations .mail-card,
  .mail-operations .admin-field :is(input, select, textarea) {
    background: #ffffff;
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }

  .mail-operations .mail-card::before,
  .mail-operations .mail-card::after,
  :deep(.admin-page[data-admin-view="邮件设置"] .admin-page__header::before),
  :deep(.admin-page[data-admin-view="邮件设置"] .admin-page__header::after) { opacity: 0; }
}

@media (prefers-contrast: more) {
  .mail-operations .mail-card,
  .mail-operations .admin-field :is(input, select, textarea),
  .mail-operations .mail-preview__frame { border-color: #29444a; }
}
</style>
