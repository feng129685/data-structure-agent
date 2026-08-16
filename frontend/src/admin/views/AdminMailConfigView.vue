<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import AdminPageFrame from "../components/AdminPageFrame.vue";
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
  <AdminPageFrame title="邮件发送控制" description="配置验证码邮件、认证策略和模板预览。SMTP 密码只在保存或测试时使用，不会返回到浏览器。">
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

      <div class="signal-strip" aria-label="邮件配置状态">
        <div class="signal-strip__item"><span class="signal-strip__label">发送状态</span><strong>{{ form.enabled ? "已启用" : "已暂停" }}</strong></div>
        <div class="signal-strip__item"><span class="signal-strip__label">凭据状态</span><strong>{{ loadedConfig?.smtpPasswordConfigured ? "已加密" : "待配置" }}</strong></div>
        <div class="signal-strip__item"><span class="signal-strip__label">安全模式</span><strong>{{ form.securityMode }}</strong></div>
        <div class="signal-strip__item"><span class="signal-strip__label">最近测试</span><strong>{{ loadedConfig?.lastConnectionTestStatus || "未测试" }}</strong></div>
      </div>

      <form class="mail-config" @submit.prevent="save">
        <section class="mail-card mail-card--delivery panel-enter">
          <header class="mail-card__header">
            <div><h2>SMTP 投递</h2><p>支持密码保留或清除、连接测试，以及使用当前表单设置的测试。</p></div>
            <label class="mail-switch"><input v-model="form.enabled" type="checkbox" /><span>启用真实邮件发送</span></label>
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
            <button class="button button--primary" type="submit" :disabled="saving || testing || sending || capability?.reason === 'MASTER_KEY_UNAVAILABLE'">{{ saving ? "保存中…" : "保存 SMTP 设置" }}</button>
            <button class="button" type="button" :disabled="saving || testing || sending" @click="testConnection">{{ testing ? "测试中…" : "测试连接" }}</button>
            <span v-if="loadedConfig?.lastConnectionTestStatus" class="admin-muted">最近测试：{{ loadedConfig.lastConnectionTestStatus }} · {{ formatDate(loadedConfig.lastConnectionTestedAt) }}</span>
          </div>
        </section>

        <section class="mail-card mail-card--policy panel-enter" style="--panel-delay: 70ms">
          <header class="mail-card__header"><div><h2>验证码与会话</h2><p>调整验证码有效期、再次发送间隔和用户登录会话周期。</p></div></header>
          <div class="mail-grid">
            <label class="admin-field"><span>验证码有效期（分钟）</span><input v-model="form.verificationTtlMinutes" type="number" min="1" max="60" step="1" required /></label>
            <label class="admin-field"><span>再次发送间隔（秒）</span><input v-model="form.resendIntervalSeconds" type="number" min="5" max="3600" step="1" required /></label>
            <label class="admin-field"><span>登录会话周期（天）</span><input v-model="form.sessionTtlDays" type="number" min="1" max="90" step="1" required /></label>
          </div>
        </section>

        <section class="mail-card mail-card--template panel-enter" style="--panel-delay: 140ms">
          <header class="mail-card__header"><div><h2>验证码邮件模板</h2><p>可使用 <code v-pre>{{site_name}}</code>、<code v-pre>{{code}}</code>、<code v-pre>{{expires_minutes}}</code>。</p></div></header>
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

        <section class="mail-card mail-card--test panel-enter" style="--panel-delay: 210ms">
          <header class="mail-card__header"><div><h2>发送测试邮件</h2><p>使用当前表单中的 SMTP 和模板设置发送，不要求先保存。</p></div></header>
          <div class="mail-test-row">
            <label class="admin-field"><span>测试收件人</span><input v-model="testRecipient" type="email" :readonly="Boolean(actorEmail)" :placeholder="actorEmail || '当前管理员邮箱'" required /></label>
            <button class="button button--primary" type="button" :disabled="saving || testing || sending || !testRecipient" @click="sendTestMail">{{ sending ? "发送中…" : "发送测试邮件" }}</button>
          </div>
          <p class="mail-test-note">测试邮件仅允许发送到当前管理员邮箱。</p>
        </section>
      </form>
    </template>
  </AdminPageFrame>
</template>

<style scoped>
.mail-config { display: grid; gap: 18px; }
.mail-card { padding: 24px; border: 1px solid var(--line); background: var(--surface); box-shadow: var(--shadow-sm); }
.mail-card__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding-bottom: 20px; border-bottom: 1px solid var(--line); }
.mail-card__header h2 { margin: 0; font-size: clamp(24px, 3vw, 34px); line-height: 1.15; }
.mail-card__header p:not(.mail-card__eyebrow) { max-width: 720px; margin: 10px 0 0; color: var(--text-muted); }
.mail-card__eyebrow { margin: 0 0 8px; color: var(--accent); font: 700 12px/1.4 var(--font-mono); letter-spacing: .04em; }
.mail-grid { display: grid; gap: 16px; margin-top: 22px; }
.mail-grid--two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.mail-grid--two .mail-clear-password { align-self: end; }
.admin-field small { color: var(--text-muted); font-size: 12px; }
.mail-switch { display: inline-flex; align-items: center; gap: 9px; min-height: 44px; padding: 0 12px; border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--surface-subtle); color: var(--text); font-weight: 600; white-space: nowrap; }
.mail-switch input, .mail-clear-password input { width: 18px; height: 18px; accent-color: var(--accent); }
.mail-card__actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 22px; padding-top: 18px; border-top: 1px solid var(--line); }
.mail-template-grid { display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(360px, .95fr); gap: 18px; margin-top: 22px; }
.mail-template-editor { display: grid; gap: 16px; }
.mail-template-textarea { min-height: 360px !important; font-family: var(--font-mono); font-size: 12px; line-height: 1.55; }
.mail-preview { display: grid; gap: 10px; min-width: 0; padding: 14px; border: 1px solid var(--line); background: var(--surface-subtle); }
.mail-preview__subject { display: grid; gap: 4px; padding: 0 4px 8px; }
.mail-preview__subject span { color: var(--text-muted); font-size: 12px; }
.mail-preview__subject strong { overflow-wrap: anywhere; }
.mail-preview__frame { width: 100%; min-height: 390px; border: 1px solid var(--line); background: var(--surface); }
.mail-test-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 12px; margin-top: 22px; }
.mail-test-row .button { min-height: 44px; }
.mail-test-note { margin: 12px 0 0; color: var(--text-muted); font-size: 13px; }
code { padding: 2px 5px; border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--surface-subtle); font-family: var(--font-mono); font-size: .9em; }

/* Responsive layout only. Theme colors are inherited from the admin shell. */
@media (max-width: 920px) {
  .mail-template-grid { grid-template-columns: 1fr; }
  .mail-preview__frame { min-height: 320px; }
}
@media (max-width: 680px) {
  .mail-card { padding: 18px; }
  .mail-card__header { display: grid; }
  .mail-grid--two, .mail-test-row { grid-template-columns: 1fr; }
  .mail-test-row .button { width: 100%; }
}
@media (prefers-reduced-transparency: reduce) { .mail-switch, .mail-preview { background: var(--surface); } }
@media (prefers-contrast: more) { .mail-card, .mail-switch, .mail-preview__frame { border-color: var(--text); } }
</style>
