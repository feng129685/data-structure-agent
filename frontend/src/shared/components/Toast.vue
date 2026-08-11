<script setup lang="ts">
withDefaults(defineProps<{ open?: boolean; message?: string; tone?: "neutral" | "success" | "warning" | "danger" }>(), { open: false, message: "", tone: "neutral" });
const emit = defineEmits<{ dismiss: [] }>();
</script>
<template><div v-if="open" class="toast" :data-tone="tone" role="status" aria-live="polite"><span>{{ message }}</span><button type="button" aria-label="关闭提示" @click="emit('dismiss')">×</button></div></template>
