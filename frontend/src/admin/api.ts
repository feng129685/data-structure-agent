import { api } from "../app/providers/runtime";
import type {
  AdminAuditEventPage,
  AdminCapability,
  AdminUser,
  AdminUserPage,
  AdminUserRolesRequest,
  AdminUserStatusRequest,
  BackgroundTask,
  BackgroundTaskPage,
  ModelConfig,
  ModelConfigCapability,
  ModelConfigConnectionTest,
  MailConfig,
  MailConfigCapability,
  MailConnectionTest,
  TestMailResult,
  UpdateMailConfigRequest,
  ReviewDetail,
  ReviewHistoryEvent,
  ReviewItem,
  ReviewItemPage,
  ReviewStatusRequest,
} from "../shared/types";
import { ApiClientError, type ApiResponse } from "../shared/api";

function jsonData<T>(response: ApiResponse<T>): T {
  if (response.kind !== "json") throw new Error("ADMIN_INVALID_RESPONSE");
  return response.data;
}

export interface AdminQuery {
  page?: number;
  size?: number;
  search?: string;
  status?: string;
  role?: string;
  type?: string;
  taskType?: string;
  action?: string;
  targetType?: string;
  targetId?: string;
  actorUserId?: number;
  from?: string;
  to?: string;
}

function compactQuery(input: AdminQuery): Record<string, string | number> {
  return Object.fromEntries(Object.entries(input).filter(([, value]) => value !== undefined && value !== "")) as Record<string, string | number>;
}

export const adminApi = {
  async capabilities(): Promise<AdminCapability> {
    return jsonData(await api.get<AdminCapability>("/admin/capabilities"));
  },
  async getModelConfig(): Promise<ModelConfigCapability> {
    return jsonData(await api.get<ModelConfigCapability>("/admin/model-config"));
  },
  async updateModelConfig(payload: Record<string, unknown>): Promise<ModelConfig> {
    return jsonData(await api.put<ModelConfig>("/admin/model-config", payload));
  },
  async testModelConnection(): Promise<ModelConfigConnectionTest> {
    return jsonData(await api.post<ModelConfigConnectionTest>("/admin/model-config/test"));
  },
  async getMailConfig(): Promise<MailConfigCapability> {
    return jsonData(await api.get<MailConfigCapability>("/admin/mail-config"));
  },
  async updateMailConfig(payload: UpdateMailConfigRequest): Promise<MailConfig> {
    return jsonData(await api.put<MailConfig>("/admin/mail-config", payload));
  },
  async testMailConnection(payload: UpdateMailConfigRequest): Promise<MailConnectionTest> {
    return jsonData(await api.post<MailConnectionTest>("/admin/mail-config/test-connection", payload));
  },
  async sendTestMail(config: UpdateMailConfigRequest, recipient: string): Promise<TestMailResult> {
    return jsonData(await api.post<TestMailResult>("/admin/mail-config/test-email", { config, recipient }));
  },
  async users(query: AdminQuery = {}): Promise<AdminUserPage> {
    return jsonData(await api.get<AdminUserPage>("/admin/users", { query: compactQuery(query) }));
  },
  async user(id: number): Promise<AdminUser> {
    return jsonData(await api.get<AdminUser>(`/admin/users/${encodeURIComponent(id)}`));
  },
  async updateUserStatus(id: number, payload: AdminUserStatusRequest): Promise<AdminUser> {
    return jsonData(await api.patch<AdminUser>(`/admin/users/${encodeURIComponent(id)}/status`, payload));
  },
  async updateUserRoles(id: number, payload: AdminUserRolesRequest): Promise<AdminUser> {
    return jsonData(await api.patch<AdminUser>(`/admin/users/${encodeURIComponent(id)}/roles`, payload));
  },
  async reviews(query: AdminQuery = {}): Promise<ReviewItemPage> {
    return jsonData(await api.get<ReviewItemPage>("/admin/reviews", { query: compactQuery(query) }));
  },
  async review(type: string, id: string): Promise<ReviewDetail> {
    return jsonData(await api.get<ReviewDetail>(`/admin/reviews/${encodeURIComponent(type)}/${encodeURIComponent(id)}`));
  },
  async updateReviewStatus(type: string, id: string, payload: ReviewStatusRequest): Promise<ReviewItem> {
    return jsonData(await api.patch<ReviewItem>(`/admin/reviews/${encodeURIComponent(type)}/${encodeURIComponent(id)}/status`, payload));
  },
  async reviewHistory(type: string, id: string): Promise<ReviewHistoryEvent[]> {
    return jsonData(await api.get<ReviewHistoryEvent[]>(`/admin/reviews/${encodeURIComponent(type)}/${encodeURIComponent(id)}/history`));
  },
  async tasks(query: AdminQuery = {}): Promise<BackgroundTaskPage> {
    return jsonData(await api.get<BackgroundTaskPage>("/admin/background-tasks", { query: compactQuery(query) }));
  },
  async task(id: number): Promise<BackgroundTask> {
    return jsonData(await api.get<BackgroundTask>(`/admin/background-tasks/${encodeURIComponent(id)}`));
  },
  async recoverTimeouts(): Promise<BackgroundTask> {
    return jsonData(await api.post<BackgroundTask>("/admin/background-tasks/recover-timeouts"));
  },
  async retryTask(id: number): Promise<BackgroundTask> {
    return jsonData(await api.post<BackgroundTask>(`/admin/background-tasks/${encodeURIComponent(id)}/retry`));
  },
  async cancelTask(id: number): Promise<BackgroundTask> {
    return jsonData(await api.post<BackgroundTask>(`/admin/background-tasks/${encodeURIComponent(id)}/cancel`));
  },
  async auditEvents(query: AdminQuery = {}): Promise<AdminAuditEventPage> {
    return jsonData(await api.get<AdminAuditEventPage>("/admin/audit-events", { query: compactQuery(query) }));
  },
};

export function adminErrorMessage(error: unknown, action: string): string {
  const status = typeof error === "object" && error !== null && "status" in error ? Number((error as { status?: unknown }).status) : undefined;
  const code = error instanceof ApiClientError ? error.code : "NETWORK_ERROR";
  const requestId = error instanceof ApiClientError ? error.requestId : "";
  const guidance = status === 403
    ? "当前账号没有执行此操作的权限。"
    : status === 404
      ? "目标资源不存在或已被撤回。"
      : status === 409
        ? "服务端拒绝了当前状态变更，请刷新后重新确认。"
        : status === 429
          ? "请求受到频率限制，请稍后重试。"
          : status === 503
            ? "管理服务暂时不可用，请确认服务配置后重试。"
            : status && status >= 500
              ? "服务暂时异常，可以稍后重试。"
              : "请检查网络连接和输入后重试。";
  return `${action}未完成（${code}）。${guidance}${requestId ? ` 请求 ID：${requestId}` : ""}`;
}

export function formatDate(value?: string | null): string {
  if (!value) return "未记录";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}
