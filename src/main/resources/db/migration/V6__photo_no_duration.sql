-- 照片不该有时长。
--
-- ffprobe 对单帧图片会报出一个 0.04 秒左右的 duration（就是一帧的长度），
-- 之前原样存进了 duration_ms，于是网格里每张照片都挂着一个「0:00」的时长角标，
-- 和视频长得一模一样——用户看到的效果就是「照片和视频没分类」。
UPDATE media SET duration_ms = NULL WHERE kind = 'PHOTO';
