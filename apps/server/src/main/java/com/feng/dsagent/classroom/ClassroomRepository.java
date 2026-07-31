package com.feng.dsagent.classroom;

import java.util.List;
import java.util.Optional;

interface ClassroomRepository {

    List<ClassroomScript> findPublishedScripts(String chapterId);

    Optional<ClassroomScript> findPublishedScript(String id);

    ClassroomSessionRecord createSession(long userId, ClassroomScript script, ClassroomStatus status);

    Optional<ClassroomSessionRecord> findSession(String id, long userId);

    ClassroomSessionRecord updateSession(ClassroomSessionRecord session, ClassroomStatus status, String summary);

    void appendEvent(ClassroomEventRecord event);
}
