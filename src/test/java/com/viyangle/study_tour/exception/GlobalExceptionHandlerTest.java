package com.viyangle.study_tour.exception;

import com.viyangle.study_tour.pojo.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 统一异常处理器的错误体与状态码约定。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("Bean Validation 失败返回第一个字段提示，状态码 400")
    void validationFailureReturnsFirstFieldMessage() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "phone", "手机号格式不正确"));
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("sample", String.class);
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);

        Result result = handler.handleValidationException(exception);

        assertEquals(0, result.getCode());
        assertEquals("手机号格式不正确", result.getMsg());
        assertEquals(HttpStatus.BAD_REQUEST,
                methodOf("handleValidationException", MethodArgumentNotValidException.class)
                        .getAnnotation(ResponseStatus.class).value());
    }

    @Test
    @DisplayName("请求体解析失败只返回“请求体格式不正确”，不泄露 Jackson 细节")
    void malformedBodyReturnsFriendlyMessage() {
        Result result = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("Unexpected character ('u')"));

        assertEquals("请求体格式不正确", result.getMsg());
    }

    @Test
    @DisplayName("业务校验异常保留原始提示")
    void illegalArgumentKeepsMessage() {
        Result result = handler.handleBadRequestException(new IllegalArgumentException("参团人数必须大于0"));

        assertEquals("参团人数必须大于0", result.getMsg());
    }

    private static Method methodOf(String name, Class<?> parameterType) throws NoSuchMethodException {
        return GlobalExceptionHandler.class.getMethod(name, parameterType);
    }

    @SuppressWarnings("unused")
    private void sample(String value) {
        // 仅用于构造 MethodParameter
    }
}
