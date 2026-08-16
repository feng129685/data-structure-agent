package com.feng.dsagent.mail;

import com.feng.dsagent.common.ApiException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

final class MailTemplateRenderer {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}")
;
    private static final Set<String> ALLOWED_VARIABLES = Set.of("site_name", "code", "expires_minutes");

    private MailTemplateRenderer() {
    }

    static void validate(String subject, String html) {
        if (subject == null || subject.isBlank() || subject.contains("\r") || subject.contains("\n")) {
            throw invalid("MAIL_SUBJECT_INVALID", "邮件主题不能为空且不能包含换行");
        }
        if (html == null || html.isBlank() || html.length() > 100_000) {
            throw invalid("MAIL_TEMPLATE_INVALID", "邮件 HTML 模板不能为空且长度不能超过 100000");
        }
        if (containsUnsafeMarkup(html)) {
            throw invalid("MAIL_TEMPLATE_UNSAFE_MARKUP", "邮件模板不能包含脚本或事件处理器");
        }
        validateVariables(subject);
        validateVariables(html);
        if (!containsVariable(html, "code")) {
            throw invalid("MAIL_TEMPLATE_CODE_REQUIRED", "邮件模板必须包含 {{code}} 变量");
        }
    }

    static String render(String source, String siteName, String code, int expiresMinutes) {
        Matcher matcher = VARIABLE.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "site_name" -> siteName;
                case "code" -> code;
                case "expires_minutes" -> Integer.toString(expiresMinutes);
                default -> matcher.group();
            };
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static String renderHtml(String source, String siteName, String code, int expiresMinutes) {
        return render(source, escape(siteName), escape(code), expiresMinutes);
    }

    private static void validateVariables(String value) {
        Matcher matcher = VARIABLE.matcher(value);
        while (matcher.find()) {
            if (!ALLOWED_VARIABLES.contains(matcher.group(1))) {
                throw invalid("MAIL_TEMPLATE_VARIABLE_INVALID", "邮件模板包含未允许的变量");
            }
        }
    }

    private static boolean containsVariable(String value, String variable) {
        Matcher matcher = VARIABLE.matcher(value);
        while (matcher.find()) {
            if (variable.equals(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUnsafeMarkup(String html) {
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("<script") || lower.matches("(?s).*\\bon[a-z0-9_-]+\\s*=.*");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
