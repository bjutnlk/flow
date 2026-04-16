package com.flow.engine.handler.capability;

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
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Aggregates multiple input files into one merged file.
 *
 * <p>Outputs: {@code mergedFile} (FILE), {@code fileCount} (NUMBER).
 */
@Component
public class AggregateFileHandler extends CapabilityNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(AggregateFileHandler.class);

    private final FileStorageService storageService;

    public AggregateFileHandler(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public String getType() {
        return "aggregate_file";
    }

    @Override
    protected NodeOutput doExecute(FlowNode node, FlowContext context) {
        Map<String, Object> inputs = context.getResolvedInputs();
        log.info("[AggregateFile] Node '{}' aggregating files from {} input(s)", node.getId(), inputs.size());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int fileCount = 0;

        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof FileReference fr) {
                try {
                    byte[] content = storageService.download(fr.getFileId());
                    if (fileCount > 0) {
                        baos.write("\n---\n".getBytes(StandardCharsets.UTF_8));
                    }
                    baos.write(content);
                    fileCount++;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to aggregate file: " + fr.getFileId(), e);
                }
            } else if (val instanceof String s) {
                try {
                    if (fileCount > 0 || baos.size() > 0) {
                        baos.write("\n---\n".getBytes(StandardCharsets.UTF_8));
                    }
                    baos.write(s.getBytes(StandardCharsets.UTF_8));
                    fileCount++;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write string input", e);
                }
            }
        }

        FileReference merged = storageService.upload(
                node.getId() + "-merged.txt",
                "text/plain",
                baos.toByteArray());

        log.info("[AggregateFile] Merged {} source(s) into {}", fileCount, merged.getFileId());

        return NodeOutput.builder()
                .addFile("mergedFile", merged)
                .addNumber("fileCount", fileCount)
                .build();
    }
}
