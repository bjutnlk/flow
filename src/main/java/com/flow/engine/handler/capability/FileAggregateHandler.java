package com.flow.engine.handler.capability;

import com.flow.engine.handler.HandleResult;
import com.flow.engine.handler.NodeHandler;
import com.flow.engine.model.FileReference;
import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.NodeOutput;
import com.flow.engine.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Capability node: aggregates files from predecessor nodes into a single ZIP.
 *
 * <p>Scans all resolved inputs — any value that is a {@link FileReference}
 * is downloaded, added to a ZIP archive, and the resulting ZIP is uploaded
 * back to cloud storage.
 *
 * <h3>Outputs</h3>
 * <ul>
 *   <li>{@code zipFile} (FILE) — the merged ZIP file reference</li>
 *   <li>{@code fileCount} (NUMBER) — how many files were packed</li>
 *   <li>{@code fileNames} (STRING) — comma-separated list of file names in the archive</li>
 * </ul>
 *
 * <h3>JSON example</h3>
 * <pre>{@code
 * {
 *   "id": "pack",
 *   "type": "file_aggregate",
 *   "name": "Pack Files",
 *   "prevNodes": ["nodeA", "nodeB"],
 *   "inputMappings": [
 *     { "name": "report",  "source": "${nodeA.outputFile}", "dataType": "FILE" },
 *     { "name": "receipt", "source": "${nodeB.receipt}",    "dataType": "FILE" },
 *     { "name": "raw",     "source": "file:doc-001",        "dataType": "FILE" }
 *   ]
 * }
 * }</pre>
 *
 * <p>Downstream nodes reference the output:
 * {@code ${pack.zipFile}} — the ZIP file reference,
 * {@code ${pack.fileCount}} — number of files packed.
 */
@Component
public class FileAggregateHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(FileAggregateHandler.class);

    private final FileStorageService storageService;

    public FileAggregateHandler(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public String getType() {
        return "file_aggregate";
    }

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        Map<String, Object> inputs = context.getResolvedInputs();
        log.info("[FileAggregate] Node '{}' packing files from {} input(s)", node.getId(), inputs.size());

        String zipName = node.getId() + "-bundle.zip";
        List<String> packedNames = new ArrayList<>();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (Map.Entry<String, Object> entry : inputs.entrySet()) {
                if (entry.getValue() instanceof FileReference fr) {
                    byte[] content = storageService.download(fr.getFileId());
                    String entryName = fr.getFileName() != null ? fr.getFileName() : entry.getKey();

                    entryName = deduplicateName(entryName, packedNames);

                    zos.putNextEntry(new ZipEntry(entryName));
                    zos.write(content);
                    zos.closeEntry();

                    packedNames.add(entryName);
                    log.debug("[FileAggregate] Added '{}' ({} bytes) from input '{}'",
                            entryName, content.length, entry.getKey());
                }
            }

            zos.finish();
            byte[] zipBytes = baos.toByteArray();

            FileReference zipRef = storageService.upload(zipName, "application/zip", zipBytes);
            log.info("[FileAggregate] Created ZIP '{}' with {} file(s), {} bytes",
                    zipRef.getFileId(), packedNames.size(), zipBytes.length);

            NodeOutput output = NodeOutput.builder()
                    .addFile("zipFile", zipRef)
                    .addNumber("fileCount", packedNames.size())
                    .addString("fileNames", String.join(", ", packedNames))
                    .build();

            return HandleResult.output(output);

        } catch (IOException e) {
            throw new RuntimeException("Failed to create ZIP for node '" + node.getId() + "'", e);
        }
    }

    private String deduplicateName(String name, List<String> existing) {
        if (!existing.contains(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int counter = 2;
        while (existing.contains(base + "_" + counter + ext)) {
            counter++;
        }
        return base + "_" + counter + ext;
    }
}
