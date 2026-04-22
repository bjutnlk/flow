package com.flow.engine.storage.stream;

import java.util.Objects;

/**
 * 上传时必填的元数据。
 *
 * <p>云存储在保存对象时，这些信息会作为对象元数据持久化，
 * 下载时会作为响应头返回给客户端。所以上传时必须指定，
 * 否则默认类型会是 {@code application/octet-stream}，
 * 浏览器无法正确预览 / 内联。
 *
 * @param fileName     原始文件名，例如 "report.pdf"、"logo.png"，必填
 * @param contentType  MIME 类型，例如 {@code application/pdf}、
 *                     {@code image/png}、{@code application/zip}、
 *                     {@code text/plain; charset=UTF-8}，必填
 * @param contentLength 文件大小（字节），未知时传 {@code -1}。已知时能让
 *                     SDK 直接走一次性 PUT，不用走分片；大文件建议填。
 */
public record UploadMetadata(String fileName, String contentType, long contentLength) {

    public UploadMetadata {
        Objects.requireNonNull(fileName, "fileName 不能为空");
        Objects.requireNonNull(contentType, "contentType 不能为空");
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName 不能为空白");
        }
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType 不能为空白");
        }
    }

    /** 未知大小的便捷构造。 */
    public static UploadMetadata of(String fileName, String contentType) {
        return new UploadMetadata(fileName, contentType, -1L);
    }
}
