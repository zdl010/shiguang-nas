-- 把 media_fts 的分词器从 unicode61 换成 trigram。
--
-- V2 用 unicode61 是个错误，实测暴露了出来：SQLite 没有中文分词器，
-- unicode61 会把连续的汉字整串当成**一个 token**，于是
--   搜"生日" → 能命中"生日聚会.mp4"（前缀匹配成功）
--   搜"日落" → 命中不了"海边日落.jpg"（不是前缀）
-- 而"搜文件名里的任意几个字"恰恰是用户最自然的用法。
--
-- trigram 把文本切成所有连续 3 字符片段，因此支持任意位置的子串匹配，
-- 中英文都适用。代价有两个，都可以接受：
--   1. 索引体积明显变大（每个字符大约产生一个 token）。文件名很短，总量可忽略。
--   2. 查询串必须至少 3 个字符，1-2 个字的搜索匹配不到任何 trigram。
--      短查询由 MediaRepository 回退成 LIKE 扫描——个人相册的数据量下，
--      对几万行做一次 LIKE 只要几十毫秒，不值得为它再引一套分词方案。

DROP TRIGGER IF EXISTS media_fts_ai;
DROP TRIGGER IF EXISTS media_fts_ad;
DROP TRIGGER IF EXISTS media_fts_au;
DROP TABLE IF EXISTS media_fts;

CREATE VIRTUAL TABLE media_fts USING fts5(
  orig_name,
  content = 'media',
  content_rowid = 'id',
  tokenize = 'trigram'
);

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

INSERT INTO media_fts(rowid, orig_name) SELECT id, orig_name FROM media;
