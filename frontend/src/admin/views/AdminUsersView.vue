<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import AdminPageFrame from "../components/AdminPageFrame.vue";
import { adminApi, adminErrorMessage, formatDate } from "../api";
import type { AdminUser, Role } from "../../shared/types";
import LoadingState from "../../shared/components/LoadingState.vue";
import ErrorState from "../../shared/components/ErrorState.vue";
import EmptyState from "../../shared/components/EmptyState.vue";
import RetryButton from "../../shared/components/RetryButton.vue";
import StatusBadge from "../../shared/components/StatusBadge.vue";
import InlineNotice from "../../shared/components/InlineNotice.vue";

const page = ref(0); const size = 20; const total = ref(0); const items = ref<AdminUser[]>([]); const loading = ref(true); const error = ref(""); const notice = ref("");
const filters = reactive({ search: "", status: "", role: "" });
const selected = ref<AdminUser | null>(null); const rolesDraft = ref<Role[]>([]); const actionBusy = ref<number | null>(null); const detailBusy = ref(false); const detailError = ref("");
function noticeTone(value: string): "success" | "danger" { return value.includes("未完成") || value.includes("失败") ? "danger" : "success"; }

async function load() { loading.value = true; error.value = ""; try { const result = await adminApi.users({ page: page.value, size, search: filters.search, status: filters.status, role: filters.role }); items.value = result.items; total.value = result.total; } catch (failure) { error.value = adminErrorMessage(failure, "读取用户列表"); } finally { loading.value = false; } }
function applyFilters() { page.value = 0; void load(); }
function selectUser(user: AdminUser) { selected.value = user; rolesDraft.value = [...user.roles]; }
async function openUser(user: AdminUser) {
  selectUser(user); detailBusy.value = true; detailError.value = "";
  try { const detail = await adminApi.user(user.id); selected.value = detail; rolesDraft.value = [...detail.roles]; }
  catch (failure) { detailError.value = adminErrorMessage(failure, "读取用户详情"); }
  finally { detailBusy.value = false; }
}
function toggleRole(role: Role) { rolesDraft.value = rolesDraft.value.includes(role) ? rolesDraft.value.filter((item) => item !== role) : [...rolesDraft.value, role]; }
async function changeStatus(user: AdminUser) {
  const next = user.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
  if (!window.confirm(`确认将 ${user.email} 设为${next === "ACTIVE" ? "启用" : "禁用"}？服务端仍会校验自禁用和最后管理员保护。`)) return;
  actionBusy.value = user.id; notice.value = "";
  try { const updated = await adminApi.updateUserStatus(user.id, { status: next, reason: next === "DISABLED" ? "管理员在管理端执行状态变更" : null }); items.value = items.value.map((item) => item.id === updated.id ? updated : item); if (selected.value?.id === updated.id) selected.value = updated; notice.value = "用户状态已更新。"; }
  catch (failure) { notice.value = adminErrorMessage(failure, "更新用户状态"); }
  finally { actionBusy.value = null; }
}
async function saveRoles(user: AdminUser) {
  if (!rolesDraft.value.length) { notice.value = "角色修改未完成：至少保留一个角色。"; return; }
  if (!window.confirm(`确认修改 ${user.email} 的角色为 ${rolesDraft.value.join("、")}？`)) return;
  actionBusy.value = user.id; notice.value = "";
  try { const updated = await adminApi.updateUserRoles(user.id, { roles: rolesDraft.value }); items.value = items.value.map((item) => item.id === updated.id ? updated : item); selected.value = updated; notice.value = "用户角色已更新。"; }
  catch (failure) { notice.value = adminErrorMessage(failure, "更新用户角色"); }
  finally { actionBusy.value = null; }
}
onMounted(load);
</script>

