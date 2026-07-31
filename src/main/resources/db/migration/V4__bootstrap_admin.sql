-- 首启模型改为「自动创建 admin + 强制改密」。
--
-- 之前是「第一个注册的账号即管理员」，那个模型有个抢注窗口：谁先打开页面谁就是管理员。
-- 现在改成开机就有一个固定的 admin，配一个众所周知的初始密码，并强制在首次登录后修改。
--
-- 这两种做法的风险窗口其实是同一个（首启后到管理员把密码定下来之间），
-- 区别在于现在这个窗口里攻击者拿到的是一个**必须立刻改密**的账号：
-- must_change_password 为 1 时，除了改密接口，其他 API 全部拒绝（见 MustChangePasswordFilter），
-- 所以他既看不到照片也改不了设置，而真正的机主只要先登录一次就能把密码定死。

ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0;
