package com.jd.diviner.ai.rec.odin.demo.web.controller;

import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.HandlerInterceptor;

public class SkillStreamLifecycleInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) return true;
        long start = System.nanoTime();
        ExecutionLog context = ExecutionLog.current().with("channel", "SERVLET");
        WebAsyncUtils.getAsyncManager(request).registerDeferredResultInterceptor(getClass().getName(),
                new DeferredResultProcessingInterceptor() {
                    private ExecutionLog log() {
                        return context.with("requestId", response.getHeader("X-Request-Id"));
                    }

                    @Override
                    public <T> void beforeConcurrentHandling(NativeWebRequest webRequest, DeferredResult<T> result) {
                        log().info("SERVLET_ASYNC_STARTED");
                    }

                    @Override
                    public <T> boolean handleTimeout(NativeWebRequest webRequest, DeferredResult<T> result) {
                        log().info("SERVLET_ASYNC_TIMEOUT", "elapsedMs", ExecutionLog.elapsed(start), "committed", response.isCommitted());
                        return true;
                    }

                    @Override
                    public <T> boolean handleError(NativeWebRequest webRequest, DeferredResult<T> result, Throwable error) {
                        log().failure("SERVLET_ASYNC_ERROR", error, "elapsedMs", ExecutionLog.elapsed(start), "committed", response.isCommitted());
                        return true;
                    }

                    @Override
                    public <T> void postProcess(NativeWebRequest webRequest, DeferredResult<T> result, Object concurrentResult) {
                        if (concurrentResult instanceof Throwable error) {
                            log().failure("SERVLET_ASYNC_RESULT_ERROR", error, "elapsedMs", ExecutionLog.elapsed(start));
                        }
                    }

                    @Override
                    public <T> void afterCompletion(NativeWebRequest webRequest, DeferredResult<T> result) {
                        log().info("SERVLET_ASYNC_COMPLETED", "elapsedMs", ExecutionLog.elapsed(start),
                                "httpStatus", response.getStatus(), "committed", response.isCommitted());
                    }
                });
        return true;
    }
}
