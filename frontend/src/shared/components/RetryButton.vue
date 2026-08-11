<script setup lang="ts">
const props = defineProps<{ onRetry?: () => void; label?: string }>();
const emit = defineEmits<{ retry: [] }>();
function retry() { props.onRetry?.(); emit("retry"); }
</script>
<template><button class="button button--small" type="button" @click="retry">{{ label || "重试" }}</button></template>
