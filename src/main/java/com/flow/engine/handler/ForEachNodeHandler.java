package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.NodeOutput;
import com.flow.engine.resolve.InputResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Iterates over a list variable and exposes each element to the context.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code properties.collection} — the context variable name (or
 *       {@code ${nodeId.field}} reference) holding the list to iterate</li>
 *   <li>{@code properties.itemVar} — the variable name under which each
 *       item is exposed (defaults to {@code "item"})</li>
 *   <li>{@code properties.indexVar} — the variable name for the current
 *       index (defaults to {@code "index"})</li>
 * </ul>
 *
 * <p>The handler collects each item into an output list so downstream
 * nodes can reference the full iteration result.  The loop itself does
 * not execute sub-nodes (that would require engine-level recursion);
 * instead it flattens the collection into indexed context variables:
 * {@code item_0}, {@code item_1}, etc., plus a {@code _loopSize} variable.
 *
 * <p>For sub-chain execution per iteration, the engine checks
 * {@code node.body} and drives the inner loop itself.
 */
@Component
public class ForEachNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(ForEachNodeHandler.class);

    private final InputResolver inputResolver;

    public ForEachNodeHandler(InputResolver inputResolver) {
        this.inputResolver = inputResolver;
    }

    @Override
    public String getType() {
        return "foreach";
    }

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        String collectionRef = (String) node.getProperties().get("collection");
        String itemVar = (String) node.getProperties().getOrDefault("itemVar", "item");
        String indexVar = (String) node.getProperties().getOrDefault("indexVar", "index");

        Object raw = inputResolver.resolveSource("${" + collectionRef + "}", null, context);
        if (raw == null) {
            raw = context.getVariable(collectionRef);
        }

        List<?> items;
        if (raw instanceof List<?> list) {
            items = list;
        } else if (raw instanceof Collection<?> col) {
            items = new ArrayList<>(col);
        } else if (raw != null) {
            items = List.of(raw);
        } else {
            log.warn("[ForEach] Collection '{}' is null for node '{}'", collectionRef, node.getId());
            items = List.of();
        }

        log.info("[ForEach] Node '{}': iterating over {} item(s) from '{}'",
                node.getId(), items.size(), collectionRef);

        NodeOutput.Builder outputBuilder = NodeOutput.builder();

        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            context.setVariable(itemVar, item);
            context.setVariable(indexVar, i);
            context.setVariable(itemVar + "_" + i, item);
        }

        context.setVariable("_loopSize", items.size());
        context.setVariable(itemVar, items.isEmpty() ? null : items.get(items.size() - 1));
        context.setVariable(indexVar, items.isEmpty() ? 0 : items.size() - 1);

        outputBuilder.addNumber("count", items.size());
        outputBuilder.add("items", NodeOutput.DataType.LIST, items);

        return HandleResult.output(outputBuilder.build());
    }
}
