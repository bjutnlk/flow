package com.flow.engine.handler.capability;

import com.flow.engine.model.FileReference;
import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.NodeOutput;
import com.flow.engine.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Collects resolved inputs as "form fields" and produces a submission
 * receipt file.
 *
 * <p>Outputs: {@code result} (STRING), {@code receiptFile} (FILE).
 */
@Component
public class SubmitFormHandler extends CapabilityNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(SubmitFormHandler.class);

    private final FileStorageService storageService;

    public SubmitFormHandler(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public String getType() {
        return "submit_form";
    }

    @Override
    protected NodeOutput doExecute(FlowNode node, FlowContext context) {
        Map<String, Object> inputs = context.getResolvedInputs();
        log.info("[SubmitForm] Node '{}' submitting form with {} field(s)", node.getId(), inputs.size());

        String receipt = inputs.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));

        FileReference receiptFile = storageService.upload(
                node.getId() + "-receipt.txt",
                "text/plain",
                receipt.getBytes(StandardCharsets.UTF_8));

        log.info("[SubmitForm] Receipt file created: {}", receiptFile.getFileId());

        return NodeOutput.builder()
                .addString("result", "SUBMITTED")
                .addFile("receiptFile", receiptFile)
                .build();
    }
}
