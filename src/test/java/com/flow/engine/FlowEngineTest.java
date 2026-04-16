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
    private InputResolver resolver;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        fileStorage = new InMemoryFileStorageService();
        resolver = new InputResolver(fileStorage);

        List<NodeHandler> handlers = List.of(
                new StartNodeHandler(),
                new EndNodeHandler(),
                new TaskNodeHandler(),
                new ConditionNodeHandler(resolver),
                new SwitchNodeHandler(resolver),
                new ForEachNodeHandler(resolver),
                new LogNodeHandler(resolver),
                new SubmitFormHandler(fileStorage),
                new AggregateFileHandler(fileStorage)
        );
        engine = new FlowEngine(mapper, resolver, handlers);
    }

    // ---- basic flow tests ---------------------------------------------------

    @Test
    void executeOrderFlow_highAmount_goesManagerApproval() {
        InputStream is = getClass().getResourceAsStream("/flows/order-flow.json");
        assertNotNull(is);

        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("amount", 2000);

        FlowResult result = engine.execute(def, ctx);

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
        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("amount", 500);

        FlowResult result = engine.execute(def, ctx);

        assertTrue(result.isSuccess());
        assertEquals("APPROVED", result.getVariables().get("orderStatus"));
        assertEquals("system", result.getVariables().get("approver"));
    }

    @Test
    void simpleFlow() {
        String json = """
                {
                  "id": "simple", "name": "Simple", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "t" },
                    { "id": "t", "type": "task",  "name": "T", "properties": { "x": 42 }, "next": "e" },
                    { "id": "e", "type": "end",   "name": "E" }
                  ]
                }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals(42, result.getVariables().get("x"));
        assertEquals(List.of("s", "t", "e"), result.getExecutionTrace());
    }

    @Test
    void contextSharedBetweenNodes() {
        String json = """
                {
                  "id": "ctx", "name": "Ctx", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "properties": { "greeting": "hello" }, "next": "b" },
                    { "id": "b", "type": "log",  "name": "B", "properties": { "message": "${greeting} world" }, "next": "c" },
                    { "id": "c", "type": "end",  "name": "C" }
                  ]
                }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("hello world", result.getVariables().get("_lastLog"));
    }

    @Test
    void missingNodeReturnsFailure() {
        String json = """
                {
                  "id": "bad", "name": "Bad", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "next": "nonexistent" }
                  ]
                }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("nonexistent"));
    }

    @Test
    void customHandlerAtRuntime() {
        engine.registerHandler(new NodeHandler() {
            @Override
            public String getType() { return "custom"; }
            @Override
            public HandleResult execute(FlowNode node, FlowContext context) {
                context.setVariable("customRan", true);
                return HandleResult.none();
            }
        });

        String json = """
                {
                  "id": "custom", "name": "Custom", "startNodeId": "c",
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
    void prePopulatedContext() {
        String json = """
                {
                  "id": "pre", "name": "Pre", "startNodeId": "log",
                  "nodes": [
                    { "id": "log", "type": "log", "name": "L", "properties": { "message": "user=${userId}" }, "next": "end" },
                    { "id": "end", "type": "end", "name": "E" }
                  ]
                }
                """;
        FlowContext ctx = new FlowContext("pre");
        ctx.setVariable("userId", "U-12345");

        FlowResult result = engine.execute(engine.parse(json), ctx);
        assertTrue(result.isSuccess());
        assertEquals("user=U-12345", result.getVariables().get("_lastLog"));
    }

    // ---- file and cross-node tests ------------------------------------------

    @Test
    void submitForm_producesFileOutput() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf",
                "PDF content".getBytes(StandardCharsets.UTF_8));

        String json = """
                {
                  "id": "sf", "name": "SF", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "form" },
                    {
                      "id": "form", "type": "submit_form", "name": "Form",
                      "inputMappings": [
                        { "name": "userName", "source": "${user}",       "dataType": "STRING" },
                        { "name": "doc",      "source": "file:doc-001",  "dataType": "FILE" }
                      ],
                      "next": "e"
                    },
                    { "id": "e", "type": "end", "name": "E" }
                  ]
                }
                """;
        FlowContext ctx = new FlowContext("sf");
        ctx.setVariable("user", "Alice");

        FlowResult result = engine.execute(engine.parse(json), ctx);

        assertTrue(result.isSuccess());
        assertEquals("SUBMITTED", result.getVariables().get("form.result"));
        assertTrue(result.getVariables().get("form.receiptFile") instanceof FileReference);

        FileReference receipt = (FileReference) result.getVariables().get("form.receiptFile");
        String content = new String(fileStorage.download(receipt.getFileId()), StandardCharsets.UTF_8);
        assertTrue(content.contains("userName=Alice"));
    }

    @Test
    void crossNodeReference_C_readsA_output() {
        fileStorage.seed("seed-file", "data.txt", "text/plain",
                "original data".getBytes(StandardCharsets.UTF_8));

        String json = """
                {
                  "id": "xref", "name": "XRef", "startNodeId": "a",
                  "nodes": [
                    {
                      "id": "a", "type": "submit_form", "name": "A",
                      "inputMappings": [
                        { "name": "input", "source": "file:seed-file", "dataType": "FILE" }
                      ],
                      "next": "b"
                    },
                    {
                      "id": "b", "type": "task", "name": "B",
                      "properties": { "status": "processed" },
                      "next": "c"
                    },
                    {
                      "id": "c", "type": "aggregate_file", "name": "C",
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
        assertTrue(result.getVariables().get("c.mergedFile") instanceof FileReference);
    }

    // ---- consecutive capability nodes (no flow nodes in between) ------------

    @Test
    void consecutiveCapabilityNodes_A_B_C_D_noFlowNodesInMiddle() {
        fileStorage.seed("raw-data-001", "raw.csv", "text/csv",
                "id,name\n1,Alice\n2,Bob".getBytes(StandardCharsets.UTF_8));

        InputStream is = getClass().getResourceAsStream("/flows/pipeline-flow.json");
        assertNotNull(is);

        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("dataSource", "CRM");

        FlowResult result = engine.execute(def, ctx);

        assertTrue(result.isSuccess());
        assertEquals(
                List.of("ingest", "transform", "validate", "export", "end"),
                result.getExecutionTrace()
        );

        assertEquals("SUBMITTED", result.getVariables().get("ingest.result"));
        assertEquals("SUBMITTED", result.getVariables().get("transform.result"));
        assertEquals("SUBMITTED", result.getVariables().get("validate.result"));
        assertTrue(result.getVariables().get("export.mergedFile") instanceof FileReference);
        assertEquals(2, result.getVariables().get("export.fileCount"));

        FileReference exported = (FileReference) result.getVariables().get("export.mergedFile");
        String content = new String(fileStorage.download(exported.getFileId()), StandardCharsets.UTF_8);
        assertFalse(content.isEmpty());
    }

    @Test
    void capabilityNodes_directChain_noStartOrEnd() {
        String json = """
                {
                  "id": "bare", "name": "Bare", "startNodeId": "a",
                  "nodes": [
                    {
                      "id": "a", "type": "submit_form", "name": "A",
                      "inputMappings": [
                        { "name": "field1", "source": "value1", "dataType": "STRING" }
                      ],
                      "next": "b"
                    },
                    {
                      "id": "b", "type": "submit_form", "name": "B",
                      "inputMappings": [
                        { "name": "prevReceipt", "source": "${a.receiptFile}", "dataType": "FILE" },
                        { "name": "extra",       "source": "extra-data",       "dataType": "STRING" }
                      ],
                      "next": "c"
                    },
                    {
                      "id": "c", "type": "submit_form", "name": "C",
                      "inputMappings": [
                        { "name": "fromA", "source": "${a.result}",  "dataType": "STRING" },
                        { "name": "fromB", "source": "${b.result}",  "dataType": "STRING" }
                      ]
                    }
                  ]
                }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals(List.of("a", "b", "c"), result.getExecutionTrace());
        assertEquals("SUBMITTED", result.getVariables().get("a.result"));
        assertEquals("SUBMITTED", result.getVariables().get("b.result"));
        assertEquals("SUBMITTED", result.getVariables().get("c.result"));

        FileReference cReceipt = (FileReference) result.getVariables().get("c.receiptFile");
        String content = new String(fileStorage.download(cReceipt.getFileId()), StandardCharsets.UTF_8);
        assertTrue(content.contains("fromA=SUBMITTED"));
        assertTrue(content.contains("fromB=SUBMITTED"));
    }

    // ---- document flow tests ------------------------------------------------

    @Test
    void documentFlow_lowAmount() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf",
                "PDF data".getBytes(StandardCharsets.UTF_8));

        InputStream is = getClass().getResourceAsStream("/flows/document-flow.json");
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
    }

    @Test
    void documentFlow_highAmount() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf",
                "PDF data".getBytes(StandardCharsets.UTF_8));

        InputStream is = getClass().getResourceAsStream("/flows/document-flow.json");
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
    }

    // ---- switch node --------------------------------------------------------

    @Test
    void switchNode_matchesCase() {
        String json = """
                {
                  "id": "sw", "name": "Switch", "startNodeId": "set",
                  "nodes": [
                    { "id": "set", "type": "task", "name": "Set",
                      "properties": { "status": "REJECTED" }, "next": "sw" },
                    {
                      "id": "sw", "type": "switch", "name": "Switch",
                      "properties": { "expression": "status" },
                      "branches": [
                        { "condition": "APPROVED",  "target": "ok" },
                        { "condition": "REJECTED",  "target": "fail" }
                      ],
                      "next": "ok"
                    },
                    { "id": "ok",   "type": "task", "name": "OK",   "properties": { "path": "approved" }, "next": "end" },
                    { "id": "fail", "type": "task", "name": "Fail", "properties": { "path": "rejected" }, "next": "end" },
                    { "id": "end",  "type": "end",  "name": "End" }
                  ]
                }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("rejected", result.getVariables().get("path"));
        assertEquals(List.of("set", "sw", "fail", "end"), result.getExecutionTrace());
    }

    @Test
    void switchNode_defaultFallthrough() {
        String json = """
                {
                  "id": "sw2", "name": "Switch2", "startNodeId": "set",
                  "nodes": [
                    { "id": "set", "type": "task", "name": "Set",
                      "properties": { "status": "UNKNOWN" }, "next": "sw" },
                    {
                      "id": "sw", "type": "switch", "name": "Switch",
                      "properties": { "expression": "status" },
                      "branches": [
                        { "condition": "APPROVED", "target": "ok" }
                      ],
                      "next": "default"
                    },
                    { "id": "ok",      "type": "task", "name": "OK",  "properties": { "path": "approved" }, "next": "end" },
                    { "id": "default", "type": "task", "name": "Def", "properties": { "path": "default" },  "next": "end" },
                    { "id": "end",     "type": "end",  "name": "End" }
                  ]
                }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("default", result.getVariables().get("path"));
        assertEquals(List.of("set", "sw", "default", "end"), result.getExecutionTrace());
    }

    @Test
    void switchNode_onNodeOutputReference() {
        String json = """
                {
                  "id": "sw3", "name": "Switch3", "startNodeId": "form",
                  "nodes": [
                    {
                      "id": "form", "type": "submit_form", "name": "Form",
                      "inputMappings": [{ "name": "x", "source": "y", "dataType": "STRING" }],
                      "next": "sw"
                    },
                    {
                      "id": "sw", "type": "switch", "name": "Switch",
                      "properties": { "expression": "form.result" },
                      "branches": [
                        { "condition": "SUBMITTED", "target": "ok" },
                        { "condition": "FAILED",    "target": "fail" }
                      ],
                      "next": "fail"
                    },
                    { "id": "ok",   "type": "task", "name": "OK",   "properties": { "path": "ok" },   "next": "end" },
                    { "id": "fail", "type": "task", "name": "Fail", "properties": { "path": "fail" }, "next": "end" },
                    { "id": "end",  "type": "end",  "name": "End" }
                  ]
                }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("ok", result.getVariables().get("path"));
    }

    // ---- foreach node -------------------------------------------------------

    @Test
    void forEachNode_iteratesOverList() {
        String json = """
                {
                  "id": "fe", "name": "ForEach", "startNodeId": "setup",
                  "nodes": [
                    { "id": "setup", "type": "task", "name": "Setup", "next": "loop" },
                    {
                      "id": "loop", "type": "foreach", "name": "Loop",
                      "properties": { "collection": "items", "itemVar": "current", "indexVar": "idx" },
                      "next": "log"
                    },
                    { "id": "log", "type": "log", "name": "Log",
                      "properties": { "message": "processed ${loop.count} items" },
                      "next": "end" },
                    { "id": "end", "type": "end", "name": "End" }
                  ]
                }
                """;
        FlowContext ctx = new FlowContext("fe");
        ctx.setVariable("items", List.of("apple", "banana", "cherry"));

        FlowResult result = engine.execute(engine.parse(json), ctx);

        assertTrue(result.isSuccess());
        assertEquals(3, result.getVariables().get("loop.count"));
        assertEquals("cherry", result.getVariables().get("current"));
        assertEquals("processed 3 items", result.getVariables().get("_lastLog"));
    }

    // ---- image / mixed output -----------------------------------------------

    @Test
    void nodeOutput_stringAndImage_crossReference() {
        engine.registerHandler(new NodeHandler() {
            @Override
            public String getType() { return "generate_image"; }
            @Override
            public HandleResult execute(FlowNode node, FlowContext context) {
                FileReference img = new FileReference("img-001", "chart.png", "image/png", 1024);
                NodeOutput out = NodeOutput.builder()
                        .addString("caption", "Sales Chart Q1")
                        .addImage("chart", img)
                        .build();
                return HandleResult.output(out);
            }
        });

        String json = """
                {
                  "id": "img", "name": "Img", "startNodeId": "gen",
                  "nodes": [
                    { "id": "gen", "type": "generate_image", "name": "Gen", "next": "log" },
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
        String lastLog = (String) result.getVariables().get("_lastLog");
        assertTrue(lastLog.contains("Sales Chart Q1"));
        assertTrue(lastLog.contains("img-001"));
    }

    @Test
    void literalInputValues() {
        String json = """
                {
                  "id": "lit", "name": "Lit", "startNodeId": "form",
                  "nodes": [
                    {
                      "id": "form", "type": "submit_form", "name": "Form",
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
        FileReference receipt = (FileReference) result.getVariables().get("form.receiptFile");
        String content = new String(fileStorage.download(receipt.getFileId()), StandardCharsets.UTF_8);
        assertTrue(content.contains("name=direct-value"));
        assertTrue(content.contains("count=42"));
    }

    // ---- condition on node output reference ---------------------------------

    @Test
    void conditionNode_referencesNodeOutput() {
        String json = """
                {
                  "id": "cond-ref", "name": "CondRef", "startNodeId": "form",
                  "nodes": [
                    {
                      "id": "form", "type": "submit_form", "name": "Form",
                      "inputMappings": [{ "name": "x", "source": "y", "dataType": "STRING" }],
                      "next": "check"
                    },
                    {
                      "id": "check", "type": "condition", "name": "Check",
                      "branches": [
                        { "condition": "form.result == SUBMITTED", "target": "ok" }
                      ],
                      "next": "fail"
                    },
                    { "id": "ok",   "type": "task", "name": "OK",   "properties": { "path": "ok" },   "next": "end" },
                    { "id": "fail", "type": "task", "name": "Fail", "properties": { "path": "fail" }, "next": "end" },
                    { "id": "end",  "type": "end",  "name": "End" }
                  ]
                }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("ok", result.getVariables().get("path"));
    }
}
