package com.flow.engine.storage;

public class FileStorageException extends RuntimeException {

    private final String fileId;

    public FileStorageException(String message, String fileId) {
        super(message);
        this.fileId = fileId;
    }

    public FileStorageException(String message, String fileId, Throwable cause) {
        super(message, cause);
        this.fileId = fileId;
    }

    public String getFileId() {
        return fileId;
    }
}
