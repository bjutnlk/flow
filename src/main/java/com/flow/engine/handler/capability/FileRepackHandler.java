package com.flow.engine.handler.capability;

import com.flow.engine.handler.HandleResult;
import com.flow.engine.handler.NodeHandler;
import com.flow.engine.model.FileReference;
import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.NodeOutput;
import com.flow.engine.zip.ZipRepackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Capability node: pack every extract record tagged with the given
 * {@code taskId} back into a single ZIP, honouring result-file
 * replacements.
 *
 * <h3>Inputs</h3>
 * <ul>
 *   <li>{@code taskId} (STRING, required)</li>
 *   <li>{@code outputFileName} (STRING, optional)</li>
 * </ul>
 *
 * <h3>Outputs</h3>
 * <ul>
 *   <li>{@code zipFile} (FILE) — reference to the repacked archive</li>
 *   <li>{@code taskId}  (STRING)</li>
 * </ul>
 */
@Component
public class FileRepackHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(FileRepackHandler.class);

    private final ZipRepackService repackService;

    public FileRepackHandler(ZipRepackService repackService) {
        this.repackService = repackService;
    }

    @Override
    public String getType() {
        return "file_repack";
    }

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        Map<String, Object> inputs = context.getResolvedInputs();

        String taskId = asString(inputs.get("taskId"));
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException(
                    "file_repack node '" + node.getId() + "' requires input 'taskId'");
        }
        String outName = asString(inputs.get("outputFileName"));

        log.info("[file_repack] node='{}' taskId='{}'", node.getId(), taskId);

        FileReference zipRef = repackService.repack(taskId, outName);

        NodeOutput output = NodeOutput.builder()
                .addFile("zipFile", zipRef)
                .addString("taskId", taskId)
                .build();
        return HandleResult.output(output);
    }

    private static String asString(Object v) {
        return v instanceof String s ? s : (v != null ? v.toString() : null);
    }
}
