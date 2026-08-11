package com.feng.dsagent.compiler;

interface CompilerGateway {

    CompilerExecution execute(SupportedLanguage language, String code, String stdin);
}
