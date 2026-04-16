package com.flow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.engine.exception.UnsupportedVersionException;
import com.flow.engine.handler.*;
import com.flow.engine.handler.capability.AggregateFileHandler;
import com.flow.engine.handler.capability.SubmitFormHandler;
import com.flow.engine.model.*;
import com.flow.engine.recorder.ExecutionLog;
import com.flow.engine.recorder.InMemoryExecutionRecorder;
import com.flow.engine.recorder.NodeExecutionLog;
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
    private InMemoryExecutionRecorder recorder;
    private InputResolver resolver;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        fileStorage = new InMemoryFileStorageService();
        recorder = new InMemoryExecutionRecorder();
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
        engine = new FlowEngine(mapper, resolver, recorder, handlers);
    }

    // ---- version validation -------------------------------------------------

    @Test
    void versionMissing_throwsUnsupported() {
        String json = """
                { "id": "no-ver", "name": "X", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        FlowDefinition def = engine.parse(json);
        assertThrows(UnsupportedVersionException.class, () -> engine.execute(def));
    }

    @Test
    void versionTooOld_throwsUnsupported() {
        String json = """
                { "version": "0.5", "id": "old", "name": "Old", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        FlowDefinition def = engine.parse(json);
        UnsupportedVersionException ex = assertThrows(
                UnsupportedVersionException.class, () -> engine.execute(def));
        assertTrue(ex.getMessage().contains("0.5"));
        assertTrue(ex.getMessage().contains("1.0"));
    }

    @Test
    void versionExactlyMinimum_isAccepted() {
        String json = """
                { "version": "1.0", "id": "min", "name": "Min", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
    }

    @Test
    void versionCurrent_isAccepted() {
        String json = """
                { "version": "2.0", "id": "cur", "name": "Cur", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
    }

    @Test
    void versionFuture_isAccepted() {
        String json = """
                { "version": "99.0", "id": "future", "name": "F", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
    }

    @Test
    void versionCompare_multiSegment() {
        assertTrue(FlowEngine.compareVersions("1.0", "1.0") == 0);
        assertTrue(FlowEngine.compareVersions("2.0", "1.0") > 0);
        assertTrue(FlowEngine.compareVersions("0.9", "1.0") < 0);
        assertTrue(FlowEngine.compareVersions("1.0.1", "1.0") > 0);
        assertTrue(FlowEngine.compareVersions("1.2", "1.10") < 0);
    }

    // ---- execution recording ------------------------------------------------

    @Test
    void executionLog_isRecorded_onSuccess() {
        String json = """
                { "version": "2.0", "id": "rec-ok", "name": "RecOK", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "t" },
                    { "id": "t", "type": "task",  "name": "T", "properties": {"x":1}, "next": "e" },
                    { "id": "e", "type": "end",   "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertNotNull(result.getExecutionId());

        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertNotNull(log);
        assertEquals(ExecutionLog.Status.SUCCESS, log.getStatus());
        assertEquals("rec-ok", log.getFlowId());
        assertEquals("2.0", log.getFlowVersion());
        assertEquals(3, log.getTotalNodes());
        assertEquals(3, log.getSuccessNodes());
        assertEquals(0, log.getFailedNodes());
        assertTrue(log.getTotalDurationMs() >= 0);
        assertNotNull(log.getStartTime());
        assertNotNull(log.getEndTime());
    }

    @Test
    void executionLog_capturesNodeDetails() {
        String json = """
                { "version": "2.0", "id": "detail", "name": "Detail", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start", "next": "t" },
                    { "id": "t", "type": "task",  "name": "DoWork", "properties": {"k":"v"}, "next": "e" },
                    { "id": "e", "type": "end",   "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());

        List<NodeExecutionLog> nodeLogs = log.getNodeExecutionLogs();
        assertEquals(3, nodeLogs.size());

        NodeExecutionLog startLog = nodeLogs.get(0);
        assertEquals("s", startLog.getNodeId());
        assertEquals("start", startLog.getNodeType());
        assertEquals("Start", startLog.getNodeName());
        assertEquals(1, startLog.getStepIndex());
        assertEquals(NodeExecutionLog.Status.SUCCESS, startLog.getStatus());

        NodeExecutionLog taskLog = nodeLogs.get(1);
        assertEquals("t", taskLog.getNodeId());
        assertEquals("task", taskLog.getNodeType());
        assertEquals(2, taskLog.getStepIndex());

        NodeExecutionLog endLog = nodeLogs.get(2);
        assertEquals("e", endLog.getNodeId());
        assertEquals(3, endLog.getStepIndex());
    }

    @Test
    void executionLog_isRecorded_onFailure() {
        String json = """
                { "version": "2.0", "id": "rec-fail", "name": "RecFail", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "next": "missing" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertFalse(result.isSuccess());
        assertNotNull(result.getExecutionId());

        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertNotNull(log);
        assertEquals(ExecutionLog.Status.FAILED, log.getStatus());
        assertNotNull(log.getErrorMessage());
        assertTrue(log.getErrorMessage().contains("missing"));
        assertEquals(1, log.getSuccessNodes());
    }

    @Test
    void executionLog_capturesNodeFailure() {
        engine.registerHandler(new NodeHandler() {
            @Override
            public String getType() { return "bomb"; }
            @Override
            public HandleResult execute(FlowNode node, FlowContext context) {
                throw new RuntimeException("boom!");
            }
        });

        String json = """
                { "version": "2.0", "id": "bomb-flow", "name": "Bomb", "startNodeId": "b",
                  "nodes": [
                    { "id": "b", "type": "bomb", "name": "Bomb" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertFalse(result.isSuccess());

        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertEquals(1, log.getFailedNodes());

        NodeExecutionLog nodeLog = log.getNodeExecutionLogs().get(0);
        assertEquals(NodeExecutionLog.Status.FAILED, nodeLog.getStatus());
        assertEquals("boom!", nodeLog.getErrorMessage());
        assertTrue(nodeLog.getDurationMs() >= 0);
    }

    @Test
    void executionLog_capturesInputOutputSnapshots() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf",
                "data".getBytes(StandardCharsets.UTF_8));

        String json = """
                { "version": "2.0", "id": "snap", "name": "Snap", "startNodeId": "form",
                  "nodes": [
                    {
                      "id": "form", "type": "submit_form", "name": "Form",
                      "inputMappings": [
                        { "name": "user", "source": "${userName}", "dataType": "STRING" },
                        { "name": "doc",  "source": "file:doc-001", "dataType": "FILE" }
                      ],
                      "next": "e"
                    },
                    { "id": "e", "type": "end", "name": "E" }
                  ] }
                """;
        FlowContext ctx = new FlowContext("snap");
        ctx.setVariable("userName", "Alice");

        FlowResult result = engine.execute(engine.parse(json), ctx);
        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());

        NodeExecutionLog formLog = log.getNodeExecutionLogs().get(0);
        assertNotNull(formLog.getInputSnapshot());
        assertEquals("Alice", formLog.getInputSnapshot().get("user"));
        assertNotNull(formLog.getOutputSnapshot());
        assertTrue(formLog.getOutputSnapshot().containsKey("result"));
        assertTrue(formLog.getOutputSnapshot().containsKey("receiptFile"));
    }

    // ---- basic flow tests (with version) ------------------------------------

    @Test
    void executeOrderFlow_highAmount() {
        InputStream is = getClass().getResourceAsStream("/flows/order-flow.json");
        assertNotNull(is);

        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("amount", 2000);

        FlowResult result = engine.execute(def, ctx);

        assertTrue(result.isSuccess());
        assertEquals("PENDING_APPROVAL", result.getVariables().get("orderStatus"));
        assertEquals(
                List.of("start", "validate", "check-amount", "approve-manager", "log-result", "end"),
                result.getExecutionTrace()
        );
        assertNotNull(result.getExecutionId());
    }

    @Test
    void executeOrderFlow_lowAmount() {
        InputStream is = getClass().getResourceAsStream("/flows/order-flow.json");
        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("amount", 500);

        FlowResult result = engine.execute(def, ctx);

        assertTrue(result.isSuccess());
        assertEquals("APPROVED", result.getVariables().get("orderStatus"));
    }

    @Test
    void simpleFlow() {
        String json = """
                { "version": "2.0", "id": "simple", "name": "Simple", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "t" },
                    { "id": "t", "type": "task",  "name": "T", "properties": { "x": 42 }, "next": "e" },
                    { "id": "e", "type": "end",   "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals(42, result.getVariables().get("x"));
    }

    @Test
    void contextSharedBetweenNodes() {
        String json = """
                { "version": "2.0", "id": "ctx", "name": "Ctx", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "properties": { "greeting": "hello" }, "next": "b" },
                    { "id": "b", "type": "log",  "name": "B", "properties": { "message": "${greeting} world" }, "next": "c" },
                    { "id": "c", "type": "end",  "name": "C" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("hello world", result.getVariables().get("_lastLog"));
    }

    @Test
    void missingNodeReturnsFailure() {
        String json = """
                { "version": "2.0", "id": "bad", "name": "Bad", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "next": "nonexistent" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("nonexistent"));
    }

    @Test
    void customHandlerAtRuntime() {
        engine.registerHandler(new NodeHandler() {
            @Override public String getType() { return "custom"; }
            @Override public HandleResult execute(FlowNode node, FlowContext context) {
                context.setVariable("customRan", true);
                return HandleResult.none();
            }
        });

        String json = """
                { "version": "2.0", "id": "custom", "name": "Custom", "startNodeId": "c",
                  "nodes": [
                    { "id": "c", "type": "custom", "name": "C", "next": "e" },
                    { "id": "e", "type": "end",    "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals(true, result.getVariables().get("customRan"));
    }

    @Test
    void prePopulatedContext() {
        String json = """
                { "version": "2.0", "id": "pre", "name": "Pre", "startNodeId": "log",
                  "nodes": [
                    { "id": "log", "type": "log", "name": "L", "properties": { "message": "user=${userId}" }, "next": "end" },
                    { "id": "end", "type": "end", "name": "E" }
                  ] }
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
                { "version": "2.0", "id": "sf", "name": "SF", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "form" },
                    { "id": "form", "type": "submit_form", "name": "Form",
                      "inputMappings": [
                        { "name": "userName", "source": "${user}", "dataType": "STRING" },
                        { "name": "doc", "source": "file:doc-001", "dataType": "FILE" }
                      ], "next": "e" },
                    { "id": "e", "type": "end", "name": "E" }
                  ] }
                """;
        FlowContext ctx = new FlowContext("sf");
        ctx.setVariable("user", "Alice");

        FlowResult result = engine.execute(engine.parse(json), ctx);

        assertTrue(result.isSuccess());
        assertEquals("SUBMITTED", result.getVariables().get("form.result"));
    }

    @Test
    void crossNodeReference_C_readsA_output() {
        fileStorage.seed("seed-file", "data.txt", "text/plain",
                "original data".getBytes(StandardCharsets.UTF_8));

        String json = """
                { "version": "2.0", "id": "xref", "name": "XRef", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "submit_form", "name": "A",
                      "inputMappings": [{ "name": "input", "source": "file:seed-file", "dataType": "FILE" }],
                      "next": "b" },
                    { "id": "b", "type": "task", "name": "B", "properties": { "status": "processed" }, "next": "c" },
                    { "id": "c", "type": "aggregate_file", "name": "C",
                      "inputMappings": [{ "name": "fileFromA", "source": "${a.receiptFile}", "dataType": "FILE" }],
                      "next": "end" },
                    { "id": "end", "type": "end", "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals(List.of("a", "b", "c", "end"), result.getExecutionTrace());
    }

    // ---- consecutive capability nodes ---------------------------------------

    @Test
    void consecutiveCapabilityNodes_pipeline() {
        fileStorage.seed("raw-data-001", "raw.csv", "text/csv",
                "id,name\n1,Alice".getBytes(StandardCharsets.UTF_8));

        InputStream is = getClass().getResourceAsStream("/flows/pipeline-flow.json");
        assertNotNull(is);

        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("dataSource", "CRM");

        FlowResult result = engine.execute(def, ctx);

        assertTrue(result.isSuccess());
        assertEquals(List.of("ingest", "transform", "validate", "export", "end"),
                result.getExecutionTrace());
    }

    @Test
    void capabilityNodes_directChain_noStartOrEnd() {
        String json = """
                { "version": "2.0", "id": "bare", "name": "Bare", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "submit_form", "name": "A",
                      "inputMappings": [{ "name": "f1", "source": "v1", "dataType": "STRING" }],
                      "next": "b" },
                    { "id": "b", "type": "submit_form", "name": "B",
                      "inputMappings": [{ "name": "prev", "source": "${a.receiptFile}", "dataType": "FILE" }],
                      "next": "c" },
                    { "id": "c", "type": "submit_form", "name": "C",
                      "inputMappings": [
                        { "name": "fromA", "source": "${a.result}", "dataType": "STRING" },
                        { "name": "fromB", "source": "${b.result}", "dataType": "STRING" }
                      ] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals(List.of("a", "b", "c"), result.getExecutionTrace());
    }

    // ---- document flow ------------------------------------------------------

    @Test
    void documentFlow_lowAmount() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf", "PDF".getBytes());
        InputStream is = getClass().getResourceAsStream("/flows/document-flow.json");
        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("applicantName", "Bob");
        ctx.setVariable("amount", 5000);

        FlowResult result = engine.execute(def, ctx);
        assertTrue(result.isSuccess());
        assertEquals(List.of("start", "submit", "check-amount", "log-result", "end"),
                result.getExecutionTrace());
    }

    @Test
    void documentFlow_highAmount() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf", "PDF".getBytes());
        InputStream is = getClass().getResourceAsStream("/flows/document-flow.json");
        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("applicantName", "Carol");
        ctx.setVariable("amount", 20000);

        FlowResult result = engine.execute(def, ctx);
        assertTrue(result.isSuccess());
        assertEquals(List.of("start", "submit", "check-amount", "aggregate", "log-result", "end"),
                result.getExecutionTrace());
    }

    // ---- switch node --------------------------------------------------------

    @Test
    void switchNode_matchesCase() {
        String json = """
                { "version": "2.0", "id": "sw", "name": "Sw", "startNodeId": "set",
                  "nodes": [
                    { "id": "set", "type": "task", "name": "Set", "properties": { "status": "REJECTED" }, "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Switch",
                      "properties": { "expression": "status" },
                      "branches": [
                        { "condition": "APPROVED", "target": "ok" },
                        { "condition": "REJECTED", "target": "fail" }
                      ], "next": "ok" },
                    { "id": "ok",   "type": "task", "name": "OK",   "properties": { "path": "approved" }, "next": "end" },
                    { "id": "fail", "type": "task", "name": "Fail", "properties": { "path": "rejected" }, "next": "end" },
                    { "id": "end",  "type": "end",  "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("rejected", result.getVariables().get("path"));
    }

    @Test
    void switchNode_defaultFallthrough() {
        String json = """
                { "version": "2.0", "id": "sw2", "name": "Sw2", "startNodeId": "set",
                  "nodes": [
                    { "id": "set", "type": "task", "name": "Set", "properties": { "status": "UNKNOWN" }, "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Switch",
                      "properties": { "expression": "status" },
                      "branches": [{ "condition": "APPROVED", "target": "ok" }],
                      "next": "def" },
                    { "id": "ok",  "type": "task", "name": "OK",  "properties": { "path": "approved" }, "next": "end" },
                    { "id": "def", "type": "task", "name": "Def", "properties": { "path": "default" },  "next": "end" },
                    { "id": "end", "type": "end",  "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("default", result.getVariables().get("path"));
    }

    @Test
    void switchNode_onNodeOutputReference() {
        String json = """
                { "version": "2.0", "id": "sw3", "name": "Sw3", "startNodeId": "form",
                  "nodes": [
                    { "id": "form", "type": "submit_form", "name": "Form",
                      "inputMappings": [{ "name": "x", "source": "y", "dataType": "STRING" }],
                      "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "form.result" },
                      "branches": [
                        { "condition": "SUBMITTED", "target": "ok" },
                        { "condition": "FAILED",    "target": "fail" }
                      ], "next": "fail" },
                    { "id": "ok",   "type": "task", "name": "OK",   "properties": { "path": "ok" },   "next": "end" },
                    { "id": "fail", "type": "task", "name": "Fail", "properties": { "path": "fail" }, "next": "end" },
                    { "id": "end",  "type": "end",  "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("ok", result.getVariables().get("path"));
    }

    // ---- foreach node -------------------------------------------------------

    @Test
    void forEachNode_iteratesOverList() {
        String json = """
                { "version": "2.0", "id": "fe", "name": "FE", "startNodeId": "setup",
                  "nodes": [
                    { "id": "setup", "type": "task", "name": "Setup", "next": "loop" },
                    { "id": "loop", "type": "foreach", "name": "Loop",
                      "properties": { "collection": "items", "itemVar": "current", "indexVar": "idx" },
                      "next": "log" },
                    { "id": "log", "type": "log", "name": "Log",
                      "properties": { "message": "processed ${loop.count} items" },
                      "next": "end" },
                    { "id": "end", "type": "end", "name": "End" }
                  ] }
                """;
        FlowContext ctx = new FlowContext("fe");
        ctx.setVariable("items", List.of("apple", "banana", "cherry"));

        FlowResult result = engine.execute(engine.parse(json), ctx);
        assertTrue(result.isSuccess());
        assertEquals(3, result.getVariables().get("loop.count"));
    }

    // ---- image / mixed output -----------------------------------------------

    @Test
    void nodeOutput_stringAndImage() {
        engine.registerHandler(new NodeHandler() {
            @Override public String getType() { return "generate_image"; }
            @Override public HandleResult execute(FlowNode node, FlowContext context) {
                FileReference img = new FileReference("img-001", "chart.png", "image/png", 1024);
                return HandleResult.output(NodeOutput.builder()
                        .addString("caption", "Sales Chart Q1")
                        .addImage("chart", img).build());
            }
        });

        String json = """
                { "version": "2.0", "id": "img", "name": "Img", "startNodeId": "gen",
                  "nodes": [
                    { "id": "gen", "type": "generate_image", "name": "Gen", "next": "log" },
                    { "id": "log", "type": "log", "name": "Log",
                      "properties": { "message": "Generated: ${gen.caption}" }, "next": "end" },
                    { "id": "end", "type": "end", "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("Sales Chart Q1", result.getVariables().get("gen.caption"));
    }

    @Test
    void literalInputValues() {
        String json = """
                { "version": "2.0", "id": "lit", "name": "Lit", "startNodeId": "form",
                  "nodes": [
                    { "id": "form", "type": "submit_form", "name": "Form",
                      "inputMappings": [
                        { "name": "name",  "source": "direct-value", "dataType": "STRING" },
                        { "name": "count", "source": "42",           "dataType": "NUMBER" }
                      ], "next": "end" },
                    { "id": "end", "type": "end", "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
    }

    @Test
    void conditionNode_referencesNodeOutput() {
        String json = """
                { "version": "2.0", "id": "cref", "name": "CRef", "startNodeId": "form",
                  "nodes": [
                    { "id": "form", "type": "submit_form", "name": "Form",
                      "inputMappings": [{ "name": "x", "source": "y", "dataType": "STRING" }],
                      "next": "check" },
                    { "id": "check", "type": "condition", "name": "Check",
                      "branches": [{ "condition": "form.result == SUBMITTED", "target": "ok" }],
                      "next": "fail" },
                    { "id": "ok",   "type": "task", "name": "OK",   "properties": { "path": "ok" },   "next": "end" },
                    { "id": "fail", "type": "task", "name": "Fail", "properties": { "path": "fail" }, "next": "end" },
                    { "id": "end",  "type": "end",  "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("ok", result.getVariables().get("path"));
    }

    // ---- execution recording on complex flow --------------------------------

    @Test
    void executionLog_complexFlow_tracksAllNodes() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf", "PDF".getBytes());

        InputStream is = getClass().getResourceAsStream("/flows/document-flow.json");
        FlowDefinition def = engine.parse(is);
        FlowContext ctx = new FlowContext(def.getId());
        ctx.setVariable("applicantName", "Dave");
        ctx.setVariable("amount", 20000);

        FlowResult result = engine.execute(def, ctx);
        assertTrue(result.isSuccess());

        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertEquals(6, log.getTotalNodes());
        assertEquals(6, log.getSuccessNodes());
        assertEquals(0, log.getFailedNodes());
        assertEquals("Document Processing Flow", log.getFlowName());

        List<String> nodeIds = log.getNodeExecutionLogs().stream()
                .map(NodeExecutionLog::getNodeId)
                .toList();
        assertEquals(List.of("start", "submit", "check-amount", "aggregate", "log-result", "end"),
                nodeIds);

        NodeExecutionLog submitLog = log.getNodeExecutionLogs().get(1);
        assertEquals("submit_form", submitLog.getNodeType());
        assertNotNull(submitLog.getOutputSnapshot());
        assertEquals("SUBMITTED", String.valueOf(submitLog.getOutputSnapshot().get("result")));
    }
}
