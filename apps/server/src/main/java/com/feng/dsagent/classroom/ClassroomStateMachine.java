package com.feng.dsagent.classroom;

import java.util.Map;
import java.util.Objects;

public final class ClassroomStateMachine {

    private static final Map<ClassroomState, ClassroomState> CONTINUE_TRANSITIONS = Map.of(
            ClassroomState.OPENING, ClassroomState.EXPLAIN,
            ClassroomState.EXPLAIN, ClassroomState.QUESTION,
            ClassroomState.QUESTION, ClassroomState.WAITING,
            ClassroomState.DISCUSS, ClassroomState.BLACKBOARD,
            ClassroomState.BLACKBOARD, ClassroomState.SUMMARY);

    public ClassroomStatus initialStatus() {
        return new ClassroomStatus(ClassroomState.OPENING, false);
    }

    public ClassroomStatus transition(ClassroomStatus current, ClassroomAction action) {
        Objects.requireNonNull(current, "current status must not be null");
        Objects.requireNonNull(action, "action must not be null");

        if (current.paused()) {
            if (action == ClassroomAction.RESUME) {
                return new ClassroomStatus(current.state(), false);
            }
            throw invalid(current, action, "classroom is paused; apply RESUME first");
        }

        if (action == ClassroomAction.RESUME) {
            throw invalid(current, action, "classroom is not paused");
        }
        if (current.state() == ClassroomState.SUMMARY) {
            throw invalid(current, action, "classroom has already finished");
        }

        return switch (action) {
            case PAUSE -> new ClassroomStatus(current.state(), true);
            case FINISH -> new ClassroomStatus(ClassroomState.SUMMARY, false);
            case ANSWER -> answer(current, true);
            case CONTINUE -> continueClassroom(current);
            case RESUME -> throw new IllegalStateException("RESUME is handled before active transitions");
        };
    }

    public ClassroomStatus answer(ClassroomStatus current, boolean requiresDiscussion) {
        Objects.requireNonNull(current, "current status must not be null");
        if (current.paused()) {
            throw invalid(current, ClassroomAction.ANSWER, "classroom is paused; apply RESUME first");
        }
        if (current.state() != ClassroomState.WAITING) {
            throw invalid(current, ClassroomAction.ANSWER, "answers are accepted only in WAITING");
        }
        return new ClassroomStatus(
            requiresDiscussion ? ClassroomState.DISCUSS : ClassroomState.BLACKBOARD,
            false
        );
    }

    private ClassroomStatus continueClassroom(ClassroomStatus current) {
        ClassroomState next = CONTINUE_TRANSITIONS.get(current.state());
        if (next != null) {
            return new ClassroomStatus(next, false);
        }
        if (current.state() == ClassroomState.WAITING) {
            throw invalid(current, ClassroomAction.CONTINUE, "apply ANSWER before continuing");
        }
        throw invalid(current, ClassroomAction.CONTINUE, "no continuation is defined");
    }

    private IllegalClassroomTransitionException invalid(
            ClassroomStatus current,
            ClassroomAction action,
            String reason) {
        return new IllegalClassroomTransitionException(current.state(), action, reason);
    }
}
