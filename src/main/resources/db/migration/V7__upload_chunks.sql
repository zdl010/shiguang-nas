-- 分片记录从"一个逗号分隔的字符串"改成独立的表。
--
-- 去掉单文件大小上限之后，原来的实现撑不住了：received 字段每收一片都要
-- 把整串读出来、拼一个新串、再写回去。一个 200GB 的文件有四万多片，
-- 字符串长到几百 KB，累计的读写量是 O(n²) —— 传到后面会越来越慢直到卡死。
--
-- 独立表让"记下第 i 片收到了"变成一次 O(1) 插入，"还差哪几片"变成一次索引查询。
CREATE TABLE upload_chunks (
  upload_id   TEXT    NOT NULL REFERENCES upload_sessions(id) ON DELETE CASCADE,
  chunk_index INTEGER NOT NULL,
  received_at INTEGER NOT NULL,
  PRIMARY KEY (upload_id, chunk_index)
);

-- 未完成的旧会话直接作废：它们的分片文件布局也变了（见 UploadService），
-- 留着只会让人在"能不能续传"上得到一个错误的答案。
DELETE FROM upload_sessions;

-- 单文件大小上限已取消，实际限制是磁盘剩余空间（见 UploadService.ensureDiskSpace）。
DELETE FROM settings WHERE k = 'upload.max.bytes';
