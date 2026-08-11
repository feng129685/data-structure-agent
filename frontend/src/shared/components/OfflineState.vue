<script setup lang="ts">
withDefaults(defineProps<{ title?: string; message?: string }>(), { title: "当前离线", message: "网络恢复后可以继续同步。" });
</script>
<template><div class="state state--offline" role="status"><h2>{{ title }}</h2><p>{{ message }}</p><slot /></div></template>
