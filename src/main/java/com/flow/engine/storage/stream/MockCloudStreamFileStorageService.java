package com.flow.engine.storage.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link StreamFileStorageService} 的 Mock 实现。
 *
 * <p>真实环境里，{@link #upload} 会调用云 SDK（比如
 * {@code s3Client.putObject(PutObjectRequest, RequestBody.fromInputStream(...))}），
 * {@link #download} 会调用 {@code s3Client.getObject(...)} 并把 ResponseInputStream
 * 包成 {@link DownloadStream} 返回。
 *
 * <p>这里用 {@link ConcurrentHashMap} 模拟后端，内容原样存着。
 * 注意几个关键点：
 * <ol>
 *   <li>上传时<b>不</b> close 调用方传进来的 {@code content} 流 ——
 *       遵守"谁创建谁关闭"。这和真实 SDK 行为一致。</li>
 *   <li>下载返回 {@link DownloadStream}，内部持有一个 {@link ByteArrayInputStream}，
 *       用来模拟云端连接。调用方 {@code close()} 时会被正确关闭。</li>
 *   <li>上传必须带 {@link UploadMetadata}，否则构造时就会报 NPE，
 *       反映"云存储要求 Content-Type 必填"的现实。</li>
 * </ol>
 */
public class MockCloudStreamFileStorageService implements StreamFileStorageService {

    private static final Logger log = LoggerFactory.getLogger(MockCloudStreamFileStorageService.class);
    private static final int BUFFER_SIZE = 8 * 1024;

    private final Map<String, StoredObject> bucket = new ConcurrentHashMap<>();

    @Override
    public UploadResult upload(InputStream content, UploadMetadata metadata) {
        if (content == null) {
            throw new FileStorageIOException("上传内容流不能为 null");
        }
        if (metadata == null) {
            throw new FileStorageIOException("上传元数据不能为 null，必须显式指定文件名和 contentType");
        }

        String fileId = UUID.randomUUID().toString();
        log.debug("[mock-cloud] 开始上传 fileId={}, name={}, type={}, declaredSize={}",
                fileId, metadata.fileName(), metadata.contentType(), metadata.contentLength());

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0;
        try {
            int n;
            while ((n = content.read(buffer)) != -1) {
                sink.write(buffer, 0, n);
                written += n;
            }
        } catch (IOException e) {
            throw new FileStorageIOException("读取上传流失败", fileId, e);
        }
        // 注意：此处故意不 close(content)。调用方持有、调用方关闭。

        byte[] bytes = sink.toByteArray();
        String etag = Integer.toHexString(java.util.Arrays.hashCode(bytes));
        bucket.put(fileId, new StoredObject(
                metadata.fileName(), metadata.contentType(), bytes, etag));

        log.debug("[mock-cloud] 上传完成 fileId={}, actualSize={}, etag={}", fileId, written, etag);
        return new UploadResult(fileId, metadata.fileName(), metadata.contentType(), written, etag);
    }

    @Override
    public DownloadStream download(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new FileStorageIOException("fileId 不能为空");
        }
        StoredObject obj = bucket.get(fileId);
        if (obj == null) {
            throw new FileStorageIOException("文件不存在: " + fileId, fileId, null);
        }

        log.debug("[mock-cloud] 开始下载 fileId={}, name={}, size={}",
                fileId, obj.fileName, obj.content.length);

        // 真实场景下这里是一个网络流（ResponseInputStream），我们用 ByteArrayInputStream 模拟
        InputStream networkStream = new ByteArrayInputStream(obj.content);
        return new DownloadStream(
                fileId, obj.fileName, obj.contentType, obj.content.length, networkStream);
    }

    /** 仅测试用：直接查询是否存在。 */
    public boolean exists(String fileId) {
        return bucket.containsKey(fileId);
    }

    /** 仅测试用：当前对象数。 */
    public int size() {
        return bucket.size();
    }

    private record StoredObject(String fileName, String contentType, byte[] content, String etag) {}
}
