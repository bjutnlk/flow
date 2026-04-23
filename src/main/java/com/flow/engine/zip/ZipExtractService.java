package com.flow.engine.zip;

import com.flow.engine.model.FileReference;
import com.flow.engine.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Expands a ZIP archive fetched from cloud storage and persists every
 * entry into {@link ZipExtractRecordRepository} so downstream nodes can
 * look up and mutate individual files by {@code taskId}.
 *
 * <p>The extractor handles all of the following archive shapes:</p>
 * <ul>
 *   <li><b>Nested ZIPs</b> — a {@code .zip} entry inside the archive is
 *       recognised, recursively unpacked, and linked via
 *       {@link ZipExtractRecord#getParentRecordId()} back to a
 *       {@code NESTED_ZIP} placeholder record.</li>
 *   <li><b>Empty folders</b> — directory-only entries (ending with
 *       {@code /}) are stored as {@code DIRECTORY} records so the
 *       original tree can be reproduced even when no files live inside
 *       them.</li>
 *   <li><b>Duplicated file names in different folders</b> — records are
 *       keyed by {@code (parentRecordId, entryPath)}, so
 *       {@code dirA/data.txt} and {@code dirB/data.txt} coexist without
 *       collision.</li>
 * </ul>
 *
 * <p>A defensive maximum nesting depth guards against zip-bomb style
 * archives that embed zips inside zips inside zips.</p>
 */
@Service
public class ZipExtractService {

    private static final Logger log = LoggerFactory.getLogger(ZipExtractService.class);

    /** Hard cap on nested-zip recursion to avoid pathological archives. */
    public static final int DEFAULT_MAX_DEPTH = 16;

    private final FileStorageService storage;
    private final ZipExtractRecordRepository repository;
    private int maxDepth = DEFAULT_MAX_DEPTH;

    public ZipExtractService(FileStorageService storage,
                             ZipExtractRecordRepository repository) {
        this.storage = storage;
        this.repository = repository;
    }

    /** Override the default nested-zip depth limit (tests / advanced use). */
    public ZipExtractService withMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        return this;
    }

    /**
     * Download {@code zipFileId} from cloud storage, explode it and
     * persist one {@link ZipExtractRecord} per entry (including
     * intermediate directory and nested-zip placeholder records).
     *
     * @param taskId     business task-detail id; every record emitted
     *                   during this call is tagged with it so the repack
     *                   stage can select them with a single query
     * @param zipFileId  cloud storage id of the root zip file
     * @return the flat list of persisted records (root first)
     */
    public List<ZipExtractRecord> extract(String taskId, String zipFileId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (zipFileId == null || zipFileId.isBlank()) {
            throw new IllegalArgumentException("zipFileId is required");
        }

        FileReference rootRef = storage.resolve(zipFileId);
        byte[] rootBytes = storage.download(zipFileId);

        log.info("[ZipExtract] task='{}' extracting root zip '{}' ({} bytes)",
                taskId, zipFileId, rootBytes.length);

        List<ZipExtractRecord> out = new ArrayList<>();

        ZipExtractRecord rootRecord = ZipExtractRecord.builder()
                .taskId(taskId)
                .sourceZipFileId(zipFileId)
                .entryType(ZipExtractRecord.EntryType.ROOT_ZIP)
                .entryPath("")
                .fileName(rootRef.getFileName() != null ? rootRef.getFileName() : "root.zip")
                .depth(0)
                .fileId(zipFileId)
                .sizeBytes(rootBytes.length)
                .mimeType("application/zip")
                .build();
        repository.save(rootRecord);
        out.add(rootRecord);

        explode(taskId, zipFileId, rootBytes, rootRecord, 1, out);

        log.info("[ZipExtract] task='{}' produced {} record(s)", taskId, out.size());
        return out;
    }

    private void explode(String taskId,
                         String sourceZipFileId,
                         byte[] zipBytes,
                         ZipExtractRecord parent,
                         int depth,
                         List<ZipExtractRecord> collected) {
        if (depth > maxDepth) {
            throw new IllegalStateException(
                    "Nested zip depth exceeds " + maxDepth + " at '" + parent.getFileName() + "'");
        }

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                try {
                    handleEntry(taskId, sourceZipFileId, parent, depth, entry, zis, collected);
                } finally {
                    zis.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read zip '" + parent.getFileName() + "' for task " + taskId, e);
        }
    }

    private void handleEntry(String taskId,
                             String sourceZipFileId,
                             ZipExtractRecord parent,
                             int depth,
                             ZipEntry entry,
                             InputStream entryStream,
                             List<ZipExtractRecord> collected) throws IOException {
        String path = entry.getName();

        if (entry.isDirectory() || path.endsWith("/")) {
            ZipExtractRecord dir = ZipExtractRecord.builder()
                    .taskId(taskId)
                    .sourceZipFileId(sourceZipFileId)
                    .parentRecordId(parent.getId())
                    .entryType(ZipExtractRecord.EntryType.DIRECTORY)
                    .entryPath(path)
                    .fileName(leafNameOfDirectory(path))
                    .depth(depth)
                    .sizeBytes(0)
                    .build();
            repository.save(dir);
            collected.add(dir);
            log.debug("[ZipExtract]   + DIR  {}  (parent={})", path, parent.getId());
            return;
        }

        byte[] content = readAll(entryStream);
        String leaf = leafName(path);

        if (looksLikeZip(leaf, content)) {
            ZipExtractRecord nested = ZipExtractRecord.builder()
                    .taskId(taskId)
                    .sourceZipFileId(sourceZipFileId)
                    .parentRecordId(parent.getId())
                    .entryType(ZipExtractRecord.EntryType.NESTED_ZIP)
                    .entryPath(path)
                    .fileName(leaf)
                    .depth(depth)
                    .sizeBytes(content.length)
                    .mimeType("application/zip")
                    .build();
            FileReference nestedRef = storage.upload(leaf, "application/zip", content);
            nested.setFileId(nestedRef.getFileId());
            repository.save(nested);
            collected.add(nested);

            log.debug("[ZipExtract]   + ZIP  {}  (parent={}, depth={})", path, parent.getId(), depth);

            explode(taskId, sourceZipFileId, content, nested, depth + 1, collected);
            return;
        }

        String mime = guessMimeType(leaf);
        FileReference uploaded = storage.upload(leaf, mime, content);

        ZipExtractRecord file = ZipExtractRecord.builder()
                .taskId(taskId)
                .sourceZipFileId(sourceZipFileId)
                .parentRecordId(parent.getId())
                .entryType(ZipExtractRecord.EntryType.FILE)
                .entryPath(path)
                .fileName(leaf)
                .depth(depth)
                .fileId(uploaded.getFileId())
                .sizeBytes(content.length)
                .mimeType(mime)
                .build();
        repository.save(file);
        collected.add(file);

        log.debug("[ZipExtract]   + FILE {}  ({} bytes, parent={})", path, content.length, parent.getId());
    }

    // --- helpers -------------------------------------------------------------

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean looksLikeZip(String fileName, byte[] content) {
        if (fileName != null && fileName.toLowerCase().endsWith(".zip")) {
            return true;
        }
        return content.length >= 4
                && content[0] == 0x50 && content[1] == 0x4B
                && (content[2] == 0x03 || content[2] == 0x05 || content[2] == 0x07)
                && (content[3] == 0x04 || content[3] == 0x06 || content[3] == 0x08);
    }

    private static String leafName(String path) {
        if (path == null || path.isEmpty()) return path;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String leafNameOfDirectory(String path) {
        if (path == null || path.isEmpty()) return path;
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private static String guessMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
