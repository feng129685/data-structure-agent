<script setup lang="ts">
import { ref, useId } from "vue";
import { useModalLifecycle } from "./useModalLifecycle";

const props = withDefaults(defineProps<{ open: boolean; title?: string; closeOnBackdrop?: boolean }>(), {
  title: "侧栏",
  closeOnBackdrop: true,
});
const emit = defineEmits<{ close: [] }>();
const overlayRef = ref<HTMLElement | null>(null);
const dialogRef = ref<HTMLElement | null>(null);
const titleId = `${useId()}-title`;
const descriptionId = `${useId()}-description`;
const { onKeydown } = useModalLifecycle(() => props.open, overlayRef, dialogRef, () => emit("close"));
</script>

<template>
  <Teleport to="body">
    <div v-if="open" ref="overlayRef" class="drawer-layer" data-modal-layer @click.self="closeOnBackdrop && emit('close')">
      <aside
        ref="dialogRef"
        class="drawer"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        :aria-describedby="descriptionId"
        tabindex="-1"
        @keydown="onKeydown"
      >
        <header>
          <h2 :id="titleId">{{ title }}</h2>
          <button class="icon-button" data-dialog-initial-focus type="button" aria-label="关闭" @click="emit('close')">×</button>
        </header>
        <div :id="descriptionId" class="drawer__body"><slot /></div>
      </aside>
    </div>
  </Teleport>
</template>

<style scoped>
.icon-button { min-width: 44px; min-height: 44px; }
</style>
