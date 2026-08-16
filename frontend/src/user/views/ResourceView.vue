<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import type { Resource } from "../../shared/types/contracts";
import UserFrame from "../components/UserFrame.vue";
import UserState from "../components/UserState.vue";
import { presentUserError, type UserErrorPresentation } from "../errors";
import { userApi } from "../runtime";

const route = useRoute();
const resourceId = computed(() => String(route.params.resourceId));
const resource = ref<Resource | null>(null);
const contentUrl = ref<string | null>(null);
const contentType = ref<string | null>(null);
const disposition = ref<string | null>(null);
const loading = ref(true);
const saving = ref(false);
const error = ref<UserErrorPresentation | null>(null);
const eventMessage = ref("");

const isPreviewable = computed(() => Boolean(contentUrl.value && (
  contentType.value?.includes("pdf") || contentType.value?.startsWith("image/")
)));
const downloadName = computed(() => {
  const match = disposition.value?.match(/filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i);
  const raw = match?.[1] || match?.[2];
  if (!raw) return resource.value?.title || "课程资料";
  try { return decodeURIComponent(raw); } catch { return raw; }
});

function releaseContentUrl() {
  if (contentUrl.value) URL.revokeObjectURL(contentUrl.value);
  contentUrl.value = null;
}

async function load() {
  loading.value = true;
  error.value = null;
  eventMessage.value = "";
  releaseContentUrl();
  try {
    const [metadata, payload] = await Promise.all([
      userApi.getResource(resourceId.value),
      userApi.getResourceContent(resourceId.value),
    ]);
    resource.value = metadata;
    contentType.value = payload.contentType;
    disposition.value = payload.disposition;
    contentUrl.value = URL.createObjectURL(new Blob([payload.bytes], { type: payload.contentType || "application/octet-stream" }));
    userApi.recordLearningEvent({ eventType: "RESOURCE_VIEW", chapterId: metadata.chapterId, referenceId: metadata.id })
      .then(() => { eventMessage.value = "已记录本次资料访问"; })
      .catch(() => { eventMessage.value = "资料已打开，但访问记录未保存"; });
  } catch (cause) {
    resource.value = null;
    error.value = presentUserError(cause);
  } finally {
    loading.value = false;
  }
}

async function download() {
  if (!contentUrl.value || !resource.value) return;
  saving.value = true;
  const link = document.createElement("a");
  link.href = contentUrl.value;
  link.download = downloadName.value;
  link.click();
  try {
    await userApi.recordLearningEvent({ eventType: "RESOURCE_DOWNLOAD", chapterId: resource.value.chapterId, referenceId: resource.value.id });
    eventMessage.value = "下载已开始，学习记录已保存";
  } catch {
    eventMessage.value = "下载已开始，但下载记录未保存";
  } finally {
    saving.value = false;
  }
}

onMounted(load);
onBeforeUnmount(releaseContentUrl);
</script>

<template>
  <UserFrame>
    <section class="user-page" aria-labelledby="resource-title">
      <header class="user-page__heading">
        <div><p class="user-page__eyebrow">课程资料</p><h1 id="resource-title">{{ resource?.title || "资源详情" }}</h1><p class="user-page__intro">{{ resource?.description || "读取受发布状态和账号范围控制的资源。" }}</p></div>
        <RouterLink class="user-action" :to="resource ? `/user/chapters/${resource.chapterId}` : '/user/chapters'">返回章节</RouterLink>
      </header>

      <UserState v-if="loading" mode="loading" title="正在加载课程资料" message="正在确认资源权限并读取内容。" />
      <UserState v-else-if="error" :mode="error.kind === 'permission' ? 'permission' : 'error'" :title="error.title" :message="error.message" :retry-label="error.retryable ? '重新加载' : undefined" @retry="load" />
      <template v-else-if="resource">
        <dl class="user-kv user-panel">
          <dt>资源类型</dt><dd>{{ resource.type }}</dd>
          <dt>来源</dt><dd>{{ resource.sourceName }}</dd>
          <dt>版本</dt><dd>{{ resource.versionLabel }}</dd>
          <dt>发布状态</dt><dd>{{ resource.reviewStatus }}</dd>
          <dt>访问范围</dt><dd>{{ resource.licenseScope }}</dd>
          <dt>内容类型</dt><dd>{{ contentType || "未提供" }}</dd>
        </dl>
        <div class="user-page__actions">
          <button class="user-action user-action--primary" type="button" :disabled="!contentUrl || saving" @click="download">{{ saving ? "正在记录…" : "下载资料" }}</button>
          <span v-if="eventMessage" class="user-list__meta" role="status">{{ eventMessage }}</span>
        </div>
        <iframe v-if="isPreviewable && contentType?.includes('pdf')" class="user-resource-frame" :src="contentUrl || undefined" :title="`${resource.title} 预览`"></iframe>
        <img v-else-if="isPreviewable" class="user-resource-image" :src="contentUrl || undefined" :alt="resource.title" />
        <UserState v-else mode="empty" title="此文件不支持浏览器内预览" message="内容已安全读取，可以使用下载按钮在本机打开。" />
      </template>
    </section>
    <template #rail><div class="user-rail-list"><strong>数据来源</strong><p>GET /resources/{resourceId}</p><p>GET /resources/{resourceId}/content</p><strong>隐私说明</strong><p>界面不显示服务器文件路径，只使用安全响应头中的文件名。</p></div></template>
  </UserFrame>
</template>
