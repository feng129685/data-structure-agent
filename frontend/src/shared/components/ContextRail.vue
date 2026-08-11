<script setup lang="ts">
withDefaults(defineProps<{ title?: string }>(), { title: "上下文" });
</script>
<template><aside class="context-rail" aria-label="上下文栏"><h2>{{ title }}</h2><slot /></aside></template>
