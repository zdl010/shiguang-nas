-- 全文检索。V1 里没建，因为当时还没有媒体接口。
--
-- 用 FTS5 而不是 LIKE '%关键词%'：LIKE 的前置通配符用不上任何索引，
-- 几万条记录就会变成每次搜索都全表扫。FTS5 是 SQLite 内置的，不引额外依赖。
--
-- 分词器用 unicode61 并把中日韩表意文字全部当作分隔符处理不了——SQLite 没有内置中文分词。
-- 折中方案：搜索时前端/后端把查询串按字拆开做前缀匹配（见 MediaRepository.search），
-- 对"文件名搜索"这个场景足够，不值得为此引 jieba 之类的分词库。
CREATE VIRTUAL TABLE media_fts USING fts5(
  orig_name,
  content = 'media',
  content_rowid = 'id',
  tokenize = 'unicode61 remove_diacritics 2'
);

-- 触发器保持 media_fts 与 media 同步。
-- 外部内容表（content=）的 FTS5 必须手工维护，写错会导致搜索结果和主表对不上。
CREATE TRIGGER media_fts_ai AFTER INSERT ON media BEGIN
  INSERT INTO media_fts(rowid, orig_name) VALUES (new.id, new.orig_name);
END;

CREATE TRIGGER media_fts_ad AFTER DELETE ON media BEGIN
  INSERT INTO media_fts(media_fts, rowid, orig_name) VALUES ('delete', old.id, old.orig_name);
END;

CREATE TRIGGER media_fts_au AFTER UPDATE OF orig_name ON media BEGIN
  INSERT INTO media_fts(media_fts, rowid, orig_name) VALUES ('delete', old.id, old.orig_name);
  INSERT INTO media_fts(rowid, orig_name) VALUES (new.id, new.orig_name);
END;

-- 已有数据回填（V1 之后、V2 之前上传过东西的库）
INSERT INTO media_fts(rowid, orig_name) SELECT id, orig_name FROM media;

-- 缩略图生成失败要能重试，记下失败原因方便排查
ALTER TABLE media ADD COLUMN thumb_error TEXT;

-- 回收站保留天数之外，再补两个设置项
INSERT OR IGNORE INTO settings(k, v, updated_at) VALUES
  ('upload.max.bytes',   '5368709120', 0),
  ('site.name',          '拾光',        0);
