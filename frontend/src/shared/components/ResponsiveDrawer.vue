<script setup lang="ts">
withDefaults(defineProps<{ open: boolean; title?: string }>(), { title: "侧栏" });
const emit = defineEmits<{ close: [] }>();
</script>
<template><div v-if="open" class="drawer-layer" @click.self="emit('close')"><aside class="drawer" role="dialog" aria-modal="true" :aria-label="title"><header><h2>{{ title }}</h2><button class="icon-button" type="button" aria-label="关闭" @click="emit('close')">×</button></header><div class="drawer__body"><slot /></div></aside></div></template>
