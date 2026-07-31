package com.feng.dsagent.classroom;

import com.feng.dsagent.common.ApiException;
import com.feng.dsagent.learning.LearningEventCommand;
import com.feng.dsagent.learning.LearningEventService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class ClassroomService {

    private final ClassroomRepository repository;
    private final ClassroomStateMachine stateMachine;
    private final ClassroomScriptParser scriptParser;
    private final ClassroomAnswerEvaluator answerEvaluator;
    private final LearningEventService learningEvents;

    ClassroomService(
        ClassroomRepository repository,
        ClassroomStateMachine stateMachine,
        ClassroomScriptParser scriptParser,
        ClassroomAnswerEvaluator answerEvaluator,
        LearningEventService learningEvents
    ) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.scriptParser = scriptParser;
        this.answerEvaluator = answerEvaluator;
        this.learningEvents = learningEvents;
    }

    public List<ClassroomScriptSummary> scripts(String chapterId) {
        return repository.findPublishedScripts(chapterId).stream()
            .map(ClassroomScriptSummary::from)
            .toList();
    }

    @Transactional
    public ClassroomSessionView create(long userId, String scriptId) {
        ClassroomScript script = repository.findPublishedScript(scriptId)
            .orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "CLASSROOM_SCRIPT_NOT_FOUND",
                "课堂脚本不存在或尚未发布"
            ));
        scriptParser.parse(script.scriptJson());
        ClassroomSessionRecord session = repository.createSession(userId, script, stateMachine.initialStatus());
        return view(session, null);
    }

    public ClassroomSessionView get(long userId, String sessionId) {
        return view(session(userId, sessionId), null);
    }

    @Transactional
    public ClassroomSessionView apply(long userId, String sessionId, ClassroomAction action, String content) {
        ClassroomSessionRecord current = session(userId, sessionId);
        String normalizedContent = normalizeContent(content);
        ClassroomAnswerEvaluation evaluation = null;
        ClassroomStatus next;
        try {
            ClassroomStatus status = new ClassroomStatus(current.state(), current.paused());
            if (action == ClassroomAction.ANSWER) {
                if (normalizedContent.isBlank()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "CLASSROOM_ANSWER_REQUIRED", "请先填写课堂回答");
                }
                ClassroomScriptPlan plan = scriptParser.parse(current.scriptJson());
                evaluation = answerEvaluator.evaluate(plan.question(), normalizedContent);
                next = stateMachine.answer(status, evaluation.requiresDiscussion());
            } else {
                next = stateMachine.transition(status, action);
            }
        } catch (IllegalClassroomTransitionException error) {
            throw new ApiException(HttpStatus.CONFLICT, "CLASSROOM_ACTION_INVALID", "当前课堂阶段不能执行该操作");
        }

        String summary = next.state() == ClassroomState.SUMMARY && !normalizedContent.isBlank()
            ? normalizedContent
            : current.summary();
        ClassroomSessionRecord updated = repository.updateSession(current, next, summary);
        repository.appendEvent(new ClassroomEventRecord(
            sessionId,
            action,
            normalizedContent,
            current.state(),
            next.state(),
            evaluation
        ));
        if (evaluation != null) {
            tools.jackson.databind.node.ObjectNode payload =
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            payload.put("answer", normalizedContent);
            payload.put("status", evaluation.status().name());
            if (evaluation.misconception() != null) {
                payload.put("misconception", evaluation.misconception());
            }
            if (evaluation.feedback() != null) {
                payload.put("feedback", evaluation.feedback());
            }
            learningEvents.record(userId, new LearningEventCommand(
                "CLASSROOM_ANSWER",
                current.chapterId(),
                current.id(),
                payload
            ));
        }
        return view(updated, evaluation);
    }

    private ClassroomSessionRecord session(long userId, String sessionId) {
        return repository.findSession(sessionId, userId)
            .orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "CLASSROOM_SESSION_NOT_FOUND",
                "课堂会话不存在"
            ));
    }

    private ClassroomSessionView view(
        ClassroomSessionRecord session,
        ClassroomAnswerEvaluation answerEvaluation
    ) {
        return new ClassroomSessionView(
            session.id(),
            session.userId(),
            session.scriptId(),
            session.state(),
            session.paused(),
            session.summary(),
            stage(session.scriptJson(), session.state()),
            answerEvaluation
        );
    }

    private JsonNode stage(String scriptJson, ClassroomState state) {
        return scriptParser.parse(scriptJson).stage(state);
    }

    private String normalizeContent(String content) {
        String normalized = content == null ? "" : content.trim();
        return normalized.length() <= 4000 ? normalized : normalized.substring(0, 4000);
    }
}
