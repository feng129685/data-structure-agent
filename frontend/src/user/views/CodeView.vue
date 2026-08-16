<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute } from "vue-router";
import type { AiReadiness, CodeAnalysisResponse, CodeLanguage, CodeRunRequest, CodeRunResponse } from "../../shared/types/contracts";
import UserFrame from "../components/UserFrame.vue";
import UserState from "../components/UserState.vue";
import { presentUserError, type UserErrorPresentation } from "../errors";
import { userApi } from "../runtime";

const route = useRoute();
const chapterId = computed(() => String(route.query.chapterId || ""));
const language = ref<CodeLanguage>("c");
const code = ref("#include <stdio.h>\n\nint main(void) {\n  printf(\"hello, data structure!\\n\");\n  return 0;\n}\n");
const stdin = ref("");
const result = ref<CodeRunResponse | null>(null);
const lastRun = ref<CodeRunRequest | null>(null);
const analysis = ref<CodeAnalysisResponse | null>(null);
const running = ref(false);
const analyzing = ref(false);
const runError = ref<UserErrorPresentation | null>(null);
const analysisError = ref<UserErrorPresentation | null>(null);

const busy = computed(() => running.value || analyzing.value);
const statusLabels: Record<CodeRunResponse["status"], string> = {
  success: "运行成功",
  compile_error: "编译失败",
  runtime_error: "运行失败",
};
const statusLabel = computed(() => running.value ? "正在运行" : result.value ? statusLabels[result.value.status] : "尚未运行");

function defaultCode(nextLanguage: CodeLanguage): string {
  return nextLanguage === "python"
    ? "print('hello, data structure!')\n"
    : "#include <stdio.h>\n\nint main(void) {\n  printf(\"hello, data structure!\\n\");\n  return 0;\n}\n";
}

function clearRunState(): void {
  result.value = null;
  lastRun.value = null;
  analysis.value = null;
  runError.value = null;
  analysisError.value = null;
}

function changeLanguage(): void {
  if (busy.value) return;
  clearRunState();
  code.value = defaultCode(language.value);
}

function invalidateRun(): void {
  if (!running.value) clearRunState();
}

function runRequest(): CodeRunRequest {
  return {
    language: language.value,
    code: code.value,
    ...(stdin.value ? { stdin: stdin.value } : {}),
    ...(chapterId.value ? { chapterId: chapterId.value } : {}),
  };
}

function analysisBlockedMessage(readiness: AiReadiness): string {
  if (!readiness.modelAvailable) return "代码分析模型当前不可用，请稍后重试。";
  if (readiness.quotaStatus === "NOT_CONFIGURED") return "代码分析配额尚未配置，暂时不能提交分析。";
  if (readiness.quotaStatus === "EXHAUSTED") return "今日代码分析配额已用尽，请稍后再试。";
  if (readiness.quotaStatus === "CONCURRENCY_LIMITED") return "代码分析服务正在处理其他请求，请稍后重试。";
  return "当前不满足代码分析条件，请稍后重试。";
}

function analysisRequest(currentResult: CodeRunResponse): Parameters<typeof userApi.analyzeCode>[0] {
  if (currentResult.runId) return { runId: currentResult.runId };
  const request = lastRun.value;
  if (!request) {
    throw new Error("缺少与本次运行对应的代码快照");
  }
  return {
    runId: null,
    language: request.language,
    code: request.code,
    ...(request.stdin ? { stdin: request.stdin } : {}),
    stdout: currentResult.stdout,
    stderr: currentResult.stderr,
    status: currentResult.status,
    ...(request.chapterId ? { chapterId: request.chapterId } : {}),
  };
}

async function run(): Promise<void> {
  if (busy.value) return;
  clearRunState();
  const request = runRequest();
  if (!request.code.trim()) {
    runError.value = {
      kind: "validation",
      title: "代码为空",
      message: "请输入要运行的 C 或 Python 代码。",
      retryable: false,
    };
    return;
  }

  running.value = true;
  try {
    result.value = await userApi.runCode(request);
    lastRun.value = request;
  } catch (cause) {
    runError.value = presentUserError(cause);
  } finally {
    running.value = false;
  }
}

async function analyze(): Promise<void> {
  const currentResult = result.value;
  if (!currentResult || busy.value) return;
  analysis.value = null;
  analysisError.value = null;
  analyzing.value = true;
  try {
    const readiness = await userApi.getReadiness({
      operation: "CODE_ANALYSIS",
      chapterId: chapterId.value || undefined,
    });
    if (!readiness.allowFormalGeneration) {
      analysisError.value = {
        kind: "service",
        title: "当前不能开始代码分析",
        message: analysisBlockedMessage(readiness),
        retryable: true,
      };
      return;
    }
    analysis.value = await userApi.analyzeCode(analysisRequest(currentResult));
  } catch (cause) {
    analysisError.value = presentUserError(cause);
  } finally {
    analyzing.value = false;
  }
}
</script>

