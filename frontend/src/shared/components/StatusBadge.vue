<script setup lang="ts">
withDefaults(defineProps<{ label: string; tone?: "neutral" | "success" | "warning" | "danger" }>(), { tone: "neutral" });
</script>
<template><span class="badge" :data-tone="tone">{{ label }}</span></template>
