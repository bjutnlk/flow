package com.flow.engine;

import com.flow.engine.handler.*;
import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowDefinition;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.FlowResult;
import com.flow.engine.service.FlowEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowEngineTest {

    private FlowEngine engine;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        List<NodeHandler> handlers = List.of(
                new StartNodeHandler(),
                new EndNodeHandler(),
                new TaskNodeHandler(),
                new ConditionNodeHandler(),
                new LogNodeHandler()
        );
        engine = new FlowEngine(mapper, handlers);
    }

    @Test
    void executeOrderFlow_highAmount_goesManagerApproval() {
        InputStream is = getClass().getResourceAsStream("/flows/order-flow.json");
        assertNotNull(is, "order-flow.json should exist on classpath");

        FlowDefinition definition = engine.parse(is);
        FlowContext ctx = new FlowContext(definition.getId());
        ctx.setVariable("amount", 2000);

        FlowResult result = engine.execute(definition, ctx);

        assertTrue(result.isSuccess());
        assertEquals("PENDING_APPROVAL", result.getVariables().get("orderStatus"));
        assertEquals("manager", result.getVariables().get("approver"));
        assertEquals(
                List.of("start", "validate", "check-amount", "approve-manager", "log-result", "end"),
                result.getExecutionTrace()
        );
    }

    @Test
    void executeOrderFlow_lowAmount_goesAutoApprove() {
        InputStream is = getClass().getResourceAsStream("/flows/order-flow.json");
        FlowDefinition definition = engine.parse(is);
        FlowContext ctx = new FlowContext(definition.getId());
        ctx.setVariable("amount", 500);

        FlowResult result = engine.execute(definition, ctx);

        assertTrue(result.isSuccess());
        assertEquals("APPROVED", result.getVariables().get("orderStatus"));
        assertEquals("system", result.getVariables().get("approver"));
        assertEquals(
                List.of("start", "validate", "check-amount", "auto-approve", "log-result", "end"),
                result.getExecutionTrace()
        );
    }

    @Test
    void executeSimpleFlowFromJsonString() {
        String json = """
                {
                  "id": "simple",
                  "name": "Simple Flow",
                  "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "t" },
                    { "id": "t", "type": "task",  "name": "T", "properties": { "x": 42 }, "next": "e" },
                    { "id": "e", "type": "end",   "name": "E" }
                  ]
                }
                """;
        FlowDefinition def = engine.parse(json);
        FlowResult result = engine.execute(def);

        assertTrue(result.isSuccess());
        assertEquals(42, result.getVariables().get("x"));
        assertEquals(List.of("s", "t", "e"), result.getExecutionTrace());
    }

    @Test
    void contextVariablesAreSharedBetweenNodes() {
        String json = """
                {
                  "id": "ctx-test",
                  "name": "Context Test",
                  "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "properties": { "greeting": "hello" }, "next": "b" },
                    { "id": "b", "type": "log",  "name": "B", "properties": { "message": "${greeting} world" }, "next": "c" },
                    { "id": "c", "type": "end",  "name": "C" }
                  ]
                }
                """;
        FlowDefinition def = engine.parse(json);
        FlowResult result = engine.execute(def);

        assertTrue(result.isSuccess());
        assertEquals("hello world", result.getVariables().get("_lastLog"));
    }

    @Test
    void missingNodeReturnsFailure() {
        String json = """
                {
                  "id": "bad",
                  "name": "Bad",
                  "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "next": "nonexistent" }
                  ]
                }
                """;
        FlowDefinition def = engine.parse(json);
        FlowResult result = engine.execute(def);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("nonexistent"));
    }

    @Test
    void customHandlerCanBeRegisteredAtRuntime() {
        engine.registerHandler(new NodeHandler() {
            @Override
            public String getType() {
                return "custom";
            }

            @Override
            public String handle(FlowNode node, FlowContext context) {
                context.setVariable("customRan", true);
                return null;
            }
        });

        String json = """
                {
                  "id": "custom-flow",
                  "name": "Custom",
                  "startNodeId": "c",
                  "nodes": [
                    { "id": "c", "type": "custom", "name": "C", "next": "e" },
                    { "id": "e", "type": "end",    "name": "E" }
                  ]
                }
                """;
        FlowDefinition def = engine.parse(json);
        FlowResult result = engine.execute(def);

        assertTrue(result.isSuccess());
        assertEquals(true, result.getVariables().get("customRan"));
    }

    @Test
    void prePopulatedContextVariablesAreAccessible() {
        String json = """
                {
                  "id": "prepop",
                  "name": "Pre-populated",
                  "startNodeId": "log",
                  "nodes": [
                    { "id": "log", "type": "log", "name": "Log", "properties": { "message": "user=${userId}" }, "next": "end" },
                    { "id": "end", "type": "end", "name": "End" }
                  ]
                }
                """;
        FlowDefinition def = engine.parse(json);
        FlowContext ctx = new FlowContext("prepop");
        ctx.setVariable("userId", "U-12345");

        FlowResult result = engine.execute(def, ctx);

        assertTrue(result.isSuccess());
        assertEquals("user=U-12345", result.getVariables().get("_lastLog"));
    }
}
