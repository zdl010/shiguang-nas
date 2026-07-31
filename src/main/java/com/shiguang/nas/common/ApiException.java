package com.shiguang.nas.common;

import org.springframework.http.HttpStatus;

/** 可以安全地把 message 原样返回给客户端的业务异常。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
