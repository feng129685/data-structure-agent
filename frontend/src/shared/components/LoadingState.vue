<script setup lang="ts">
withDefaults(defineProps<{ label?: string }>(), { label: "正在加载" });
</script>
<template><div class="state state--loading" role="status" aria-live="polite"><span class="state__spinner" aria-hidden="true"></span><span>{{ label }}</span></div></template>
