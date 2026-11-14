package com.foodiego.utils;

import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Management writes are denied unless the user is explicitly configured. */
public class AdminInterceptor implements HandlerInterceptor {
    private final AdministratorAccess administrators;
    private final boolean allowPublicReads;

    public AdminInterceptor(AdministratorAccess administrators, boolean allowPublicReads) {
        this.administrators = administrators;
        this.allowPublicReads = allowPublicReads;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (allowPublicReads && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        if (!administrators.allows(UserHolder.getUser().getId())) {
            response.setStatus(403);
            return false;
        }
        return true;
    }
}
