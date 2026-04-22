package com.flow.engine.storage.stream;

/**
 * 上传结果。
 *
 * @param fileId       云存储分配的对象标识，后续 download 要用
 * @param fileName     云存储最终保存的文件名（一般与上传时一致）
 * @param contentType  云存储最终保存的 MIME
 * @param size         实际写入的字节数
 * @param etag         对象指纹（S3/OSS 的 ETag），可用于幂等校验；mock 可返回 null
 */
public record UploadResult(
        String fileId,
        String fileName,
        String contentType,
        long size,
        String etag
) {
}
