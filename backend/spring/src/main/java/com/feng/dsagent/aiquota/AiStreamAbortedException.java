package com.feng.dsagent.aiquota;

/**
 * Signals that the downstream SSE connection closed while an upstream model stream was active.
 */
public final class AiStreamAbortedException extends RuntimeException {

    public AiStreamAbortedException() {
        this(null);
    }

    public AiStreamAbortedException(Throwable cause) {
        super("AI stream client disconnected", cause);
    }
}
