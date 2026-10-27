package com.viyangle.study_tour.exception;

import com.viyangle.study_tour.pojo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result handleUnauthorizedException(UnauthorizedException e) {
        log.warn("Unauthorized: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result handleForbiddenException(ForbiddenException e) {
        log.warn("Forbidden: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result handleResourceNotFoundException(ResourceNotFoundException e) {
        log.warn("Not found: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    /**
     * Bean Validation 失败时返回第一个字段错误的提示，保持 {code,msg,data} 结构。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("请求参数不合法");
        log.warn("Validation failed: {}", message);
        return Result.error(message);
    }

    /**
     * 请求体无法解析时只提示格式错误，不把 Jackson 的内部异常文本返回给客户端。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("Malformed request body: {}", e.getMessage());
        return Result.error("请求体格式不正确");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleBadRequestException(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result handleRuntimeException(RuntimeException e) {
        log.error("Runtime exception", e);
        return Result.error("系统繁忙，请稍后再试");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result handleException(Exception e) {
        log.error("System exception", e);
        return Result.error("系统繁忙，请稍后再试");
    }
}
