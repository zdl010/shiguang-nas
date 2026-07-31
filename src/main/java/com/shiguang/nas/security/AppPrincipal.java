package com.shiguang.nas.security;

import java.io.Serializable;

/**
 * 会话里保存的身份。刻意只放 4 个字段——不要把密码哈希、邮箱、配额之类
 * 塞进会话对象，那些每次用时从库里查即可。会话对象越小，序列化到磁盘时泄露面越小。
 */
public record AppPrincipal(long userId, String username, String displayName, String role)
        implements Serializable {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
