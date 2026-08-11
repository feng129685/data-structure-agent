<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import AppShell from "./app-shell/AppShell.vue";

const route = useRoute();
const shellLayout = computed(() => route.meta.layout !== "auth" && route.meta.layout !== "minimal");
</script>

<template>
  <RouterView v-slot="{ Component }">
    <AppShell v-if="shellLayout"><component :is="Component" /></AppShell>
    <component v-else :is="Component" />
  </RouterView>
</template>
