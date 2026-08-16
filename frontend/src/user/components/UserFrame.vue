<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import "../user.css";

const route = useRoute();
const navigation = [
  { to: "/user/home", label: "学习台" },
  { to: "/user/chapters", label: "主线学习" },
  { to: "/user/knowledge", label: "知识检索" },
  { to: "/user/coach", label: "问答陪练" },
  { to: "/user/classroom", label: "课堂" },
  { to: "/user/code", label: "代码实验" },
  { to: "/user/progress", label: "复盘" },
  { to: "/user/profile", label: "账户" },
];
const currentModule = computed(() => String(route.meta.module || "学习工作台"));
</script>

<template>
  <div class="user-workspace">
    <a class="user-skip-link" href="#user-learning-content">跳到主内容</a>
    <aside class="user-workspace__sidebar" aria-label="学习导航">
      <div class="user-workspace__brand"><strong>{{ currentModule }}</strong><span>课程上下文已保留</span></div>
      <nav class="user-workspace__nav">
        <RouterLink v-for="item in navigation" :key="item.to" :to="item.to">{{ item.label }}</RouterLink>
      </nav>
    </aside>
    <main class="user-workspace__content" id="user-learning-content"><slot /></main>
    <aside class="user-workspace__rail" aria-label="当前学习上下文"><slot name="rail" /></aside>
    <nav class="user-workspace__mobile-nav" aria-label="移动端学习导航">
      <RouterLink v-for="item in navigation" :key="item.to" :to="item.to">{{ item.label }}</RouterLink>
    </nav>
  </div>
</template>
