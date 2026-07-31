package com.feng.dsagent.model;

public enum ModelErrorCode {
    MODEL_NOT_CONFIGURED("Model service is not configured"),
    MODEL_REQUEST_TIMEOUT("Model request timed out"),
    MODEL_REQUEST_FAILED("Model request failed"),
    MODEL_UPSTREAM_ERROR("Model service returned an error"),
    MODEL_RESPONSE_READ_FAILED("Model response could not be read"),
    MODEL_RESPONSE_TOO_LARGE("Model response exceeded the configured limit"),
    MODEL_STREAM_IDLE_TIMEOUT("Model stream timed out while waiting for data"),
    MODEL_STREAM_READ_FAILED("Model stream could not be read"),
    MODEL_EMPTY_RESPONSE("Model service returned no content");

    private final String safeMessage;

    ModelErrorCode(String safeMessage) {
        this.safeMessage = safeMessage;
    }

    String safeMessage() {
        return safeMessage;
    }
}
