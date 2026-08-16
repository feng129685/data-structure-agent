import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { mockApi } = vi.hoisted(() => ({ mockApi: { listChapters: vi.fn(), searchKnowledge: vi.fn() } }));
vi.mock("../runtime", () => ({ userApi: mockApi }));
import KnowledgeView from "./KnowledgeView.vue";

async function mountView() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/user/knowledge", component: { template: "<div />" } }] });
  await router.push("/user/knowledge?chapterId=stack");
  await router.isReady();
  return mount(KnowledgeView, { global: { plugins: [router], stubs: { UserFrame: { template: "<div><slot /><slot name=\"rail\" /></div>" } } } });
}

describe("KnowledgeView", () => {
  beforeEach(() => { vi.clearAllMocks(); mockApi.listChapters.mockResolvedValue([{ id: "stack", chapterNumber: 1, title: "栈", summary: "" }]); mockApi.searchKnowledge.mockResolvedValue({ ok: true, query: "栈", results: [] }); });

  it("在客户端阻止超过契约长度的检索，而不请求接口", async () => {
    const wrapper = await mountView();
    await flushPromises();
    await wrapper.get("input").setValue("x".repeat(501));
    await wrapper.get("form").trigger("submit");
    expect(mockApi.searchKnowledge).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("不能超过 500 个字符");
  });

  it("显示服务端返回的空检索结果，不以本地内容替代", async () => {
    const wrapper = await mountView();
    await flushPromises();
    await wrapper.get("input").setValue("栈");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(mockApi.searchKnowledge).toHaveBeenCalledWith({ query: "栈", chapterId: "stack", limit: 4 });
    expect(wrapper.text()).toContain("没有找到可见结果");
  });
});
