<script setup lang="ts">
withDefaults(defineProps<{ open: boolean; title?: string }>(), { title: "更多操作" });
const emit = defineEmits<{ close: [] }>();
</script>
<template><div v-if="open" class="sheet-layer" @click.self="emit('close')"><section class="bottom-sheet" role="dialog" aria-modal="true" :aria-label="title"><div class="sheet-handle" aria-hidden="true"></div><header><h2>{{ title }}</h2><button class="icon-button" type="button" aria-label="关闭" @click="emit('close')">×</button></header><slot /></section></div></template>
