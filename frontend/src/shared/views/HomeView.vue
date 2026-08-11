<script setup lang="ts">
import { auth } from "../../app/providers/runtime";
</script>

<template>
  <section class="home-screen" aria-labelledby="home-title">
    <div>
      <p class="home-screen__kicker">STRUCTURE / WORKSPACE</p>
      <h1 id="home-title">数据结构学习工作台</h1>
      <p class="home-screen__lead">以章节、证据和算法舞台为线索，逐步重建学习闭环。</p>
    </div>
    <div class="home-screen__actions">
      <RouterLink class="button button--primary" to="/user/chapters">进入学习端</RouterLink>
      <RouterLink v-if="auth.hasRole('ADMIN')" class="button" to="/admin">打开管理端</RouterLink>
      <RouterLink v-else class="button" to="/login">登录账号</RouterLink>
    </div>
  </section>
</template>
