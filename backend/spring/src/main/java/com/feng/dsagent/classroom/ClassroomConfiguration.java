package com.feng.dsagent.classroom;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class ClassroomConfiguration {

    @Bean
    ClassroomStateMachine classroomStateMachine() {
        return new ClassroomStateMachine();
    }

    @Bean
    ClassroomScriptParser classroomScriptParser(ObjectMapper objectMapper) {
        return new ClassroomScriptParser(objectMapper);
    }

    @Bean
    ClassroomAnswerEvaluator classroomAnswerEvaluator() {
        return new ClassroomAnswerEvaluator();
    }
}
