import type { RouteRecordRaw } from "vue-router";
import UserHomeView from "./views/UserHomeView.vue";
import ChaptersView from "./views/ChaptersView.vue";
import ChapterDetailView from "./views/ChapterDetailView.vue";
import ResourceView from "./views/ResourceView.vue";
import KnowledgeView from "./views/KnowledgeView.vue";
import ChatView from "./views/ChatView.vue";
import ClassroomView from "./views/ClassroomView.vue";
import AnimationView from "./views/AnimationView.vue";
import CodeView from "./views/CodeView.vue";
import ProgressView from "./views/ProgressView.vue";
import ProfileView from "./views/ProfileView.vue";

const protectedMeta = (module: string) => ({ requiresAuth: true, layout: "shell", module });

export const userRoutes: RouteRecordRaw[] = [
  { path: "/user", redirect: "/user/home", meta: protectedMeta("学习端") },
  { path: "/user/home", name: "user-home", component: UserHomeView, meta: protectedMeta("学习台") },
  { path: "/user/chapters", name: "user-chapters", component: ChaptersView, meta: protectedMeta("主线学习") },
  { path: "/user/chapters/:chapterId", name: "user-chapter-detail", component: ChapterDetailView, meta: protectedMeta("章节详情") },
  { path: "/user/resources/:resourceId", name: "user-resource", component: ResourceView, meta: protectedMeta("课程资料") },
  { path: "/user/knowledge", name: "user-knowledge", component: KnowledgeView, meta: protectedMeta("知识检索") },
  { path: "/user/coach", name: "user-coach", component: ChatView, meta: protectedMeta("问答陪练") },
  { path: "/user/classroom", name: "user-classroom", component: ClassroomView, meta: protectedMeta("课堂学习") },
  { path: "/user/animation", name: "user-animation", component: AnimationView, meta: protectedMeta("算法舞台") },
  { path: "/user/code", name: "user-code", component: CodeView, meta: protectedMeta("代码实验") },
  { path: "/user/progress", name: "user-progress", component: ProgressView, meta: protectedMeta("学习复盘") },
  { path: "/user/profile", name: "user-profile", component: ProfileView, meta: protectedMeta("个人资料") },
];
