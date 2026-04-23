package com.flow.engine.zip;

import com.flow.engine.model.FileReference;
import com.flow.engine.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Rebuilds the original ZIP tree for a previously-extracted task.
 *
 * <p>Given a {@code taskId}, the service loads every
 * {@link ZipExtractRecord} in one shot, reconstructs the parent/child
 * tree in memory, and walks the ROOT_ZIP record to re-emit a ZIP whose
 * structure is byte-for-byte identical to the source in terms of
 * entry names and nesting — except that regular {@code FILE} entries
 * use {@link ZipExtractRecord#effectiveFileId()}, i.e. the processed
 * result file when present, otherwise the raw extracted bytes.</p>
 *
 * <p>Nested zips are reassembled recursively: first the inner zip
 * bytes are produced by recursing over its children, then those bytes
 * become a single entry inside the parent zip.</p>
 */
@Service
public class ZipRepackService {

    private static final Logger log = LoggerFactory.getLogger(ZipRepackService.class);

    private final FileStorageService storage;
    private final ZipExtractRecordRepository repository;

    public ZipRepackService(FileStorageService storage,
                            ZipExtractRecordRepository repository) {
        this.storage = storage;
        this.repository = repository;
    }

    /**
     * Aggregate every record of {@code taskId} back into one ZIP and
     * upload it as a new cloud-storage file.
     *
     * @return the {@link FileReference} of the repacked archive
     */
    public FileReference repack(String taskId) {
        return repack(taskId, null);
    }

    /**
     * Same as {@link #repack(String)} but lets the caller override the
     * output file name.
     */
    public FileReference repack(String taskId, String outputFileName) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }

        List<ZipExtractRecord> all = repository.findByTaskId(taskId);
        if (all.isEmpty()) {
            throw new IllegalStateException("No extract records found for task: " + taskId);
        }

        ZipExtractRecord root = null;
        Map<String, List<ZipExtractRecord>> byParent = new HashMap<>();
        for (ZipExtractRecord r : all) {
            if (r.getEntryType() == ZipExtractRecord.EntryType.ROOT_ZIP) {
                if (root != null) {
                    throw new IllegalStateException(
                            "Task '" + taskId + "' has more than one ROOT_ZIP record");
                }
                root = r;
            } else {
                byParent.computeIfAbsent(r.getParentRecordId(), k -> new ArrayList<>()).add(r);
            }
        }
        if (root == null) {
            throw new IllegalStateException("Task '" + taskId + "' has no ROOT_ZIP record");
        }

        log.info("[ZipRepack] task='{}' repacking {} record(s)", taskId, all.size());

        byte[] zipBytes = buildZipBytes(root, byParent);
        String name = outputFileName != null ? outputFileName
                : (root.getFileName() != null ? "repacked-" + root.getFileName()
                                              : "repacked-" + taskId + ".zip");

        FileReference uploaded = storage.upload(name, "application/zip", zipBytes);
        log.info("[ZipRepack] task='{}' produced '{}' ({} bytes, fileId={})",
                taskId, name, zipBytes.length, uploaded.getFileId());
        return uploaded;
    }

    // ---- internal -----------------------------------------------------------

    private byte[] buildZipBytes(ZipExtractRecord container,
                                 Map<String, List<ZipExtractRecord>> byParent) {
        List<ZipExtractRecord> children = byParent.getOrDefault(container.getId(), List.of());

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (ZipExtractRecord child : children) {
                writeEntry(zos, child, byParent);
            }

            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to build zip for container '" + container.getId() + "'", e);
        }
    }

    private void writeEntry(ZipOutputStream zos,
                            ZipExtractRecord record,
                            Map<String, List<ZipExtractRecord>> byParent) throws IOException {
        switch (record.getEntryType()) {
            case DIRECTORY -> {
                String name = record.getEntryPath();
                if (!name.endsWith("/")) name = name + "/";
                zos.putNextEntry(new ZipEntry(name));
                zos.closeEntry();
            }
            case FILE -> {
                String fid = record.effectiveFileId();
                if (fid == null) {
                    throw new IllegalStateException(
                            "FILE record '" + record.getId() + "' (" + record.getEntryPath()
                                    + ") has no fileId/resultFileId");
                }
                byte[] bytes = storage.download(fid);
                zos.putNextEntry(new ZipEntry(record.getEntryPath()));
                zos.write(bytes);
                zos.closeEntry();
            }
            case NESTED_ZIP -> {
                byte[] innerBytes = buildZipBytes(record, byParent);
                zos.putNextEntry(new ZipEntry(record.getEntryPath()));
                zos.write(innerBytes);
                zos.closeEntry();
            }
            case ROOT_ZIP -> throw new IllegalStateException(
                    "ROOT_ZIP record cannot appear as a child entry: " + record.getId());
        }
    }
}
