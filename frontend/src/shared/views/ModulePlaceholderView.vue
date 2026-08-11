<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import { auth } from "../../app/providers/runtime";
import StatusBadge from "../components/StatusBadge.vue";

const route = useRoute();
const moduleName = computed(() => String(route.meta.module || "共享工作台"));
</script>

<template>
  <section class="module-placeholder" aria-labelledby="module-title">
    <div class="module-placeholder__eyebrow">{{ route.meta.layout === "admin" ? "管理端" : "学习端" }}</div>
    <h1 id="module-title">{{ moduleName }}</h1>
    <p>共享应用基础已就绪，业务页面将在后续模块中接入冻结后的 Spring v1 接口。</p>
    <StatusBadge :label="auth.state.status === 'authenticated' ? '会话已恢复' : '访客模式'" :tone="auth.state.status === 'authenticated' ? 'success' : 'neutral'" />
  </section>
</template>
