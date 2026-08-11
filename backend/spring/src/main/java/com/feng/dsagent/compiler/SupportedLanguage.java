package com.feng.dsagent.compiler;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

enum SupportedLanguage {
    C("c", "gcc", "main.c"),
    PYTHON("python", "python", "main.py");

    private final String apiName;
    private final String pistonRuntime;
    private final String fileName;

    SupportedLanguage(String apiName, String pistonRuntime, String fileName) {
        this.apiName = apiName;
        this.pistonRuntime = pistonRuntime;
        this.fileName = fileName;
    }

    String apiName() {
        return apiName;
    }

    String pistonRuntime() {
        return pistonRuntime;
    }

    String fileName() {
        return fileName;
    }

    static Optional<SupportedLanguage> fromApiName(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(language -> language.apiName.equals(normalized))
            .findFirst();
    }
}
