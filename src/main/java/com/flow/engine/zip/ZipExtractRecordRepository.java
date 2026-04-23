package com.flow.engine.zip;

import java.util.List;
import java.util.Optional;

/**
 * Persistence gateway for {@link ZipExtractRecord}.
 *
 * <p>The interface is backend-agnostic: the default implementation stores
 * records in memory, but a JDBC/JPA implementation can be plugged in by
 * exposing a bean that implements this interface.</p>
 */
public interface ZipExtractRecordRepository {

    ZipExtractRecord save(ZipExtractRecord record);

    void saveAll(List<ZipExtractRecord> records);

    Optional<ZipExtractRecord> findById(String id);

    /**
     * Return every record belonging to {@code taskId}.  The list is
     * what the repack stage consumes to rebuild the original zip tree.
     */
    List<ZipExtractRecord> findByTaskId(String taskId);

    List<ZipExtractRecord> findByParentRecordId(String parentRecordId);

    /**
     * Attach the id of a processed result file to an existing extract
     * record.  The repack stage will prefer {@code resultFileId} over
     * {@code fileId} when assembling the final archive.
     *
     * @return {@code true} if the record existed and was updated.
     */
    boolean updateResultFileId(String recordId, String resultFileId);

    /** Drop every record for {@code taskId} (for re-runs or cleanup). */
    void deleteByTaskId(String taskId);
}
