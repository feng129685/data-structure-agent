import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { ApiClientError } from "../../shared/api";
import AdminReviewsView from "./AdminReviewsView.vue";

const reviewItem = {
  type: "KNOWLEDGE_CHUNK" as const,
  id: "chunk-1",
  title: "栈的基本操作",
  status: "PUBLISHED" as const,
  chapterId: "stack",
  sourceComplete: true,
  updatedAt: "2026-08-12T01:00:00Z",
};

const reviews = vi.hoisted(() => vi.fn());
const review = vi.hoisted(() => vi.fn());
const reviewHistory = vi.hoisted(() => vi.fn());
const updateReviewStatus = vi.hoisted(() => vi.fn());

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

vi.mock("../api", () => ({
  adminApi: { reviews, review, reviewHistory, updateReviewStatus },
  adminErrorMessage: (failure: unknown, action: string) => {
    const error = failure as { code?: string; requestId?: string };
    return `${action}未完成（${error.code || "NETWORK_ERROR"}）。请求 ID：${error.requestId || "无"}`;
  },
  formatDate: (value?: string | null) => value || "未记录",
}));

describe("AdminReviewsView", () => {
  beforeEach(() => {
    reviews.mockReset().mockResolvedValue({ items: [reviewItem], page: 0, size: 20, total: 1 });
    review.mockReset().mockResolvedValue({ item: reviewItem, sourceChain: [{ type: "RESOURCE", id: "source-1", title: "课程资料", status: "VERIFIED" }] });
    reviewHistory.mockReset().mockResolvedValue([]);
    updateReviewStatus.mockReset();
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });

  it("loads the real detail and history through public actions", async () => {
    const wrapper = mount(AdminReviewsView);
    await flushPromises();
    await wrapper.get("button[data-action='open-review']").trigger("click");
    await flushPromises();

    expect(review).toHaveBeenCalledWith("KNOWLEDGE_CHUNK", "chunk-1");
    expect(reviewHistory).toHaveBeenCalledWith("KNOWLEDGE_CHUNK", "chunk-1");
    expect(wrapper.text()).toContain("课程资料");
  });

  it("shows the detail loading state while the public detail request is pending", async () => {
    const pending = deferred<{ item: typeof reviewItem; sourceChain: never[] }>();
    review.mockReturnValueOnce(pending.promise);
    const wrapper = mount(AdminReviewsView);
    await flushPromises();

    await wrapper.get("button[data-action='open-review']").trigger("click");

    expect(wrapper.get("[data-state='review-detail-loading']").text()).toContain("正在读取来源链");
    pending.resolve({ item: reviewItem, sourceChain: [] });
    await flushPromises();
    wrapper.unmount();
  });

  it("keeps a rejected VERIFIED transition visible with the backend error code and request id", async () => {
    updateReviewStatus.mockRejectedValue(new ApiClientError({
      status: 409,
      code: "ADMIN_REVIEW_SOURCE_INCOMPLETE",
      message: "来源链不完整",
      requestId: "req-review-409",
      details: [],
    }));
    const wrapper = mount(AdminReviewsView);
    await flushPromises();
    await wrapper.get("button[data-action='open-review']").trigger("click");
    await flushPromises();
    await wrapper.get("select[data-field='next-status']").setValue("VERIFIED");
    await wrapper.get("button[data-action='update-review']").trigger("click");
    await flushPromises();

    expect(updateReviewStatus).toHaveBeenCalledWith("KNOWLEDGE_CHUNK", "chunk-1", { status: "VERIFIED", note: null });
    expect(wrapper.text()).toContain("ADMIN_REVIEW_SOURCE_INCOMPLETE");
    expect(wrapper.text()).toContain("req-review-409");
    expect(wrapper.text()).not.toContain("审核状态已更新。");
  });
});
