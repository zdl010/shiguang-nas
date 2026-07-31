package com.shiguang.nas.media;

public record Media(
        long id,
        long ownerId,
        String sha256,
        String kind,
        String mime,
        String ext,
        String origName,
        long sizeBytes,
        Integer width,
        Integer height,
        Long durationMs,
        Long takenAt,
        long createdAt,
        String relPath,
        String thumbState,
        boolean playable,
        boolean starred,
        Long deletedAt) {

    public static final String KIND_PHOTO = "PHOTO";
    public static final String KIND_VIDEO = "VIDEO";
    public static final String KIND_AUDIO = "AUDIO";

    public static final String THUMB_PENDING = "PENDING";
    public static final String THUMB_READY = "READY";
    public static final String THUMB_FAILED = "FAILED";
    /** 音频没有画面，也没有内嵌封面时用这个状态，前端据此画渐变占位图 */
    public static final String THUMB_NONE = "NONE";

    public boolean deleted() {
        return deletedAt != null;
    }

    /** 排序和展示都优先用拍摄时间，没有 EXIF 的文件退回上传时间。 */
    public long effectiveTime() {
        return takenAt != null ? takenAt : createdAt;
    }
}
