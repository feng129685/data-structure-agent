<script setup lang="ts">
withDefaults(defineProps<{ status?: "idle" | "saving" | "saved" | "error" }>(), { status: "idle" });
const labels = { idle: "未保存", saving: "保存中", saved: "已保存", error: "保存失败" };
</script>
<template><span class="save-state" :data-status="status" role="status">{{ labels[status] }}</span></template>
