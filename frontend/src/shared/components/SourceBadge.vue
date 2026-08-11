<script setup lang="ts">
withDefaults(defineProps<{ label: string; source?: string }>(), { source: "" });
</script>
<template><span class="badge badge--source" :title="source || label">{{ label }}</span></template>
