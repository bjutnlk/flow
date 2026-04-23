package com.flow.engine.zip;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single record produced by unpacking a ZIP archive.
 *
 * <p>The entire archive tree (including nested ZIPs, empty folders and
 * duplicated file names across different folders) is flattened into a
 * collection of records.  The tree is reconstructed at repack time by
 * following {@link #parentRecordId} and {@link #entryPath}.</p>
 *
 * <h3>Logical table schema — {@code zip_extract_record}</h3>
 * <pre>
 * ┌─────────────────────┬──────────────┬──────────────────────────────────────────┐
 * │ Column              │ Type         │ Description                              │
 * ├─────────────────────┼──────────────┼──────────────────────────────────────────┤
 * │ id                  │ VARCHAR(64)  │ PK                                       │
 * │ task_id             │ VARCHAR(64)  │ Business task-detail id; groups one zip  │
 * │ source_zip_file_id  │ VARCHAR(64)  │ Cloud storage id of the root zip         │
 * │ parent_record_id    │ VARCHAR(64)  │ FK to this table; null for ROOT_ZIP      │
 * │ entry_type          │ VARCHAR(16)  │ ROOT_ZIP/NESTED_ZIP/DIRECTORY/FILE       │
 * │ entry_path          │ VARCHAR(1024)│ Relative path inside its parent container│
 * │ file_name           │ VARCHAR(512) │ Leaf name                                │
 * │ depth               │ INT          │ Nesting level; 0 = root zip              │
 * │ file_id             │ VARCHAR(64)  │ Cloud id of extracted bytes (FILE only)  │
 * │ result_file_id      │ VARCHAR(64)  │ Cloud id of processed result             │
 * │ size_bytes          │ BIGINT       │ Extracted size in bytes                  │
 * │ mime_type           │ VARCHAR(128) │ Best-effort mime type                    │
 * │ created_at          │ TIMESTAMP    │                                          │
 * └─────────────────────┴──────────────┴──────────────────────────────────────────┘
 * Indexes: (task_id), (task_id, parent_record_id), (parent_record_id)
 * </pre>
 */
public class ZipExtractRecord {

    /** Kind of entry represented by this record. */
    public enum EntryType {
        /** The outermost zip archive; has no parent. */
        ROOT_ZIP,
        /** A zip file that lives inside another zip and was recursively unpacked. */
        NESTED_ZIP,
        /** An explicit (possibly empty) directory entry. */
        DIRECTORY,
        /** A regular file entry; its bytes are uploaded to cloud storage. */
        FILE
    }

    private String id;
    private String taskId;
    private String sourceZipFileId;
    private String parentRecordId;
    private EntryType entryType;
    private String entryPath;
    private String fileName;
    private int depth;
    private String fileId;
    private String resultFileId;
    private long sizeBytes;
    private String mimeType;
    private Instant createdAt;

    public ZipExtractRecord() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getSourceZipFileId() { return sourceZipFileId; }
    public void setSourceZipFileId(String sourceZipFileId) { this.sourceZipFileId = sourceZipFileId; }

    public String getParentRecordId() { return parentRecordId; }
    public void setParentRecordId(String parentRecordId) { this.parentRecordId = parentRecordId; }

    public EntryType getEntryType() { return entryType; }
    public void setEntryType(EntryType entryType) { this.entryType = entryType; }

    public String getEntryPath() { return entryPath; }
    public void setEntryPath(String entryPath) { this.entryPath = entryPath; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getResultFileId() { return resultFileId; }
    public void setResultFileId(String resultFileId) { this.resultFileId = resultFileId; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    /**
     * The cloud storage id that the repack stage should use for this file:
     * {@link #resultFileId} when present (i.e. the downstream node has
     * produced a processed result), otherwise the raw extracted {@link #fileId}.
     */
    public String effectiveFileId() {
        return resultFileId != null ? resultFileId : fileId;
    }

    public boolean isContainer() {
        return entryType == EntryType.ROOT_ZIP || entryType == EntryType.NESTED_ZIP;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ZipExtractRecord that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ZipExtractRecord{"
                + "id='" + id + '\''
                + ", taskId='" + taskId + '\''
                + ", type=" + entryType
                + ", path='" + entryPath + '\''
                + ", depth=" + depth
                + ", fileId='" + fileId + '\''
                + ", resultFileId='" + resultFileId + '\''
                + '}';
    }

    public static class Builder {
        private final ZipExtractRecord r = new ZipExtractRecord();

        public Builder id(String id) { r.id = id; return this; }
        public Builder taskId(String v) { r.taskId = v; return this; }
        public Builder sourceZipFileId(String v) { r.sourceZipFileId = v; return this; }
        public Builder parentRecordId(String v) { r.parentRecordId = v; return this; }
        public Builder entryType(EntryType v) { r.entryType = v; return this; }
        public Builder entryPath(String v) { r.entryPath = v; return this; }
        public Builder fileName(String v) { r.fileName = v; return this; }
        public Builder depth(int v) { r.depth = v; return this; }
        public Builder fileId(String v) { r.fileId = v; return this; }
        public Builder resultFileId(String v) { r.resultFileId = v; return this; }
        public Builder sizeBytes(long v) { r.sizeBytes = v; return this; }
        public Builder mimeType(String v) { r.mimeType = v; return this; }

        public ZipExtractRecord build() {
            Objects.requireNonNull(r.taskId, "taskId is required");
            Objects.requireNonNull(r.entryType, "entryType is required");
            return r;
        }
    }
}
