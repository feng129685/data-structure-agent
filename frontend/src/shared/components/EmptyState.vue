<script setup lang="ts">
withDefaults(defineProps<{ title?: string; message?: string }>(), { title: "暂无内容", message: "这里还没有可展示的内容。" });
</script>
<template><div class="state state--empty"><h2>{{ title }}</h2><p>{{ message }}</p><slot /></div></template>
