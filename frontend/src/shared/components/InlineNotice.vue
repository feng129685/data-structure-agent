<script setup lang="ts">
withDefaults(defineProps<{ message: string; tone?: "neutral" | "success" | "warning" | "danger" }>(), { tone: "neutral" });
</script>
<template><p class="inline-notice" :data-tone="tone" role="status">{{ message }}</p></template>