<template>
  <AdminPageFrame title="用户与角色" description="分页读取安全用户投影。状态和角色变更都会经过服务端保护并要求明确确认。">
    <template #actions><button class="button button--small" type="button" :disabled="loading" @click="load">刷新</button></template>
    <form class="admin-toolbar" @submit.prevent="applyFilters">
      <label class="admin-field admin-field--wide"><span>搜索邮箱</span><input v-model="filters.search" maxlength="160" placeholder="按邮箱搜索" /></label>
      <label class="admin-field"><span>状态</span><select v-model="filters.status"><option value="">全部状态</option><option value="ACTIVE">ACTIVE</option><option value="DISABLED">DISABLED</option></select></label>
      <label class="admin-field"><span>角色</span><select v-model="filters.role"><option value="">全部角色</option><option value="STUDENT">STUDENT</option><option value="TEACHER">TEACHER</option><option value="ADMIN">ADMIN</option></select></label>
      <button class="button button--primary" type="submit" :disabled="loading">筛选</button>
    </form>
    <InlineNotice v-if="notice" :message="notice" :tone="noticeTone(notice)" />
    <LoadingState v-if="loading" label="正在读取用户…" />
    <ErrorState v-else-if="error" title="用户列表读取失败" :message="error"><RetryButton @retry="load" /></ErrorState>
    <EmptyState v-else-if="!items.length" title="没有匹配用户" message="当前筛选条件没有返回用户记录。" />
    <section v-else class="admin-table-wrap" aria-label="用户列表">
      <table class="admin-table"><thead><tr><th>用户</th><th>状态</th><th>角色</th><th>创建时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="user in items" :key="user.id" :data-selected="selected?.id === user.id"><td><strong>{{ user.email }}</strong><div class="admin-muted">#{{ user.id }}</div></td><td><StatusBadge :label="user.status" :tone="user.status === 'ACTIVE' ? 'success' : 'warning'" /><div v-if="user.disabledReason" class="admin-muted">{{ user.disabledReason }}</div></td><td><div class="admin-checks"><label v-for="role in ['STUDENT','TEACHER','ADMIN'] as Role[]" :key="role" class="admin-check"><input type="checkbox" :checked="(selected?.id === user.id ? rolesDraft : user.roles).includes(role)" @change="selectUser(user); toggleRole(role)" />{{ role }}</label></div></td><td>{{ formatDate(user.createdAt) }}</td><td><div class="admin-table__actions"><button class="button button--small" type="button" :disabled="detailBusy && selected?.id === user.id" @click="openUser(user)">查看</button><button class="button button--small" type="button" :disabled="actionBusy === user.id" @click="changeStatus(user)">{{ user.status === 'ACTIVE' ? '禁用' : '启用' }}</button><button v-if="selected?.id === user.id" class="button button--small button--primary" type="button" :disabled="actionBusy === user.id" @click="saveRoles(user)">保存角色</button></div></td></tr>
      </tbody></table>
    </section>
    <div class="admin-pagination"><span>第 {{ page + 1 }} 页 · 共 {{ total }} 条</span><div class="admin-pagination__actions"><button class="button button--small" type="button" :disabled="page === 0 || loading" @click="page--; load()">上一页</button><button class="button button--small" type="button" :disabled="(page + 1) * size >= total || loading" @click="page++; load()">下一页</button></div></div>
    <aside v-if="selected" class="admin-detail" aria-label="用户详情"><div class="admin-detail__header"><h2>用户详情</h2><button class="button button--small" type="button" @click="selected = null; detailError = ''">关闭</button></div><LoadingState v-if="detailBusy" label="正在读取用户详情…" /><ErrorState v-else-if="detailError" title="用户详情读取失败" :message="detailError"><RetryButton @retry="openUser(selected)" /></ErrorState><dl v-else><dt>邮箱</dt><dd>{{ selected.email }}</dd><dt>状态</dt><dd>{{ selected.status }}</dd><dt>禁用时间</dt><dd>{{ formatDate(selected.disabledAt) }}</dd><dt>更新时间</dt><dd>{{ formatDate(selected.updatedAt) }}</dd></dl></aside>
  </AdminPageFrame>
</template>
