package com.hmdp.exception;

/**
 * 限流异常：被 RateLimitAspect 抛出，由 WebExceptionAdvice 转成友好提示。
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
