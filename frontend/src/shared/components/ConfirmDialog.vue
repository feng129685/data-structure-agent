<script setup lang="ts">
import { ref, useId } from "vue";
import { useModalLifecycle } from "./useModalLifecycle";

const props = withDefaults(defineProps<{
  open: boolean;
  title?: string;
  message?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  closeOnBackdrop?: boolean;
}>(), {
  title: "确认操作",
  message: "确定继续吗？",
  confirmLabel: "确认",
  cancelLabel: "取消",
  closeOnBackdrop: false,
});
const emit = defineEmits<{ confirm: []; cancel: [] }>();
const overlayRef = ref<HTMLElement | null>(null);
const dialogRef = ref<HTMLElement | null>(null);
const titleId = `${useId()}-title`;
const descriptionId = `${useId()}-description`;
const { onKeydown } = useModalLifecycle(() => props.open, overlayRef, dialogRef, () => emit("cancel"));
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      ref="overlayRef"
      class="dialog-backdrop"
      data-modal-layer
      role="presentation"
      @click.self="closeOnBackdrop && emit('cancel')"
    >
      <section
        ref="dialogRef"
        class="dialog"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        :aria-describedby="descriptionId"
        tabindex="-1"
        @keydown="onKeydown"
      >
        <h2 :id="titleId">{{ title }}</h2>
        <p :id="descriptionId">{{ message }}</p>
        <div class="dialog__actions">
          <button class="button" data-dialog-initial-focus type="button" @click="emit('cancel')">{{ cancelLabel }}</button>
          <button class="button button--primary" type="button" @click="emit('confirm')">{{ confirmLabel }}</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.dialog__actions .button { min-height: 44px; }
</style>
