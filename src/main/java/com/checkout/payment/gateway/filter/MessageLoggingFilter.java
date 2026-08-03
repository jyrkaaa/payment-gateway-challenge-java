package com.checkout.payment.gateway.filter;

import com.checkout.payment.gateway.logging.MessageLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(2)
public class MessageLoggingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator") || uri.startsWith("/swagger") || uri.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, 8192);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(requestWrapper, responseWrapper);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            String reqBody = new String(requestWrapper.getContentAsByteArray(), StandardCharsets.UTF_8).strip();
            String resBody = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8).strip();
            responseWrapper.copyBodyToResponse();
            MessageLogger.logInbound(
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                durationMs,
                reqBody.isEmpty() ? null : reqBody,
                resBody.isEmpty() ? null : resBody,
                extractHeaders(request)
            );
        }
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) return headers;
        for (String name : Collections.list(headerNames)) {
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
