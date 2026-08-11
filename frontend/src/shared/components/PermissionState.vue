<script setup lang="ts">
withDefaults(defineProps<{ title?: string; message?: string }>(), { title: "需要登录", message: "登录后才能访问此内容。" });
</script>
<template><div class="state state--permission" role="alert"><h2>{{ title }}</h2><p>{{ message }}</p><slot /></div></template>
