import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { mockApi } = vi.hoisted(() => ({ mockApi: { getResource: vi.fn(), getResourceContent: vi.fn(), recordLearningEvent: vi.fn() } }));
vi.mock("../runtime", () => ({ userApi: mockApi }));
import ResourceView from "./ResourceView.vue";

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/user/resources/:resourceId", component: { template: "<div />" } },
      { path: "/user/chapters", component: { template: "<div />" } },
      { path: "/user/chapters/:chapterId", component: { template: "<div />" } },
    ],
  });
  await router.push("/user/resources/resource-1");
  await router.isReady();
  return mount(ResourceView, { global: { plugins: [router], stubs: { UserFrame: { template: "<div><slot /><slot name=\"rail\" /></div>" } } } });
}

describe("ResourceView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("URL", {
      createObjectURL: vi.fn(() => "blob:resource-preview"),
      revokeObjectURL: vi.fn(),
    });
    mockApi.getResource.mockResolvedValue({ id: "resource-1", chapterId: "stack", type: "PDF", title: "栈讲义", description: "", sourceName: "课程组", versionLabel: "v1", reviewStatus: "PUBLISHED", licenseScope: "PUBLIC", contentUrl: null });
    mockApi.getResourceContent.mockResolvedValue({ bytes: new ArrayBuffer(4), disposition: "inline; filename=stack.pdf", contentType: "application/pdf" });
    mockApi.recordLearningEvent.mockResolvedValue({ id: 1 });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("同时读取元数据和二进制内容，且不显示服务器路径", async () => {
    const wrapper = await mountView();
    await flushPromises();
    expect(mockApi.getResource).toHaveBeenCalledWith("resource-1");
    expect(mockApi.getResourceContent).toHaveBeenCalledWith("resource-1");
    expect(wrapper.text()).toContain("栈讲义");
    expect(wrapper.text()).not.toContain("course-content");
  });

  it("将 404 显示为资源不可访问状态", async () => {
    mockApi.getResource.mockRejectedValueOnce({ status: 404, message: "not found" });
    const wrapper = await mountView();
    await flushPromises();
    expect(wrapper.text()).toContain("资源不可访问");
  });
});
