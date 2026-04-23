package com.flow.engine.handler.capability;

import com.flow.engine.handler.HandleResult;
import com.flow.engine.handler.NodeHandler;
import com.flow.engine.model.FileReference;
import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.NodeOutput;
import com.flow.engine.zip.ZipExtractRecord;
import com.flow.engine.zip.ZipExtractService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Capability node: unpack a ZIP file and persist every entry into
 * {@code zip_extract_record}.
 *
 * <h3>Inputs (resolved into {@code FlowContext.resolvedInputs})</h3>
 * <ul>
 *   <li>{@code zipFile} (FILE) or {@code zipFileId} (STRING) — the source archive
 *       in cloud storage</li>
 *   <li>{@code taskId} (STRING) — business task-detail id; all records
 *       produced are tagged with it and later queried by it when repacking.
 *       If absent, the node falls back to {@code flowContext.flowId}.</li>
 * </ul>
 *
 * <h3>Outputs</h3>
 * <ul>
 *   <li>{@code taskId} (STRING)</li>
 *   <li>{@code rootRecordId} (STRING) — id of the ROOT_ZIP record</li>
 *   <li>{@code recordCount} (NUMBER) — total number of persisted records</li>
 *   <li>{@code fileCount}  (NUMBER) — number of regular file records</li>
 * </ul>
 */
@Component
public class FileExtractHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(FileExtractHandler.class);

    private final ZipExtractService extractService;

    public FileExtractHandler(ZipExtractService extractService) {
        this.extractService = extractService;
    }

    @Override
    public String getType() {
        return "file_extract";
    }

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        Map<String, Object> inputs = context.getResolvedInputs();

        String zipFileId = resolveZipFileId(inputs);
        if (zipFileId == null) {
            throw new IllegalArgumentException(
                    "file_extract node '" + node.getId()
                            + "' requires an input named 'zipFile' (FILE) or 'zipFileId' (STRING)");
        }

        String taskId = asString(inputs.get("taskId"));
        if (taskId == null || taskId.isBlank()) {
            taskId = context.getFlowId() + ":" + node.getId();
        }

        log.info("[file_extract] node='{}' taskId='{}' zipFileId='{}'",
                node.getId(), taskId, zipFileId);

        List<ZipExtractRecord> records = extractService.extract(taskId, zipFileId);

        long fileCount = records.stream()
                .filter(r -> r.getEntryType() == ZipExtractRecord.EntryType.FILE)
                .count();
        String rootId = records.isEmpty() ? null : records.get(0).getId();

        NodeOutput output = NodeOutput.builder()
                .addString("taskId", taskId)
                .addString("rootRecordId", rootId)
                .addNumber("recordCount", records.size())
                .addNumber("fileCount", fileCount)
                .build();
        return HandleResult.output(output);
    }

    private static String resolveZipFileId(Map<String, Object> inputs) {
        Object ref = inputs.get("zipFile");
        if (ref instanceof FileReference fr) {
            return fr.getFileId();
        }
        Object id = inputs.get("zipFileId");
        if (id instanceof String s && !s.isBlank()) {
            return s;
        }
        if (ref instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static String asString(Object v) {
        return v instanceof String s ? s : (v != null ? v.toString() : null);
    }
}
