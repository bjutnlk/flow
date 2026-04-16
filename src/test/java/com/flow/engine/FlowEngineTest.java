package com.flow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.engine.handler.*;
import com.flow.engine.handler.capability.AggregateFileHandler;
import com.flow.engine.handler.capability.SubmitFormHandler;
import com.flow.engine.model.*;
import com.flow.engine.resolve.InputResolver;
import com.flow.engine.service.FlowEngine;
import com.flow.engine.storage.InMemoryFileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowEngineTest {

    private FlowEngine engine;
    private InMemoryFileStorageService fileStorage;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        fileStorage = new InMemoryFileStorageService();
        InputResolver resolver = new InputResolver(fileStorage);

        List<NodeHandler> handlers = List.of(
                new StartNodeHandler(),
                new EndNodeHandler(),
                new TaskNodeHandler(),
                new ConditionNodeHandler(),
                new LogNodeHandler(resolver),
                new SubmitFormHandler(fileStorage),
                new AggregateFileHandler(fileStorage)
        );
        engine = new FlowEngine(mapper, resolver, handlers);
    }

    // ---- original tests (backward compat) -----------------------------------

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
        assertTrue(result.getErrorMessage().contains("nonexistent"));
    }

    @Test
    void customHandlerCanBeRegisteredAtRuntime() {
        engine.registerHandler(new NodeHandler() {
            @Override
            public String getType() { return "custom"; }
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
        FlowResult result = engine.execute(engine.parse(json));

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
        FlowContext ctx = new FlowContext("prepop");
        ctx.setVariable("userId", "U-12345");

        FlowResult result = engine.execute(engine.parse(json), ctx);

        assertTrue(result.isSuccess());
        assertEquals("user=U-12345", result.getVariables().get("_lastLog"));
    }

    // ---- new tests: file references and cross-node outputs ------------------

    @Test
    void submitFormHandler_resolvesFileInput_producesFileOutput() {
        fileStorage.seed("doc-001", "application.pdf", "application/pdf",
                "PDF content".getBytes(StandardCharsets.UTF_8));

        String json = """
                {
                  "id": "submit-test",
                  "name": "Submit Test",
                  "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start", "next": "form" },
                    {
                      "id": "form",
                      "type": "submit_form",
                      "name": "Submit",
                      "inputMappings": [
                        { "name": "userName", "source": "${user}",    "dataType": "STRING" },
                        { "name": "doc",      "source": "file:doc-001", "dataType": "FILE" }
                      ],
                      "next": "e"
                    },
                    { "id": "e", "type": "end", "name": "End" }
                  ]
                }
                """;
        FlowContext ctx = new FlowContext("submit-test");
        ctx.setVariable("user", "Alice");

        FlowResult result = engine.execute(engine.parse(json), ctx);

        assertTrue(result.isSuccess());
        assertEquals("SUBMITTED", result.getVariables().get("form.result"));
        assertNotNull(result.getVariables().get("form.receiptFile"));
        assertTrue(result.getVariables().get("form.receiptFile") instanceof FileReference);

        FileReference receipt = (FileReference) result.getVariables().get("form.receiptFile");
        String content = new String(fileStorage.download(receipt.getFileId()), StandardCharsets.UTF_8);
        assertTrue(content.contains("userName=Alice"));
    }

    @Test
    void crossNodeReference_nodeC_readsNodeA_fileOutput() {
        fileStorage.seed("seed-file", "data.txt", "text/plain",
                "original data".getBytes(StandardCharsets.UTF_8));

        String json = """
                {
                  "id": "cross-ref",
                  "name": "Cross Reference Test",
                  "startNodeId": "a",
                  "nodes": [
                    {
                      "id": "a",
                      "type": "submit_form",
                      "name": "Node A",
                      "inputMappings": [
                        { "name": "input", "source": "file:seed-file", "dataType": "FILE" }
                      ],
                      "next": "b"
                    },
                    {
                      "id": "b",
                      "type": "task",
                      "name": "Node B",
                      "properties": { "status": "processed" },
                      "next": "c"
                    },
                    {
                      "id": "c",
                      "type": "aggregate_file",
                      "name": "Node C",
                      "inputMappings": [
                        { "name": "fileFromA", "source": "${a.receiptFile}", "dataType": "FILE" }
                      ],
                      "next": "end"
                    },
                    { "id": "end", "type": "end", "name": "End" }
                  ]
                }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals(List.of("a", "b", "c", "end"), result.getExecutionTrace());

        assertNotNull(result.getVariables().get("c.mergedFile"));
        assertTrue(result.getVariables().get("c.mergedFile") instanceof FileReference);

        FileReference merged = (FileReference) result.getVariables().get("c.mergedFile");
        String content = new String(fileStorage.download(merged.getFileId()), StandardCharsets.UTF_8);
        assertTrue(content.contains("input="), "merged content should contain the receipt data from node A");
    }

    @Test
    void documentFlow_lowAmount_skipsAggregate() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf",
                "PDF data".getBytes(StandardCharsets.UTF_8));

        InputStream is = getClass().getResourceAsStream("/flows/document-flow.json");
        assertNotNull(is);

        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("applicantName", "Bob");
        ctx.setVariable("amount", 5000);

        FlowResult result = engine.execute(def, ctx);

        assertTrue(result.isSuccess());
        assertEquals(
                List.of("start", "submit", "check-amount", "log-result", "end"),
                result.getExecutionTrace()
        );
        assertEquals("SUBMITTED", result.getVariables().get("submit.result"));
        String lastLog = (String) result.getVariables().get("_lastLog");
        assertTrue(lastLog.contains("SUBMITTED"));
        assertTrue(lastLog.contains("Bob"));
    }

    @Test
    void documentFlow_highAmount_goesAggregate() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf",
                "PDF data".getBytes(StandardCharsets.UTF_8));

        InputStream is = getClass().getResourceAsStream("/flows/document-flow.json");
        assertNotNull(is);

        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("applicantName", "Carol");
        ctx.setVariable("amount", 20000);

        FlowResult result = engine.execute(def, ctx);

        assertTrue(result.isSuccess());
        assertEquals(
                List.of("start", "submit", "check-amount", "aggregate", "log-result", "end"),
                result.getExecutionTrace()
        );
        assertNotNull(result.getVariables().get("aggregate.mergedFile"));
        assertEquals(2, result.getVariables().get("aggregate.fileCount"));
    }

    @Test
    void nodeOutput_stringAndImage_crossReference() {
        engine.registerHandler(new NodeHandler() {
            @Override
            public String getType() { return "generate_image"; }

            @Override
            public String handle(FlowNode node, FlowContext context) { return null; }

            @Override
            public NodeOutput execute(FlowNode node, FlowContext context) {
                FileReference img = new FileReference("img-001", "chart.png", "image/png", 1024);
                return NodeOutput.builder()
                        .addString("caption", "Sales Chart Q1")
                        .addImage("chart", img)
                        .build();
            }
        });

        String json = """
                {
                  "id": "img-flow",
                  "name": "Image Flow",
                  "startNodeId": "gen",
                  "nodes": [
                    { "id": "gen", "type": "generate_image", "name": "Generate",  "next": "log" },
                    { "id": "log", "type": "log", "name": "Log",
                      "properties": { "message": "Generated: ${gen.caption}, file=${gen.chart}" },
                      "next": "end" },
                    { "id": "end", "type": "end", "name": "End" }
                  ]
                }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("Sales Chart Q1", result.getVariables().get("gen.caption"));
        assertTrue(result.getVariables().get("gen.chart") instanceof FileReference);
        String lastLog = (String) result.getVariables().get("_lastLog");
        assertTrue(lastLog.contains("Sales Chart Q1"));
        assertTrue(lastLog.contains("img-001"));
    }

    @Test
    void inputMapping_literalValues() {
        String json = """
                {
                  "id": "literal-test",
                  "name": "Literal Test",
                  "startNodeId": "form",
                  "nodes": [
                    {
                      "id": "form",
                      "type": "submit_form",
                      "name": "Form",
                      "inputMappings": [
                        { "name": "name",  "source": "direct-value",  "dataType": "STRING" },
                        { "name": "count", "source": "42",            "dataType": "NUMBER" }
                      ],
                      "next": "end"
                    },
                    { "id": "end", "type": "end", "name": "End" }
                  ]
                }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("SUBMITTED", result.getVariables().get("form.result"));

        FileReference receipt = (FileReference) result.getVariables().get("form.receiptFile");
        String content = new String(fileStorage.download(receipt.getFileId()), StandardCharsets.UTF_8);
        assertTrue(content.contains("name=direct-value"));
        assertTrue(content.contains("count=42"));
    }
}
