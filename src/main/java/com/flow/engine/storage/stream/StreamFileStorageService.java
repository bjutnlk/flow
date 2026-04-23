package com.flow.engine.storage.stream;

import java.io.InputStream;

/**
 * 流式文件存储接口。
 *
 * <p>与 {@link com.flow.engine.storage.FileStorageService} 的区别：
 * 本接口完全走 {@link InputStream} / {@link DownloadStream}，
 * 不会把整份文件读到内存里，适合大文件（文档、zip、图片、视频等）。
 *
 * <h2>资源关闭约定</h2>
 * <ul>
 *   <li><b>上传</b>：{@code content} 流由<b>调用方</b>创建、调用方关闭。
 *       实现不会主动 close 调用方传进来的流，避免出现"别人的流被我关掉"的
 *       副作用；调用方请用 try-with-resources 包住。</li>
 *   <li><b>下载</b>：方法返回的 {@link DownloadStream} 由<b>调用方</b>关闭，
 *       同样建议用 try-with-resources。它持有底层 HTTP / SDK 连接，
 *       忘记关闭会造成连接泄漏。</li>
 * </ul>
 *
 * <h2>为什么上传必须指定文件类型</h2>
 * <p>云存储（S3 / OSS / COS / MinIO 等）把 MIME 类型当成对象的持久化元数据：
 * 下载时作为 {@code Content-Type} 响应头返回给客户端。如果不指定，
 * 多数 SDK 会默认写 {@code application/octet-stream}，浏览器就只会
 * 当二进制流下载，图片不能内联、PDF 不能预览。
 * 所以上传时必须通过 {@link UploadMetadata} 传入：
 * <ul>
 *   <li>字符串 / JSON → {@code text/plain} / {@code application/json}</li>
 *   <li>Word / Excel / PDF → 对应 office / pdf MIME</li>
 *   <li>zip → {@code application/zip}</li>
 *   <li>图片 → {@code image/png}、{@code image/jpeg} 等</li>
 * </ul>
 * 其中文件名也要带上，云存储会保留它，方便后续按原始名下载。
 */
public interface StreamFileStorageService {

    /**
     * 流式上传。
     *
     * @param content  文件内容流，由调用方负责关闭
     * @param metadata 必填的文件元数据（文件名 + MIME 类型 + 可选大小）
     * @return 上传结果，包含云端 fileId 与最终确认的元数据
     * @throws FileStorageIOException 上传过程中底层云存储失败
     */
    UploadResult upload(InputStream content, UploadMetadata metadata);

    /**
     * 流式下载。
     *
     * <p>返回值是一个 {@link DownloadStream}（{@link java.io.Closeable}），
     * <b>必须</b>由调用方关闭：
     * <pre>{@code
     * try (DownloadStream ds = storage.download(fileId)) {
     *     ds.getInputStream().transferTo(response.getOutputStream());
     * }
     * }</pre>
     *
     * @param fileId 云存储里的对象标识
     * @return 下载流，持有底层连接与元数据
     * @throws FileStorageIOException 文件不存在或底层云存储失败
     */
    DownloadStream download(String fileId);
}
