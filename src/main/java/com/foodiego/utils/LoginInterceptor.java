package com.foodiego.utils;

import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (handler instanceof ResourceHttpRequestHandler) {
            return true;
        }
        // Browsing is public; writes still require authentication.
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) &&
                (path.startsWith("/shop/") || path.startsWith("/shop-type/") ||
                        path.startsWith("/voucher/list/") || path.equals("/meal-batches") || path.equals("/meal-batches/") ||
                        path.matches("/campus-windows(?:/[0-9]{1,19})?/?"))) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
