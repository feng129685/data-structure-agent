package com.feng.dsagent.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClassroomStateMachineTest {

    private final ClassroomStateMachine stateMachine = new ClassroomStateMachine();

    @Test
    void followsTheRequiredTeachingSequence() {
        ClassroomStatus status = stateMachine.initialStatus();

        assertThat(status).isEqualTo(new ClassroomStatus(ClassroomState.OPENING, false));
        status = stateMachine.transition(status, ClassroomAction.CONTINUE);
        assertThat(status.state()).isEqualTo(ClassroomState.EXPLAIN);
        status = stateMachine.transition(status, ClassroomAction.CONTINUE);
        assertThat(status.state()).isEqualTo(ClassroomState.QUESTION);
        status = stateMachine.transition(status, ClassroomAction.CONTINUE);
        assertThat(status.state()).isEqualTo(ClassroomState.WAITING);
        status = stateMachine.transition(status, ClassroomAction.ANSWER);
        assertThat(status.state()).isEqualTo(ClassroomState.DISCUSS);
        status = stateMachine.transition(status, ClassroomAction.CONTINUE);
        assertThat(status.state()).isEqualTo(ClassroomState.BLACKBOARD);
        status = stateMachine.transition(status, ClassroomAction.CONTINUE);

        assertThat(status).isEqualTo(new ClassroomStatus(ClassroomState.SUMMARY, false));
    }

    @Test
    void pauseAndResumePreserveTheTeachingState() {
        ClassroomStatus explaining = new ClassroomStatus(ClassroomState.EXPLAIN, false);

        ClassroomStatus paused = stateMachine.transition(explaining, ClassroomAction.PAUSE);
        ClassroomStatus resumed = stateMachine.transition(paused, ClassroomAction.RESUME);

        assertThat(paused).isEqualTo(new ClassroomStatus(ClassroomState.EXPLAIN, true));
        assertThat(resumed).isEqualTo(explaining);
    }

    @Test
    void finishMovesAnActiveSessionToSummary() {
        ClassroomStatus waiting = new ClassroomStatus(ClassroomState.WAITING, false);

        ClassroomStatus finished = stateMachine.transition(waiting, ClassroomAction.FINISH);

        assertThat(finished).isEqualTo(new ClassroomStatus(ClassroomState.SUMMARY, false));
    }

    @Test
    void rejectsAnswerOutsideTheWaitingStateWithAUsefulMessage() {
        ClassroomStatus explaining = new ClassroomStatus(ClassroomState.EXPLAIN, false);

        assertThatThrownBy(() -> stateMachine.transition(explaining, ClassroomAction.ANSWER))
                .isInstanceOf(IllegalClassroomTransitionException.class)
                .hasMessageContaining("ANSWER")
                .hasMessageContaining("EXPLAIN");
    }

    @Test
    void rejectsActionsWhilePausedUntilTheSessionIsResumed() {
        ClassroomStatus paused = new ClassroomStatus(ClassroomState.QUESTION, true);

        assertThatThrownBy(() -> stateMachine.transition(paused, ClassroomAction.CONTINUE))
                .isInstanceOf(IllegalClassroomTransitionException.class)
                .hasMessageContaining("paused")
                .hasMessageContaining("RESUME");
    }

    @Test
    void rejectsResumeForAnActiveSession() {
        ClassroomStatus opening = stateMachine.initialStatus();

        assertThatThrownBy(() -> stateMachine.transition(opening, ClassroomAction.RESUME))
                .isInstanceOf(IllegalClassroomTransitionException.class)
                .hasMessageContaining("RESUME")
                .hasMessageContaining("not paused");
    }

    @Test
    void rejectsContinueWhileWaitingForAnAnswer() {
        ClassroomStatus waiting = new ClassroomStatus(ClassroomState.WAITING, false);

        assertThatThrownBy(() -> stateMachine.transition(waiting, ClassroomAction.CONTINUE))
                .isInstanceOf(IllegalClassroomTransitionException.class)
                .hasMessageContaining("CONTINUE")
                .hasMessageContaining("WAITING")
                .hasMessageContaining("ANSWER");
    }

    @Test
    void rejectsFurtherActionsAfterSummary() {
        ClassroomStatus summary = new ClassroomStatus(ClassroomState.SUMMARY, false);

        assertThatThrownBy(() -> stateMachine.transition(summary, ClassroomAction.CONTINUE))
                .isInstanceOf(IllegalClassroomTransitionException.class)
                .hasMessageContaining("SUMMARY")
                .hasMessageContaining("finished");
    }
}
