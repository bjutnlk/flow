package com.flow.engine.storage.stream;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * 下载流句柄。把元数据 + 数据流 + 底层连接绑在一起。
 *
 * <p><b>必须关闭</b>。{@link #getInputStream()} 底下通常是一条 HTTP / SDK
 * 连接，不关就会泄漏。推荐用法：
 * <pre>{@code
 * try (DownloadStream ds = storage.download(fileId)) {
 *     ds.getInputStream().transferTo(out);
 * }
 * }</pre>
 *
 * <p>{@link #close()} 实现了幂等：重复调用安全。关闭时会同时关底层的
 * {@link InputStream}，所以调用方不需要再单独 close 拿到的 stream。
 */
public final class DownloadStream implements Closeable {

    private final String fileId;
    private final String fileName;
    private final String contentType;
    private final long size;
    private final InputStream inputStream;

    private volatile boolean closed = false;

    public DownloadStream(String fileId,
                          String fileName,
                          String contentType,
                          long size,
                          InputStream inputStream) {
        this.fileId = Objects.requireNonNull(fileId, "fileId");
        this.fileName = fileName;
        this.contentType = contentType;
        this.size = size;
        this.inputStream = Objects.requireNonNull(inputStream, "inputStream");
    }

    public String getFileId() {
        return fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    /** 拿到底层数据流；关闭本对象即关闭此流。 */
    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        inputStream.close();
    }
}