<template>
  <UserFrame>
    <section class="user-page" aria-labelledby="code-title">
      <header class="user-page__heading">
        <div>
          <p class="user-page__eyebrow">C / Python</p>
          <h1 id="code-title">代码实验</h1>
          <p class="user-page__intro">实际提交到沙箱执行；编译错误、运行错误、超时和服务未配置都会原样呈现。</p>
        </div>
        <RouterLink class="user-action" :to="chapterId ? `/user/chapters/${chapterId}` : '/user/chapters'">返回章节</RouterLink>
      </header>

      <div class="user-code">
        <form class="user-form user-panel" @submit.prevent="run">
          <div class="user-form__split">
            <label>
              语言
              <select v-model="language" :disabled="busy" @change="changeLanguage">
                <option value="c">C</option>
                <option value="python">Python</option>
              </select>
            </label>
            <label>章节上下文<input :value="chapterId || '未限定章节'" disabled /></label>
          </div>
          <label>
            代码
            <textarea v-model="code" class="user-code-editor" spellcheck="false" aria-label="代码编辑器" :disabled="busy" @input="invalidateRun"></textarea>
          </label>
          <label>
            标准输入
            <textarea v-model="stdin" class="user-code-stdin" placeholder="可选" :disabled="busy" @input="invalidateRun"></textarea>
          </label>
          <div class="user-page__actions">
            <button class="user-action user-action--primary" type="submit" :disabled="busy">{{ running ? '运行中…' : '运行代码' }}</button>
            <button class="user-action" data-testid="code-analyze" type="button" :disabled="!result || busy" @click="analyze">{{ analyzing ? '分析中…' : '分析结果' }}</button>
          </div>
        </form>

        <section class="user-page__section" aria-live="polite">
          <header><h2>运行结果</h2><p>{{ statusLabel }}</p></header>
          <UserState
            v-if="running"
            data-testid="code-result-state"
            mode="loading"
            title="正在运行代码"
            message="代码正在提交到隔离沙箱。"
          />
          <UserState
            v-else-if="runError"
            data-testid="code-result-state"
            :mode="runError.kind === 'permission' ? 'permission' : 'error'"
            :title="runError.title"
            :message="runError.message"
            :retry-label="runError.retryable ? '重新运行' : undefined"
            @retry="run"
          >
            <RouterLink v-if="runError.kind === 'permission'" class="user-action user-action--primary" to="/login">前往登录</RouterLink>
          </UserState>
          <template v-else-if="result">
            <div data-testid="code-run-result">
              <p class="user-list__meta user-list__meta--left">耗时 {{ result.durationMs }} ms · {{ result.language }} · {{ result.runId || '未持久化' }}</p>
              <h3>标准输出</h3>
              <pre>{{ result.stdout || '（无输出）' }}</pre>
              <h3>错误输出</h3>
              <pre>{{ result.stderr || '（无错误输出）' }}</pre>
            </div>
          </template>
          <UserState
            v-else
            data-testid="code-result-state"
            mode="empty"
            title="尚未运行代码"
            message="选择语言并提交代码后，这里显示沙箱真实输出。"
          />

          <section v-if="result" class="user-page__section" aria-labelledby="analysis-title">
            <header><h2 id="analysis-title">代码分析</h2><p>仅在服务端允许时请求</p></header>
            <UserState
              v-if="analyzing"
              data-testid="code-analysis-state"
              mode="loading"
              title="正在检查并分析代码"
              message="正在确认账号、模型和配额状态。"
            />
            <UserState
              v-else-if="analysisError"
              data-testid="code-analysis-state"
              :mode="analysisError.kind === 'permission' ? 'permission' : 'error'"
              :title="analysisError.title"
              :message="analysisError.message"
              :retry-label="analysisError.retryable ? '重新分析' : undefined"
              @retry="analyze"
            >
              <RouterLink v-if="analysisError.kind === 'permission'" class="user-action user-action--primary" to="/login">前往登录</RouterLink>
            </UserState>
            <section v-else-if="analysis" class="user-panel" data-testid="code-analysis">
              <h3>分析结果</h3>
              <p class="user-prewrap">{{ analysis.analysis }}</p>
            </section>
            <UserState
              v-else
              data-testid="code-analysis-state"
              mode="empty"
              title="尚未请求代码分析"
              message="分析会先由服务端确认当前账号、模型和配额是否允许。"
            />
          </section>
        </section>
      </div>
    </section>

    <template #rail>
      <div class="user-rail-list">
        <strong>真实接口</strong>
        <p>POST /code/runs</p>
        <p>GET /ai/readiness?operation=CODE_ANALYSIS</p>
        <p>POST /code/analyze</p>
        <strong>环境要求</strong>
        <p>沙箱依赖服务端 Piston 配置；未配置时会显示服务错误，不生成假输出。</p>
      </div>
    </template>
  </UserFrame>
</template>
