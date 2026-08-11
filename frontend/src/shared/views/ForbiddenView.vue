<template>
  <section class="empty-screen" role="alert" aria-labelledby="forbidden-title">
    <p class="screen-code">403</p>
    <h1 id="forbidden-title">当前账号没有访问权限</h1>
    <p>请使用具备相应角色或模块能力的账号继续。</p>
    <RouterLink class="button button--primary" to="/">返回首页</RouterLink>
  </section>
</template>
