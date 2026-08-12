package com.feng.dsagent.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public final class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).body(new ApiError(
            error.code(),
            error.getMessage(),
            requestId(request),
            List.of()
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(
            "AUTH_FORBIDDEN",
            "当前账号无权执行该操作",
            requestId(request),
            List.of()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException error, HttpServletRequest request) {
        List<String> details = error.getBindingResult().getFieldErrors().stream()
            .map(this::formatFieldError)
            .toList();
        return ResponseEntity.badRequest().body(new ApiError(
            "VALIDATION_FAILED",
            "请求参数不符合要求",
            requestId(request),
            details
        ));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleHandlerMethodValidation(
        HandlerMethodValidationException error,
        HttpServletRequest request
    ) {
        List<String> details = error.getParameterValidationResults().stream()
            .flatMap(result -> result.getResolvableErrors().stream()
                .map(resolvable -> formatParameterError(result.getMethodParameter().getParameterName(), resolvable)))
            .toList();
        return validationError(request, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(
        ConstraintViolationException error,
        HttpServletRequest request
    ) {
        List<String> details = error.getConstraintViolations().stream()
            .map(this::formatConstraintViolation)
            .toList();
        return validationError(request, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(
        HttpMessageNotReadableException error,
        HttpServletRequest request
    ) {
        return ResponseEntity.badRequest().body(new ApiError(
            "INVALID_REQUEST_BODY",
            "请求内容不是有效的 JSON",
            requestId(request),
            List.of()
        ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNotFound(NoResourceFoundException error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
            "RESOURCE_NOT_FOUND",
            "请求的资源不存在",
            requestId(request),
            List.of()
        ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
            "INTERNAL_ERROR",
            "服务器暂时无法处理该请求",
            requestId(request),
            List.of()
        ));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + (error.getDefaultMessage() == null ? "参数无效" : error.getDefaultMessage());
    }

    private ResponseEntity<ApiError> validationError(HttpServletRequest request, List<String> details) {
        return ResponseEntity.badRequest().body(new ApiError(
            "VALIDATION_FAILED",
            "请求参数不符合要求",
            requestId(request),
            details
        ));
    }

    private String formatParameterError(String parameterName, MessageSourceResolvable error) {
        String name = parameterName == null || parameterName.isBlank() ? "参数" : parameterName;
        return name + ": " + (error.getDefaultMessage() == null ? "参数无效" : error.getDefaultMessage());
    }

    private String formatConstraintViolation(ConstraintViolation<?> error) {
        String path = error.getPropertyPath() == null ? "参数" : error.getPropertyPath().toString();
        return path + ": " + (error.getMessage() == null ? "参数无效" : error.getMessage());
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? "" : value.toString();
    }
}
