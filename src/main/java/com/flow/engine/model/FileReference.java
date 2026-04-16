package com.flow.engine.model;

/**
 * A reference to a file stored in cloud storage.
 *
 * <p>Files flowing through the engine are never raw byte arrays;
 * they are represented by this lightweight descriptor containing
 * the cloud storage {@code fileId} and optional metadata.  Handlers
 * use {@link com.flow.engine.storage.FileStorageService} to fetch
 * or upload the actual bytes when needed.
 */
public class FileReference {

    private final String fileId;
    private final String fileName;
    private final String mimeType;
    private final long sizeBytes;

    public FileReference(String fileId) {
        this(fileId, null, null, -1);
    }

    public FileReference(String fileId, String fileName, String mimeType, long sizeBytes) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
    }

    public String getFileId() {
        return fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public boolean isImage() {
        return mimeType != null && mimeType.startsWith("image/");
    }

    @Override
    public String toString() {
        return "FileRef{id='" + fileId + "'"
                + (fileName != null ? ", name='" + fileName + "'" : "")
                + (mimeType != null ? ", type='" + mimeType + "'" : "")
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileReference that)) return false;
        return fileId.equals(that.fileId);
    }

    @Override
    public int hashCode() {
        return fileId.hashCode();
    }
}
