package com.feng.dsagent.knowledge;

final class KnowledgeEligibilitySql {

    static final String EFFECTIVE_LICENSE_SCOPE =
        "CASE WHEN k.resource_id IS NULL THEN k.license_scope ELSE r.license_scope END";

    static final String REVIEWED_SOURCE_CHAIN = """
        k.review_status = 'VERIFIED'
          AND NULLIF(TRIM(k.source_path), '') IS NOT NULL
          AND (k.chapter_id IS NULL OR kc.status = 'PUBLISHED')
          AND (k.resource_id IS NULL OR (
            r.review_status = 'VERIFIED'
            AND rc.status = 'PUBLISHED'
            AND NULLIF(TRIM(r.source_name), '') IS NOT NULL
            AND NULLIF(TRIM(r.version_label), '') IS NOT NULL
          ))
        """;

    private KnowledgeEligibilitySql() {
    }
}
