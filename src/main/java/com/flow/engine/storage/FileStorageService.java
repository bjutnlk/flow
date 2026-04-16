package com.flow.engine.storage;

import com.flow.engine.model.FileReference;

/**
 * Abstraction over cloud file storage.
 *
 * <p>The flow engine never touches raw file bytes directly;
 * it works exclusively with {@link FileReference} descriptors.
 * Implementations of this interface bridge to the actual storage
 * backend (S3, OSS, MinIO, etc.).
 */
public interface FileStorageService {

    /**
     * Resolve a file ID to a full {@link FileReference} with metadata
     * (file name, MIME type, size) by querying the cloud storage.
     *
     * @param fileId the cloud storage file identifier
     * @return populated file reference
     * @throws FileStorageException if the file does not exist or cannot be accessed
     */
    FileReference resolve(String fileId);

    /**
     * Download the content of a file as bytes.
     */
    byte[] download(String fileId);

    /**
     * Upload bytes and return a new {@link FileReference}.
     */
    FileReference upload(String fileName, String mimeType, byte[] content);
}
