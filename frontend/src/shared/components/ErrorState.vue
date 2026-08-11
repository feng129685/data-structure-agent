<script setup lang="ts">
withDefaults(defineProps<{ title?: string; message?: string }>(), { title: "加载失败", message: "请求没有完成，请稍后重试。" });
</script>
<template><div class="state state--error" role="alert" aria-live="assertive"><h2>{{ title }}</h2><p>{{ message }}</p><slot /></div></template>
