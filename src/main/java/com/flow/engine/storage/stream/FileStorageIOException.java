package com.flow.engine.storage.stream;

/**
 * 流式文件存储运行时异常。用 RuntimeException，让上层可选捕获，
 * 同时不污染 Stream API 链式调用。
 */
public class FileStorageIOException extends RuntimeException {

    private final String fileId;

    public FileStorageIOException(String message) {
        super(message);
        this.fileId = null;
    }

    public FileStorageIOException(String message, Throwable cause) {
        super(message, cause);
        this.fileId = null;
    }

    public FileStorageIOException(String message, String fileId, Throwable cause) {
        super(message, cause);
        this.fileId = fileId;
    }

    public String getFileId() {
        return fileId;
    }
}
