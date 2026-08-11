package com.feng.dsagent.animation;

record DsvpEvidenceResolution(
    String chapterId,
    String sourceRef,
    String matchSource
) {

    static DsvpEvidenceResolution preview() {
        return new DsvpEvidenceResolution(null, null, "NONE");
    }

    boolean persistable() {
        return chapterId != null && !chapterId.isBlank();
    }
}
