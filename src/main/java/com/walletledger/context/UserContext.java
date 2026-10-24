package com.walletledger.context;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

public final class UserContext {

    private static final String USER_ID_ATTRIBUTE =
            UserContext.class.getName() + ".USER_ID";

    private UserContext() {
    }

    public static void setUserId(HttpServletRequest request, UUID userId) {
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
    }

    public static UUID getRequiredUserId(HttpServletRequest request) {
        Object value = request.getAttribute(USER_ID_ATTRIBUTE);

        if (value instanceof UUID userId) {
            return userId;
        }

        throw new IllegalStateException(
                "No user identity is available for this request."
        );
    }
}