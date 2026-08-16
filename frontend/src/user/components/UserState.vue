<script setup lang="ts">
defineProps<{
  mode: "loading" | "empty" | "error" | "permission";
  title: string;
  message: string;
  retryLabel?: string;
}>();
const emit = defineEmits<{ retry: [] }>();
</script>

<template>
  <section class="user-state" :class="`user-state--${mode}`" :role="mode === 'error' || mode === 'permission' ? 'alert' : 'status'">
    <template v-if="mode === 'loading'"><span class="user-state__skeleton"></span><span class="user-state__skeleton"></span></template>
    <template v-else>
      <h2>{{ title }}</h2>
      <p>{{ message }}</p>
      <div v-if="retryLabel || $slots.default || $slots.actions" class="user-page__actions">
        <button v-if="retryLabel" class="user-action" type="button" @click="emit('retry')">{{ retryLabel }}</button>
        <slot />
        <slot name="actions" />
      </div>
    </template>
  </section>
</template>
