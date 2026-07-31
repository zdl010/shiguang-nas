-- 拾光 NAS 初始 schema
-- 约定：所有时间戳都是 epoch millis（INTEGER），不用 SQLite 的日期字符串，避免时区歧义。

CREATE TABLE users (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  username       TEXT    NOT NULL COLLATE NOCASE,
  display_name   TEXT,
  password_hash  TEXT    NOT NULL,
  role           TEXT    NOT NULL DEFAULT 'USER',
  status         TEXT    NOT NULL DEFAULT 'ACTIVE',
  totp_secret    TEXT,
  failed_count   INTEGER NOT NULL DEFAULT 0,
  locked_until   INTEGER,
  quota_bytes    INTEGER,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  last_login_at  INTEGER
);

-- 需求第 8 条：用户名唯一。COLLATE NOCASE 让 Alice 和 alice 视为同一人，
-- 避免出现视觉上无法区分的仿冒账号。
CREATE UNIQUE INDEX ux_users_username ON users(username);

-- 已登录设备列表，支持"查看在线设备 / 强制下线"。
-- token_hash 存的是会话令牌的 SHA-256，库被拖走也无法反推出可用令牌。
CREATE TABLE user_sessions (
  id            TEXT    PRIMARY KEY,
  user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash    TEXT    NOT NULL,
  user_agent    TEXT,
  ip            TEXT,
  created_at    INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL,
  revoked_at    INTEGER
);

CREATE INDEX ix_sessions_user ON user_sessions(user_id, revoked_at);
CREATE INDEX ix_sessions_expiry ON user_sessions(expires_at);

CREATE TABLE media (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_id     INTEGER NOT NULL REFERENCES users(id),
  sha256       TEXT    NOT NULL,
  kind         TEXT    NOT NULL,
  mime         TEXT    NOT NULL,
  ext          TEXT    NOT NULL,
  orig_name    TEXT    NOT NULL,
  size_bytes   INTEGER NOT NULL,
  width        INTEGER,
  height       INTEGER,
  duration_ms  INTEGER,
  taken_at     INTEGER,
  created_at   INTEGER NOT NULL,
  rel_path     TEXT    NOT NULL,
  thumb_state  TEXT    NOT NULL DEFAULT 'PENDING',
  playable     INTEGER NOT NULL DEFAULT 1,
  starred      INTEGER NOT NULL DEFAULT 0,
  deleted_at   INTEGER
);

-- 内容寻址去重：同一用户重复上传同一文件直接秒传。
-- 按 owner 隔离而不是全局唯一，避免"A 上传过的文件 B 也能秒传"造成的存在性泄露。
CREATE UNIQUE INDEX ux_media_owner_sha ON media(owner_id, sha256);

-- feed 游标分页的命门索引，(taken_at DESC, id DESC) 必须和查询的 ORDER BY 完全一致
CREATE INDEX ix_media_feed  ON media(owner_id, deleted_at, taken_at DESC, id DESC);
CREATE INDEX ix_media_kind  ON media(owner_id, kind, deleted_at, taken_at DESC, id DESC);
CREATE INDEX ix_media_star  ON media(owner_id, starred, deleted_at, taken_at DESC, id DESC);
CREATE INDEX ix_media_trash ON media(owner_id, deleted_at);
CREATE INDEX ix_media_thumb ON media(thumb_state) WHERE thumb_state = 'PENDING';

CREATE TABLE media_exif (
  media_id  INTEGER PRIMARY KEY REFERENCES media(id) ON DELETE CASCADE,
  camera    TEXT,
  lens      TEXT,
  iso       INTEGER,
  aperture  TEXT,
  shutter   TEXT,
  focal_len TEXT,
  lat       REAL,
  lon       REAL,
  raw_json  TEXT
);

CREATE TABLE upload_sessions (
  id           TEXT    PRIMARY KEY,
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sha256       TEXT    NOT NULL,
  orig_name    TEXT    NOT NULL,
  total_size   INTEGER NOT NULL,
  chunk_size   INTEGER NOT NULL,
  chunk_total  INTEGER NOT NULL,
  received     TEXT    NOT NULL DEFAULT '',
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL
);

CREATE INDEX ix_upload_expiry ON upload_sessions(expires_at);

CREATE TABLE albums (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name           TEXT    NOT NULL,
  cover_media_id INTEGER REFERENCES media(id) ON DELETE SET NULL,
  created_at     INTEGER NOT NULL
);

CREATE TABLE album_items (
  album_id INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
  media_id INTEGER NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  sort_no  INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (album_id, media_id)
);

-- 默认关闭开放注册，改为邀请码。降低"局域网里谁都能注册一个号"的风险。
CREATE TABLE invite_codes (
  code       TEXT PRIMARY KEY,
  created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
  used_by    INTEGER REFERENCES users(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER,
  used_at    INTEGER
);

-- 安全审计：登录、改密、删除、清空回收站、改存储目录都要留痕
CREATE TABLE audit_log (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER,
  username   TEXT,
  action     TEXT    NOT NULL,
  target     TEXT,
  outcome    TEXT    NOT NULL DEFAULT 'SUCCESS',
  ip         TEXT,
  user_agent TEXT,
  detail     TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX ix_audit_time ON audit_log(created_at DESC);
CREATE INDEX ix_audit_action ON audit_log(action, created_at DESC);

CREATE TABLE settings (
  k          TEXT PRIMARY KEY,
  v          TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

-- 默认安全姿态：开放注册关闭，回收站保留 30 天
INSERT INTO settings(k, v, updated_at) VALUES
  ('registration.open',   'false', 0),
  ('trash.retention.days', '30',   0);
