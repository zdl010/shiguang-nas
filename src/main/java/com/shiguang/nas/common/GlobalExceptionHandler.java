package com.shiguang.nas.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一错误响应。
 *
 * <p>安全要点：<b>只有 {@link ApiException} 的 message 会回给客户端</b>，
 * 其余异常一律折叠成一句通用文案。堆栈、SQL 片段、文件路径、类名对攻击者都是情报，
 * 绝不能出现在响应体里——它们只进服务端日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * 方法级鉴权（{@code @PreAuthorize}）拒绝时抛的异常。
     *
     * <p>必须显式处理：这个异常是在控制器方法调用时抛出的，会先被 Spring MVC 的
     * 异常解析器接住，根本走不到过滤器链上的 accessDeniedHandler。不加这个处理器的话，
     * 越权访问会返回 500 —— 既让前端无法区分"没权限"和"服务挂了"，
     * 也会在日志里刷出一堆吓人的 ERROR 堆栈。
     */
    @ExceptionHandler(org.springframework.security.authorization.AuthorizationDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "没有权限"));
    }

    /**
     * 请求体缺失或格式不对。这是客户端的问题，不该记成服务端 ERROR，
     * 也不该返回 500 让前端以为是服务挂了。
     */
    @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        log.debug("请求参数不合法: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "请求参数不合法"));
    }

    /** 上传的文件超过 multipart 限制。默认会变成 500，对用户毫无指导意义。 */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "分片过大，请刷新页面重试"));
    }

    /**
     * 访问了不存在的路径。
     *
     * <p>SpaConfig 把所有非 API 路径回落到 index.html，`api/` 开头的则返回 null，
     * 于是 Spring 抛出 NoResourceFoundException。不显式处理的话它会被下面的
     * 兜底处理器接住变成 500——前端分不清"接口没了"和"服务挂了"，
     * 日志里还会为每个探测请求刷一条 ERROR 堆栈。
     */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "接口不存在"));
    }

    /** 方法不对（比如对只支持 POST 的接口发 GET）。同样不该记成服务端错误。 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("error", "请求方法不支持"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex,
                                                                HttpServletRequest request) {
        log.error("请求处理失败: {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "服务器内部错误"));
    }
}
