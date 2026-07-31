package com.feng.dsagent.model;

public final class ModelClientException extends RuntimeException {

    private final ModelErrorCode errorCode;

    public ModelClientException(ModelErrorCode errorCode) {
        super(errorCode.safeMessage());
        this.errorCode = errorCode;
    }

    public ModelErrorCode errorCode() {
        return errorCode;
    }

    public String code() {
        return errorCode.name();
    }
}
