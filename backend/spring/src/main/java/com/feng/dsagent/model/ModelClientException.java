package com.feng.dsagent.model;

public final class ModelClientException extends RuntimeException {

    private final ModelErrorCode errorCode;
    private final Long consumedTokens;

    public ModelClientException(ModelErrorCode errorCode) {
        this(errorCode, null);
    }

    public ModelClientException(ModelErrorCode errorCode, long consumedTokens) {
        this(errorCode, Long.valueOf(consumedTokens));
    }

    public ModelClientException(ModelErrorCode errorCode, Long consumedTokens) {
        super(errorCode.safeMessage());
        this.errorCode = errorCode;
        this.consumedTokens = consumedTokens == null || consumedTokens < 0 ? null : consumedTokens;
    }

    public ModelErrorCode errorCode() {
        return errorCode;
    }

    public String code() {
        return errorCode.name();
    }

    /**
     * Usage reported by an upstream provider for a failed request, when available.
     */
    public Long consumedTokens() {
        return consumedTokens;
    }
}
