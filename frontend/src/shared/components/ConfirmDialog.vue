<script setup lang="ts">
withDefaults(defineProps<{ open: boolean; title?: string; message?: string; confirmLabel?: string; cancelLabel?: string }>(), { title: "确认操作", message: "确定继续吗？", confirmLabel: "确认", cancelLabel: "取消" });
const emit = defineEmits<{ confirm: []; cancel: [] }>();
</script>
<template>
  <div v-if="open" class="dialog-backdrop" role="presentation" @click.self="emit('cancel')">
    <section class="dialog" role="dialog" aria-modal="true" :aria-label="title">
      <h2>{{ title }}</h2><p>{{ message }}</p>
      <div class="dialog__actions"><button class="button" type="button" @click="emit('cancel')">{{ cancelLabel }}</button><button class="button button--primary" type="button" @click="emit('confirm')">{{ confirmLabel }}</button></div>
    </section>
  </div>
</template>
