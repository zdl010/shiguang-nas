-- 去掉邀请码与两步验证。
--
-- 邀请码：管理员现在可以直接建号（AdminUserController），邀请码是通向同一个目的地的
-- 第二条路，而每多一条路就多一份要维护和要防护的表面。一并去掉的还有公开注册接口——
-- 没有邀请码之后前端根本没有注册入口，留着 /api/auth/register 就是一个没人用、
-- 但攻击者可以拿来爆破的开放端点。
--
-- 两步验证：产品要求去掉。
--
-- 这两张表/列里的数据一起删干净，不留半截。留着不用的列迟早会有人以为它还在生效。
DROP TABLE IF EXISTS invite_codes;

ALTER TABLE users DROP COLUMN totp_secret;

DELETE FROM settings WHERE k = 'registration.open';
