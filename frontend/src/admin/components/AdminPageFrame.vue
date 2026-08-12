<script setup lang="ts">
defineProps<{ title: string; description: string; eyebrow?: string }>();
</script>

<template>
  <section class="admin-page">
    <header class="admin-page__header">
      <div>
        <p class="admin-page__eyebrow">{{ eyebrow || "管理端工作台" }}</p>
        <h1>{{ title }}</h1>
        <p class="admin-page__description">{{ description }}</p>
      </div>
      <div class="admin-page__actions"><slot name="actions" /></div>
    </header>
    <div class="admin-page__body"><slot /></div>
  </section>
</template>

<script lang="ts">
import "../admin.css";
</script>
