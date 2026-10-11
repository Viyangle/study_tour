package com.viyangle.study_tour.utils;

import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.UnauthorizedException;

/** Derives private AI storage keys exclusively from the authenticated account. */
public final class AiConversationScope {
    private AiConversationScope() {
    }

    public static Long requireAccount(Long requestedAccountId) {
        Long accountId = SecurityContextUtil.currentAccountId();
        if (accountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        if (requestedAccountId != null && !accountId.equals(requestedAccountId)) {
            throw new ForbiddenException("不能使用其他账号的AI会话或偏好");
        }
        return accountId;
    }

    public static String storageId(String memoryId) {
        Long accountId = requireAccount(null);
        if (memoryId == null || memoryId.isBlank() || memoryId.length() > 128) {
            throw new IllegalArgumentException("memoryId不能为空且不能超过128个字符");
        }
        return "account:" + accountId + ":" + memoryId;
    }
}
