<script setup lang="ts">
defineProps<{ title: string; description: string }>();
</script>

<template>
  <section class="admin-page" :data-admin-view="title">
    <header class="admin-page__header">
      <div class="admin-page__header-copy">
        <h1>{{ title }}</h1>
        <p class="admin-page__description">{{ description }}</p>
      </div>
      <div v-if="$slots.actions" class="admin-page__header-tools">
        <div class="admin-page__actions"><slot name="actions" /></div>
      </div>
    </header>
    <div class="admin-page__body"><slot /></div>
  </section>
</template>

<script lang="ts">
import "../admin.css";
</script>
