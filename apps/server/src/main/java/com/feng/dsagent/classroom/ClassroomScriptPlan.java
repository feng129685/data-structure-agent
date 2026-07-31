package com.feng.dsagent.classroom;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record ClassroomScriptPlan(
    String lessonId,
    String title,
    List<String> objectives,
    Map<ClassroomState, JsonNode> stages,
    JsonNode question,
    boolean legacy
) {

    public ClassroomScriptPlan {
        objectives = List.copyOf(objectives);
        stages = Map.copyOf(stages);
    }

    public JsonNode stage(ClassroomState state) {
        return stages.get(state);
    }
}
