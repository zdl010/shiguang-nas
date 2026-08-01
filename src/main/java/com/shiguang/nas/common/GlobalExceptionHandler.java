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
        return error(ex.status(), ex.getMessage());
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
        return error(HttpStatus.FORBIDDEN, "没有权限");
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
        return error(HttpStatus.BAD_REQUEST, "请求参数不合法");
    }

    /** 上传的文件超过 multipart 限制。默认会变成 500，对用户毫无指导意义。 */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "分片过大，请刷新页面重试");
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
        return error(HttpStatus.NOT_FOUND, "接口不存在");
    }

    /** 方法不对（比如对只支持 POST 的接口发 GET）。同样不该记成服务端错误。 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed() {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持");
    }

    /**
     * 客户端提前断开连接。
     *
     * <p><b>这不是故障，不该按错误记。</b>浏览器刷新、切走页面、网络抖动都会产生它；
     * 视频拖进度条更是每拖一次就中断一个 Range 请求——按 ERROR 记完整堆栈的话，
     * 正常使用几分钟就能把日志刷满，真正的错误反而被淹掉。
     *
     * <p>返回 void 是刻意的：连接已经没了，再往里写 500 的响应体没有意义，
     * 只会在 Tomcat 里再引发一次写失败。
     *
     * <p>按<b>类型</b>而不是按消息文本判断：这个异常的消息由操作系统给出，
     * 中文 Windows 上是"你的主机中的软件中止了一个已建立的连接"，
     * 英文环境是 "Broken pipe" 或 "Connection reset by peer"，匹配文本必然漏。
     */
    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public void handleClientAbort(Exception ex, HttpServletRequest request) {
        log.debug("客户端提前断开: {} {}", request.getMethod(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex,
                                                                HttpServletRequest request) {
        // 断连也可能被包在别的异常里（比如流式写出时的 IOException 链），一并降级
        if (isClientAbort(ex)) {
            log.debug("客户端提前断开: {} {}", request.getMethod(), request.getRequestURI());
            return null;
        }
        log.error("请求处理失败: {} {}", request.getMethod(), request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
    }


    /**
     * 统一构造错误响应。
     *
     * <p><b>必须显式写 Content-Type。</b>不写的话，Spring 会沿用响应上已有的类型——
     * 而静态资源出错时那个类型已经是 {@code text/javascript} 或 {@code image/jpeg} 了，
     * 没有任何消息转换器能把 Map 按那种类型写出去，于是异常处理器<b>自己</b>抛出
     * {@code No converter for [ImmutableCollections$Map1] with preset Content-Type}，
     * 真正的错误反而被这条二次失败盖住。
     */
    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }

    private static boolean isClientAbort(Throwable ex) {
        for (Throwable t = ex; t != null && t != t.getCause(); t = t.getCause()) {
            String name = t.getClass().getName();
            if (name.equals("org.apache.catalina.connector.ClientAbortException")
                    || name.equals("org.springframework.web.context.request.async.AsyncRequestNotUsableException")) {
                return true;
            }
        }
        return false;
    }
}
