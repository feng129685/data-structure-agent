import { ApiClientError } from "../shared/api";

export type UserErrorKind = "permission" | "not-found" | "conflict" | "limited" | "timeout" | "service" | "network" | "validation" | "unknown";

export interface UserErrorPresentation {
  kind: UserErrorKind;
  title: string;
  message: string;
  retryable: boolean;
}

export function presentUserError(cause: unknown): UserErrorPresentation {
  const error = cause as Partial<ApiClientError> | undefined;
  const status = error?.status;
  const code = error?.code;
  if (status === 401) return { kind: "permission", title: "需要登录后继续", message: "当前操作需要有效学习账号。", retryable: false };
  if (status === 403) return { kind: "permission", title: "当前账号没有权限", message: "服务已拒绝此学习资源或操作。", retryable: false };
  if (status === 404) return { kind: "not-found", title: "资源不可访问", message: "该资源可能未发布、已移除，或当前账号没有访问范围。", retryable: false };
  if (status === 409) return { kind: "conflict", title: "当前状态已变化", message: "请刷新后继续操作，避免覆盖服务器中的最新学习状态。", retryable: true };
  if (status === 429) return { kind: "limited", title: "请求过于频繁", message: "服务暂时限制了请求，请稍后重试。", retryable: true };
  if (status === 502 || status === 503) return { kind: "service", title: "学习服务暂不可用", message: "当前页面位置已保留，服务恢复后可再次尝试。", retryable: true };
  if (status === 504 || code === "REQUEST_TIMEOUT") return { kind: "timeout", title: "请求超时", message: "上游服务未在规定时间内响应。", retryable: true };
  if (cause instanceof TypeError || /network|fetch/i.test(String((cause as Error | undefined)?.message))) return { kind: "network", title: "网络连接不可用", message: "请检查网络后重试，当前学习位置不会丢失。", retryable: true };
  if (status === 400 || status === 413) return { kind: "validation", title: "提交内容无法处理", message: error?.message || "请检查输入内容后重试。", retryable: false };
  return { kind: "unknown", title: "操作未完成", message: error?.message || "服务返回了未预期的结果。", retryable: true };
}
