package com.viyangle.study_tour.validation;

import com.viyangle.study_tour.pojo.CreateChatGroupRequest;
import com.viyangle.study_tour.pojo.JoinProjectRequest;
import com.viyangle.study_tour.pojo.LoginRequest;
import com.viyangle.study_tour.pojo.RegisterRequest;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;
import com.viyangle.study_tour.pojo.UpdateAccountRoleRequest;
import com.viyangle.study_tour.pojo.UpdatePasswordRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求 DTO 的 Bean Validation 规则测试。
 *
 * <p>这些注解在 controller 的 {@code @Valid} 处生效，统一由 GlobalExceptionHandler
 * 转成 400 + {code,msg,data}；这里直接校验约束，不依赖 Spring 上下文。</p>
 */
class RequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static Set<String> messages(Object bean) {
        return validator.validate(bean).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private static RegisterRequest validRegister() {
        return new RegisterRequest("小明", "13800138000", "123456", "123456", "USER", "320102");
    }

    @Nested
    @DisplayName("注册请求")
    class RegisterValidation {

        @Test
        @DisplayName("合法请求没有约束错误")
        void validRequestPasses() {
            assertTrue(messages(validRegister()).isEmpty());
        }

        @Test
        @DisplayName("手机号必须是大陆手机号")
        void phoneMustBeMainlandMobile() {
            RegisterRequest request = validRegister();
            request.setPhone("10010001003");

            assertEquals(Set.of("手机号格式不正确"), messages(request));
        }

        @Test
        @DisplayName("密码长度限制为 6-64 位")
        void passwordLengthIsBounded() {
            RegisterRequest request = validRegister();
            request.setPassword("123");

            assertEquals(Set.of("密码长度需为6-64个字符"), messages(request));
        }

        @Test
        @DisplayName("用户名不能为空白且不超过 50 字符")
        void usernameMustBePresent() {
            RegisterRequest request = validRegister();
            request.setUsername(" ");

            assertEquals(Set.of("用户名不能为空"), messages(request));
        }

        @Test
        @DisplayName("公开注册不允许 ADMIN 角色")
        void adminIsRejectedAtRegistration() {
            RegisterRequest request = validRegister();
            request.setRole("ADMIN");

            assertEquals(Set.of("注册仅支持USER/LEADER/BOTH角色"), messages(request));
        }

        @Test
        @DisplayName("地区编码不能为空")
        void regionCodeRequired() {
            RegisterRequest request = validRegister();
            request.setRegionCode(null);

            assertEquals(Set.of("regionCode不能为空"), messages(request));
        }
    }

    @Nested
    @DisplayName("登录请求")
    class LoginValidation {

        @Test
        @DisplayName("合法手机号与密码通过校验")
        void validLoginPasses() {
            assertTrue(messages(new LoginRequest("13800138000", "123456")).isEmpty());
        }

        @Test
        @DisplayName("手机号格式与密码非空同时校验")
        void invalidLoginReportsBoth() {
            Set<String> messages = messages(new LoginRequest("", ""));

            assertTrue(messages.contains("手机号不能为空"), messages.toString());
            assertTrue(messages.contains("密码不能为空"), messages.toString());
        }
    }

    @Nested
    @DisplayName("修改密码")
    class PasswordValidation {

        @Test
        @DisplayName("新密码不能为空白且不少于 6 位")
        void newPasswordRules() {
            UpdatePasswordRequest request = new UpdatePasswordRequest();
            request.setNewPassword("123");
            request.setConfirmPassword("123");

            assertEquals(Set.of("密码长度需为6-64个字符"), messages(request));
        }

        @Test
        @DisplayName("管理员重置时可以不带旧密码")
        void oldPasswordOptional() {
            UpdatePasswordRequest request = new UpdatePasswordRequest();
            request.setNewPassword("123456");
            request.setConfirmPassword("123456");

            assertTrue(messages(request).isEmpty());
        }
    }

    @Nested
    @DisplayName("项目与群聊")
    class ProjectAndChatValidation {

        @Test
        @DisplayName("参团人数必填且必须大于 0")
        void representedCountMustBePositive() {
            JoinProjectRequest request = new JoinProjectRequest();
            request.setRepresentedCount(0);

            assertEquals(Set.of("参团人数必须大于0"), messages(request));
        }

        @Test
        @DisplayName("参团人数为空时给出必填提示")
        void representedCountRequired() {
            assertEquals(Set.of("参团人数不能为空"), messages(new JoinProjectRequest()));
        }

        @Test
        @DisplayName("创建群聊必须带项目 ID")
        void projectIdRequired() {
            assertEquals(Set.of("项目ID不能为空"), messages(new CreateChatGroupRequest()));
        }

        @Test
        @DisplayName("发消息必须带群组 ID 与非空内容")
        void sendMessageRules() {
            SendChatMessageRequest request = new SendChatMessageRequest();
            request.setContent(" ");

            assertTrue(messages(request).contains("群组ID不能为空"), messages(request).toString());
            assertTrue(messages(request).contains("消息内容不能为空"), messages(request).toString());
        }

        @Test
        @DisplayName("消息类型可省略，但传了必须是 TEXT/IMAGE/SYSTEM")
        void msgTypeIsOptionalButConstrained() {
            SendChatMessageRequest valid = new SendChatMessageRequest(1L, "hi", null);
            assertTrue(messages(valid).isEmpty());

            SendChatMessageRequest invalid = new SendChatMessageRequest(1L, "hi", "VIDEO");
            assertEquals(Set.of("消息类型仅支持TEXT/IMAGE/SYSTEM"), messages(invalid));
        }
    }

    @Nested
    @DisplayName("修改角色")
    class RoleValidation {

        @Test
        @DisplayName("角色只接受 USER/LEADER/BOTH/ADMIN，大小写不敏感")
        void roleMustBeKnownValue() {
            UpdateAccountRoleRequest lower = new UpdateAccountRoleRequest();
            lower.setRole("leader");
            assertTrue(messages(lower).isEmpty());

            UpdateAccountRoleRequest invalid = new UpdateAccountRoleRequest();
            invalid.setRole("ROOT");
            assertEquals(Set.of("角色仅支持USER/LEADER/BOTH/ADMIN"), messages(invalid));
        }
    }
}
