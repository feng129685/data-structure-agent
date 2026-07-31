package com.feng.dsagent.animation;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.model.ModelClient;
import com.feng.dsagent.model.ModelClientException;
import com.feng.dsagent.model.ModelMessage;
import com.feng.dsagent.model.ModelRequest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AnimationGenerationService {

    private static final Set<String> TYPES = Set.of("stack", "list", "tree", "queue", "heap", "hash", "array");
    private static final Pattern FENCED_JSON = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final String SYSTEM_PROMPT = """
        你是数据结构交互式动画规划器。只返回 JSON，不要返回解释、Markdown、HTML、SVG、CSS 或 JavaScript，禁止 HTML 和任何可执行代码。
        JSON 格式必须是：
        {"animation":true,"type":"stack|list|tree|queue|heap|hash|array","title":"简短标题","description":"演示目标","initial":[],"steps":[{"op":"操作","label":"步骤名","note":"本步观察","value":值或null,"index":null,"node":null,"i":null,"j":null,"key":null,"val":null}]}
        最多 20 步。允许操作：
        stack: push,pop,peek; list: append,insert,delete,deleteValue,find; tree: visit,highlight;
        queue: enqueue,dequeue,peek; heap: insert,extract,peek; hash: put,get,delete;
        array: set,insert,delete,swap,get。
        note 必须描述可观察的状态变化，不得包含脚本、标签或外部链接。
        """;

    private final ModelClient model;
    private final AnimationValidator validator;
    private final AnimationRepository repository;
    private final ObjectMapper objectMapper;

    AnimationGenerationService(
        ModelClient model,
        AnimationValidator validator,
        AnimationRepository repository,
        ObjectMapper objectMapper
    ) {
        this.model = model;
        this.validator = validator;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public AnimationGenerationResponse generate(AnimationGenerationCommand command, Long userId) {
        String prompt = normalizePrompt(command.prompt());
        String preferredType = normalizeType(command.preferredType());
        String chapterId = normalizeChapterId(command.chapterId());
        String userPrompt = preferredType == null
            ? prompt
            : "优先使用 " + preferredType + " 类型生成动画：" + prompt;

        String raw;
        try {
            raw = model.complete(new ModelRequest(
                List.of(
                    new ModelMessage("system", SYSTEM_PROMPT),
                    new ModelMessage("user", userPrompt)
                ),
                0.15,
                1600
            )).content();
        } catch (ModelClientException error) {
            throw modelFailure(error);
        }

        AnimationDefinition definition = parse(raw);
        AnimationValidationResult validation = validator.validate(definition);
        if (!validation.valid()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ANIMATION_MODEL_INVALID", "模型返回的动画步骤不符合安全协议");
        }
        if (preferredType != null && !preferredType.equals(definition.type())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ANIMATION_MODEL_INVALID", "模型返回了错误的动画类型");
        }

        if (userId == null) {
            return new AnimationGenerationResponse(definition, null, false);
        }
        String payload = serialize(definition);
        String recordId = repository.save(userId, chapterId, definition, payload);
        return new AnimationGenerationResponse(definition, recordId, true);
    }

    private AnimationDefinition parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalidModelResult();
        }
        String json = raw.trim();
        Matcher fenced = FENCED_JSON.matcher(json);
        if (fenced.find()) {
            json = fenced.group(1).trim();
        } else {
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw invalidModelResult();
            }
            json = json.substring(start, end + 1);
        }
        try {
            return objectMapper.readValue(json, AnimationDefinition.class);
        } catch (Exception error) {
            throw invalidModelResult();
        }
    }

    private String serialize(AnimationDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize animation definition", error);
        }
    }

    private String normalizePrompt(String prompt) {
        String normalized = prompt == null ? "" : prompt.trim();
        if (normalized.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ANIMATION_PROMPT_REQUIRED", "动画描述不能为空");
        }
        if (normalized.length() > 2000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ANIMATION_PROMPT_TOO_LONG", "动画描述过长");
        }
        return normalized;
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ANIMATION_TYPE_UNSUPPORTED", "暂不支持该动画类型");
        }
        return normalized;
    }

    private String normalizeChapterId(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return null;
        }
        String normalized = chapterId.trim();
        if (normalized.length() > 64 || !normalized.matches("^[0-9]{2}-[a-z0-9-]+$")
                || !repository.isPublishedChapter(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ANIMATION_CHAPTER_INVALID", "章节不存在或尚未发布");
        }
        return normalized;
    }

    private ApiException modelFailure(ModelClientException error) {
        HttpStatus status = switch (error.code()) {
            case "MODEL_NOT_CONFIGURED" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "MODEL_REQUEST_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return new ApiException(status, error.code(), "动画生成服务暂时不可用");
    }

    private ApiException invalidModelResult() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ANIMATION_MODEL_INVALID", "模型未返回有效的动画数据");
    }
}
