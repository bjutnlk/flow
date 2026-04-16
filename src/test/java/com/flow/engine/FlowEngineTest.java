package com.flow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.engine.exception.FlowValidationException;
import com.flow.engine.handler.*;
import com.flow.engine.handler.capability.AggregateFileHandler;
import com.flow.engine.handler.capability.SubmitFormHandler;
import com.flow.engine.model.*;
import com.flow.engine.recorder.ExecutionLog;
import com.flow.engine.recorder.InMemoryExecutionRecorder;
import com.flow.engine.recorder.NodeExecutionLog;
import com.flow.engine.resolve.InputResolver;
import com.flow.engine.service.FlowEngine;
import com.flow.engine.service.FlowValidator;
import com.flow.engine.storage.InMemoryFileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowEngineTest {

    private FlowEngine engine;
    private InMemoryFileStorageService fileStorage;
    private InMemoryExecutionRecorder recorder;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        fileStorage = new InMemoryFileStorageService();
        recorder = new InMemoryExecutionRecorder();
        InputResolver resolver = new InputResolver(fileStorage);
        FlowValidator validator = new FlowValidator();

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
        engine = new FlowEngine(mapper, resolver, recorder, validator, handlers);
    }

    // ======================================================================
    // Validation: all checks run before execution, errors returned together
    // ======================================================================

    @Test
    void validate_versionMissing() {
        String json = """
                { "id": "nv", "name": "X", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("Version")));
    }

    @Test
    void validate_versionTooOld() {
        String json = """
                { "version": "0.5", "id": "old", "name": "X", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("0.5")));
    }

    @Test
    void validate_noNodes() {
        String json = """
                { "version": "2.0", "id": "empty", "name": "X", "nodes": [] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("no nodes")));
    }

    @Test
    void validate_noEndNode() {
        String json = """
                { "version": "2.0", "id": "ne", "name": "X", "startNodeId": "a",
                  "nodes": [{ "id": "a", "type": "task", "name": "A" }] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("end")));
    }

    @Test
    void validate_startNodeNotExists() {
        String json = """
                { "version": "2.0", "id": "ns", "name": "X", "startNodeId": "ghost",
                  "nodes": [{ "id": "a", "type": "end", "name": "A" }] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("ghost")));
    }

    @Test
    void validate_duplicateIds() {
        String json = """
                { "version": "2.0", "id": "dup", "name": "X", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "next": "a2" },
                    { "id": "a", "type": "end",  "name": "A2" }
                  ] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("Duplicate")));
    }

    @Test
    void validate_brokenNextReference() {
        String json = """
                { "version": "2.0", "id": "br", "name": "X", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "next": "ghost" },
                    { "id": "e", "type": "end",  "name": "E" }
                  ] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("ghost")));
    }

    @Test
    void validate_brokenPrevNodeReference() {
        String json = """
                { "version": "2.0", "id": "bp", "name": "X",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A" },
                    { "id": "e", "type": "end",  "name": "E", "prevNodes": ["ghost"] }
                  ] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("ghost")));
    }

    @Test
    void validate_unreachableNode() {
        String json = """
                { "version": "2.0", "id": "ur", "name": "X", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "next": "e" },
                    { "id": "e", "type": "end",  "name": "E" },
                    { "id": "orphan", "type": "task", "name": "Orphan", "next": "e" }
                  ] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("orphan") && e.contains("unreachable")));
    }

    @Test
    void validate_cycle_detected() {
        String json = """
                { "version": "2.0", "id": "cyc", "name": "X", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "next": "b" },
                    { "id": "b", "type": "task", "name": "B", "next": "a" },
                    { "id": "e", "type": "end",  "name": "E" }
                  ] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("cycle")));
    }

    @Test
    void validate_endNotReachable() {
        String json = """
                { "version": "2.0", "id": "enr", "name": "X", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A" },
                    { "id": "e", "type": "end",  "name": "E" }
                  ] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("end") && e.contains("reachable")));
    }

    @Test
    void validate_multipleErrors_reportedTogether() {
        String json = """
                { "id": "multi", "name": "X", "startNodeId": "ghost",
                  "nodes": [{ "id": "a", "type": "task", "name": "A" }] }
                """;
        FlowValidationException ex = assertThrows(
                FlowValidationException.class, () -> engine.execute(engine.parse(json)));
        assertTrue(ex.getErrors().size() >= 2, "should report version + end node + start missing etc");
    }

    @Test
    void validate_validFlow_passes() {
        String json = """
                { "version": "2.0", "id": "ok", "name": "OK",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S" },
                    { "id": "t", "type": "task",  "name": "T", "prevNodes": ["s"] },
                    { "id": "e", "type": "end",   "name": "E", "prevNodes": ["t"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
    }

    @Test
    void versionCompare() {
        assertEquals(0, FlowEngine.compareVersions("1.0", "1.0"));
        assertTrue(FlowEngine.compareVersions("2.0", "1.0") > 0);
        assertTrue(FlowEngine.compareVersions("0.9", "1.0") < 0);
        assertTrue(FlowEngine.compareVersions("1.0.1", "1.0") > 0);
        assertTrue(FlowEngine.compareVersions("1.2", "1.10") < 0);
    }

    // ======================================================================
    // prevNodes graph resolution
    // ======================================================================

    @Test
    void resolve_buildNextFromPrevNodes() {
        String json = """
                { "version": "2.0", "id": "g", "name": "G",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A" },
                    { "id": "b", "type": "task", "name": "B", "prevNodes": ["a"] },
                    { "id": "c", "type": "end",  "name": "C", "prevNodes": ["b"] }
                  ] }
                """;
        FlowDefinition def = engine.parse(json);
        assertEquals("a", def.getStartNodeId());
        assertEquals(List.of("b"), def.toNodeMap().get("a").getNext());

        FlowResult result = engine.execute(def);
        assertTrue(result.isSuccess());
        assertEquals(List.of("a", "b", "c"), result.getExecutionTrace());
    }

    @Test
    void resolve_forkDetected() {
        String json = """
                { "version": "2.0", "id": "fork", "name": "Fork",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A" },
                    { "id": "b", "type": "task", "name": "B", "prevNodes": ["a"] },
                    { "id": "c", "type": "task", "name": "C", "prevNodes": ["a"] },
                    { "id": "d", "type": "end",  "name": "D", "prevNodes": ["b", "c"] }
                  ] }
                """;
        FlowDefinition def = engine.parse(json);
        assertTrue(def.toNodeMap().get("a").isFork());
        assertTrue(def.toNodeMap().get("d").isJoin());
    }

    // ======================================================================
    // Fork-join execution via prevNodes
    // ======================================================================

    @Test
    void forkJoin_A_B_D_C() {
        String json = """
                { "version": "2.0", "id": "fj", "name": "FJ",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "properties": { "origin": "a" } },
                    { "id": "b", "type": "task", "name": "B", "prevNodes": ["a"], "properties": { "bResult": "fromB" } },
                    { "id": "d", "type": "task", "name": "D", "prevNodes": ["a"], "properties": { "dResult": "fromD" } },
                    { "id": "c", "type": "log",  "name": "C", "prevNodes": ["b", "d"],
                      "properties": { "message": "${bResult} + ${dResult}" } },
                    { "id": "e", "type": "end",  "name": "E", "prevNodes": ["c"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        int cIdx = result.getExecutionTrace().indexOf("c");
        assertTrue(cIdx > result.getExecutionTrace().indexOf("b"));
        assertTrue(cIdx > result.getExecutionTrace().indexOf("d"));
        assertEquals("fromB + fromD", result.getVariables().get("_lastLog"));
    }

    @Test
    void forkJoin_files() {
        InputStream is = getClass().getResourceAsStream("/flows/fork-join-flow.json");
        assertNotNull(is);
        FlowContext ctx = new FlowContext("fork-join-flow");
        ctx.setVariable("dataSource", "API");

        FlowResult result = engine.execute(engine.parse(is), ctx);

        assertTrue(result.isSuccess());
        assertTrue(result.getVariables().get("merge.mergedFile") instanceof FileReference);
        assertEquals(2, result.getVariables().get("merge.fileCount"));
    }

    @Test
    void forkJoin_threePaths() {
        String json = """
                { "version": "2.0", "id": "fj3", "name": "FJ3",
                  "nodes": [
                    { "id": "root",  "type": "task", "name": "Root" },
                    { "id": "p1",    "type": "task", "name": "P1", "prevNodes": ["root"], "properties": {"p1":"done"} },
                    { "id": "p2",    "type": "task", "name": "P2", "prevNodes": ["root"], "properties": {"p2":"done"} },
                    { "id": "p3",    "type": "task", "name": "P3", "prevNodes": ["root"], "properties": {"p3":"done"} },
                    { "id": "merge", "type": "task", "name": "Merge", "prevNodes": ["p1","p2","p3"],
                      "properties": {"merged": true} },
                    { "id": "end",   "type": "end",  "name": "End", "prevNodes": ["merge"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals(true, result.getVariables().get("merged"));
    }

    // ======================================================================
    // Recording
    // ======================================================================

    @Test
    void recording_onSuccess() {
        String json = """
                { "version": "2.0", "id": "rec", "name": "Rec", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "t" },
                    { "id": "t", "type": "task",  "name": "T", "next": "e" },
                    { "id": "e", "type": "end",   "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertEquals(ExecutionLog.Status.SUCCESS, log.getStatus());
        assertEquals(3, log.getTotalNodes());
    }

    @Test
    void recording_capturesNodeDetails() {
        String json = """
                { "version": "2.0", "id": "det", "name": "Det", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start", "next": "e" },
                    { "id": "e", "type": "end",   "name": "End" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        List<NodeExecutionLog> logs = recorder.getExecutionLog(result.getExecutionId()).getNodeExecutionLogs();
        assertEquals("s", logs.get(0).getNodeId());
        assertEquals("start", logs.get(0).getNodeType());
    }

    @Test
    void recording_onNodeFailure() {
        engine.registerHandler(new NodeHandler() {
            @Override public String getType() { return "bomb"; }
            @Override public HandleResult execute(FlowNode node, FlowContext context) {
                throw new RuntimeException("boom!");
            }
        });

        String json = """
                { "version": "2.0", "id": "bomb", "name": "Bomb", "startNodeId": "b",
                  "nodes": [
                    { "id": "b", "type": "bomb", "name": "B", "next": "e" },
                    { "id": "e", "type": "end",  "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertEquals(1, log.getFailedNodes());
        assertEquals("boom!", log.getNodeExecutionLogs().get(0).getErrorMessage());
    }

    @Test
    void recording_capturesSnapshots() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf", "data".getBytes());

        String json = """
                { "version": "2.0", "id": "snap", "name": "Snap", "startNodeId": "form",
                  "nodes": [
                    { "id": "form", "type": "submit_form", "name": "Form",
                      "inputMappings": [
                        { "name": "user", "source": "${userName}", "dataType": "STRING" }
                      ], "next": "e" },
                    { "id": "e", "type": "end", "name": "E" }
                  ] }
                """;
        FlowContext ctx = new FlowContext("snap");
        ctx.setVariable("userName", "Alice");

        FlowResult result = engine.execute(engine.parse(json), ctx);
        NodeExecutionLog formLog = recorder.getExecutionLog(result.getExecutionId()).getNodeExecutionLogs().get(0);
        assertEquals("Alice", formLog.getInputSnapshot().get("user"));
    }

    // ======================================================================
    // Basic flow tests (backward compat)
    // ======================================================================

    @Test
    void orderFlow_highAmount() {
        InputStream is = getClass().getResourceAsStream("/flows/order-flow.json");
        FlowContext ctx = new FlowContext("order-flow");
        ctx.setVariable("amount", 2000);
        FlowResult result = engine.execute(engine.parse(is), ctx);
        assertTrue(result.isSuccess());
        assertEquals("PENDING_APPROVAL", result.getVariables().get("orderStatus"));
    }

    @Test
    void orderFlow_lowAmount() {
        InputStream is = getClass().getResourceAsStream("/flows/order-flow.json");
        FlowContext ctx = new FlowContext("order-flow");
        ctx.setVariable("amount", 500);
        FlowResult result = engine.execute(engine.parse(is), ctx);
        assertTrue(result.isSuccess());
        assertEquals("APPROVED", result.getVariables().get("orderStatus"));
    }

    @Test
    void simpleFlow() {
        String json = """
                { "version": "2.0", "id": "s", "name": "S", "startNodeId": "s",
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
    void contextShared() {
        String json = """
                { "version": "2.0", "id": "c", "name": "C", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "task", "name": "A", "properties": { "greeting": "hello" }, "next": "b" },
                    { "id": "b", "type": "log",  "name": "B", "properties": { "message": "${greeting} world" }, "next": "c" },
                    { "id": "c", "type": "end",  "name": "C" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertEquals("hello world", result.getVariables().get("_lastLog"));
    }

    @Test
    void crossNodeReference() {
        fileStorage.seed("seed", "d.txt", "text/plain", "data".getBytes());
        String json = """
                { "version": "2.0", "id": "xr", "name": "XR", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "submit_form", "name": "A",
                      "inputMappings": [{ "name": "in", "source": "file:seed", "dataType": "FILE" }], "next": "b" },
                    { "id": "b", "type": "aggregate_file", "name": "B",
                      "inputMappings": [{ "name": "f", "source": "${a.receiptFile}", "dataType": "FILE" }], "next": "e" },
                    { "id": "e", "type": "end", "name": "E" }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
    }

    @Test
    void pipeline() {
        fileStorage.seed("raw-data-001", "raw.csv", "text/csv", "1,Alice".getBytes());
        InputStream is = getClass().getResourceAsStream("/flows/pipeline-flow.json");
        FlowContext ctx = new FlowContext("pipeline-flow");
        ctx.setVariable("dataSource", "CRM");
        FlowResult result = engine.execute(engine.parse(is), ctx);
        assertTrue(result.isSuccess());
    }

    @Test
    void switchNode() {
        String json = """
                { "version": "2.0", "id": "sw", "name": "Sw", "startNodeId": "set",
                  "nodes": [
                    { "id": "set", "type": "task", "name": "Set", "properties": { "status": "REJECTED" }, "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
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
        assertEquals("rejected", result.getVariables().get("path"));
    }

    @Test
    void forEachNode() {
        String json = """
                { "version": "2.0", "id": "fe", "name": "FE", "startNodeId": "setup",
                  "nodes": [
                    { "id": "setup", "type": "task", "name": "Setup", "next": "loop" },
                    { "id": "loop", "type": "foreach", "name": "Loop",
                      "properties": { "collection": "items", "itemVar": "cur", "indexVar": "idx" }, "next": "end" },
                    { "id": "end", "type": "end", "name": "End" }
                  ] }
                """;
        FlowContext ctx = new FlowContext("fe");
        ctx.setVariable("items", List.of("a", "b", "c"));
        FlowResult result = engine.execute(engine.parse(json), ctx);
        assertEquals(3, result.getVariables().get("loop.count"));
    }

    @Test
    void documentFlow() {
        fileStorage.seed("doc-001", "app.pdf", "application/pdf", "PDF".getBytes());
        InputStream is = getClass().getResourceAsStream("/flows/document-flow.json");
        FlowContext ctx = new FlowContext("document-flow");
        ctx.setVariable("applicantName", "Bob");
        ctx.setVariable("amount", 5000);
        FlowResult result = engine.execute(engine.parse(is), ctx);
        assertTrue(result.isSuccess());
    }
}
