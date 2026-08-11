<script setup lang="ts">
withDefaults(defineProps<{ message?: string }>(), { message: "显示的是较早的数据。" });
</script>
<template><p class="inline-notice inline-notice--warning" role="status">{{ message }}</p></template>
