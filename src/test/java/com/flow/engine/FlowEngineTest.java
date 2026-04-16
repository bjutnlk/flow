package com.flow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.engine.exception.FlowValidationException;
import com.flow.engine.handler.*;
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

import java.util.List;
import java.util.Map;

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
                new SwitchNodeHandler(resolver)
        );
        engine = new FlowEngine(mapper, resolver, recorder, validator, handlers);
    }

    private void registerCapability(String type) {
        engine.registerHandler(new NodeHandler() {
            @Override public String getType() { return type; }
            @Override public HandleResult execute(FlowNode node, FlowContext context) {
                Map<String, Object> inputs = context.getResolvedInputs();
                NodeOutput.Builder b = NodeOutput.builder();
                b.addString("result", "DONE");
                inputs.forEach((k, v) -> b.add(k, NodeOutput.DataType.STRING, v));
                return HandleResult.output(b.build());
            }
        });
    }

    // ======================================================================
    // Start node: input parameters
    // ======================================================================

    @Test
    void startNode_setsInputParamsIntoContext() {
        String json = """
                { "version": "2.0", "id": "si", "name": "SI",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start",
                      "inputMappings": [
                        { "name": "userId", "source": "${userId}", "dataType": "STRING" },
                        { "name": "count",  "source": "${count}",  "dataType": "NUMBER" }
                      ] },
                    { "id": "e", "type": "end", "name": "End", "prevNodes": ["s"] }
                  ] }
                """;
        FlowContext ctx = new FlowContext("si");
        ctx.setVariable("userId", "U-100");
        ctx.setVariable("count", 5);

        FlowResult result = engine.execute(engine.parse(json), ctx);
        assertTrue(result.isSuccess());
        assertEquals("U-100", result.getVariables().get("userId"));
        assertEquals(5, result.getVariables().get("count"));
    }

    @Test
    void startNode_withStaticProperties() {
        String json = """
                { "version": "2.0", "id": "sp", "name": "SP",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start",
                      "properties": { "env": "production", "retryLimit": 3 } },
                    { "id": "e", "type": "end", "name": "End", "prevNodes": ["s"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("production", result.getVariables().get("env"));
        assertEquals(3, result.getVariables().get("retryLimit"));
    }

    // ======================================================================
    // End node: return values
    // ======================================================================

    @Test
    void endNode_collectsReturnValues() {
        registerCapability("process");

        String json = """
                { "version": "2.0", "id": "rv", "name": "RV",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start", "properties": { "status": "OK" } },
                    { "id": "p", "type": "process", "name": "Process", "prevNodes": ["s"],
                      "inputMappings": [{ "name": "data", "source": "${status}", "dataType": "STRING" }] },
                    { "id": "e", "type": "end", "name": "End", "prevNodes": ["p"],
                      "properties": {
                        "returnValues": {
                          "processResult": "${p.result}",
                          "originalStatus": "${status}"
                        }
                      } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("DONE", result.getReturnValues().get("processResult"));
        assertEquals("OK", result.getReturnValues().get("originalStatus"));
    }

    @Test
    void endNode_noReturnValues_emptyMap() {
        String json = """
                { "version": "2.0", "id": "nr", "name": "NR",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S" },
                    { "id": "e", "type": "end",   "name": "E", "prevNodes": ["s"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertTrue(result.getReturnValues().isEmpty());
    }

    // ======================================================================
    // Switch node: comparison operators
    // ======================================================================

    @Test
    void switch_exactMatch() {
        String json = """
                { "version": "2.0", "id": "sem", "name": "SEM", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "properties": { "status": "REJECTED" }, "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "status" },
                      "branches": [
                        { "condition": "APPROVED", "target": "ok" },
                        { "condition": "REJECTED", "target": "fail" }
                      ], "next": "ok" },
                    { "id": "ok",   "type": "start", "name": "OK",   "properties": { "path": "approved" }, "next": "e" },
                    { "id": "fail", "type": "start", "name": "Fail", "properties": { "path": "rejected" }, "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "path": "${path}" } } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("rejected", result.getReturnValues().get("path"));
    }

    @Test
    void switch_greaterThan() {
        String json = """
                { "version": "2.0", "id": "sgt", "name": "SGT", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "amount" },
                      "branches": [
                        { "condition": "> 1000", "target": "high" },
                        { "condition": "<= 1000", "target": "low" }
                      ] },
                    { "id": "high", "type": "start", "name": "H", "properties": { "tier": "HIGH" }, "next": "e" },
                    { "id": "low",  "type": "start", "name": "L", "properties": { "tier": "LOW" },  "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "tier": "${tier}" } } }
                  ] }
                """;
        FlowContext ctx = new FlowContext("sgt");
        ctx.setVariable("amount", 2000);

        FlowResult result = engine.execute(engine.parse(json), ctx);
        assertTrue(result.isSuccess());
        assertEquals("HIGH", result.getReturnValues().get("tier"));
    }

    @Test
    void switch_lessThanOrEqual() {
        String json = """
                { "version": "2.0", "id": "sle", "name": "SLE", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "score" },
                      "branches": [
                        { "condition": ">= 90", "target": "a" },
                        { "condition": ">= 60", "target": "b" },
                        { "condition": "< 60",  "target": "c" }
                      ] },
                    { "id": "a", "type": "start", "name": "A", "properties": { "grade": "A" }, "next": "e" },
                    { "id": "b", "type": "start", "name": "B", "properties": { "grade": "B" }, "next": "e" },
                    { "id": "c", "type": "start", "name": "C", "properties": { "grade": "C" }, "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "grade": "${grade}" } } }
                  ] }
                """;
        FlowContext ctx = new FlowContext("sle");
        ctx.setVariable("score", 75);

        FlowResult result = engine.execute(engine.parse(json), ctx);
        assertTrue(result.isSuccess());
        assertEquals("B", result.getReturnValues().get("grade"));
    }

    @Test
    void switch_notEquals() {
        String json = """
                { "version": "2.0", "id": "sne", "name": "SNE", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "properties": { "code": "ERR" }, "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "code" },
                      "branches": [
                        { "condition": "!= OK", "target": "err" },
                        { "condition": "== OK", "target": "ok" }
                      ] },
                    { "id": "ok",  "type": "start", "name": "OK",  "properties": { "r": "ok" },    "next": "e" },
                    { "id": "err", "type": "start", "name": "Err", "properties": { "r": "error" }, "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "result": "${r}" } } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("error", result.getReturnValues().get("result"));
    }

    @Test
    void switch_defaultFallthrough() {
        String json = """
                { "version": "2.0", "id": "sdf", "name": "SDF", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "properties": { "val": "UNKNOWN" }, "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "val" },
                      "branches": [{ "condition": "== YES", "target": "y" }],
                      "next": "n" },
                    { "id": "y", "type": "start", "name": "Y", "properties": { "r": "yes" },     "next": "e" },
                    { "id": "n", "type": "start", "name": "N", "properties": { "r": "default" }, "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "result": "${r}" } } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("default", result.getReturnValues().get("result"));
    }

    @Test
    void switch_onNodeOutput() {
        registerCapability("process");

        String json = """
                { "version": "2.0", "id": "sno", "name": "SNO", "startNodeId": "s",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S", "next": "p" },
                    { "id": "p", "type": "process", "name": "P",
                      "inputMappings": [{ "name": "x", "source": "y", "dataType": "STRING" }],
                      "next": "sw" },
                    { "id": "sw", "type": "switch", "name": "Sw",
                      "properties": { "expression": "p.result" },
                      "branches": [
                        { "condition": "== DONE", "target": "ok" }
                      ], "next": "fail" },
                    { "id": "ok",   "type": "start", "name": "OK",   "properties": { "r": "ok" },   "next": "e" },
                    { "id": "fail", "type": "start", "name": "Fail", "properties": { "r": "fail" }, "next": "e" },
                    { "id": "e", "type": "end", "name": "E",
                      "properties": { "returnValues": { "result": "${r}" } } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        assertTrue(result.isSuccess());
        assertEquals("ok", result.getReturnValues().get("result"));
    }

    // ======================================================================
    // Full flow: start → capability → switch → end with return
    // ======================================================================

    @Test
    void fullFlow() {
        registerCapability("submit_form");

        String json = """
                { "version": "2.0", "id": "full", "name": "Full Flow", "startNodeId": "start",
                  "nodes": [
                    { "id": "start", "type": "start", "name": "Begin",
                      "inputMappings": [
                        { "name": "userName", "source": "${userName}", "dataType": "STRING" },
                        { "name": "amount",   "source": "${amount}",  "dataType": "NUMBER" }
                      ], "next": "submit" },
                    { "id": "submit", "type": "submit_form", "name": "Submit",
                      "inputMappings": [
                        { "name": "user",   "source": "${userName}", "dataType": "STRING" },
                        { "name": "amount", "source": "${amount}",   "dataType": "NUMBER" }
                      ], "next": "check" },
                    { "id": "check", "type": "switch", "name": "Check",
                      "properties": { "expression": "amount" },
                      "branches": [
                        { "condition": "> 10000",  "target": "high" },
                        { "condition": "<= 10000", "target": "low" }
                      ] },
                    { "id": "high", "type": "start", "name": "High", "properties": { "tier": "PREMIUM" },  "next": "end" },
                    { "id": "low",  "type": "start", "name": "Low",  "properties": { "tier": "STANDARD" }, "next": "end" },
                    { "id": "end", "type": "end", "name": "Done",
                      "properties": {
                        "returnValues": {
                          "tier": "${tier}",
                          "submitResult": "${submit.result}"
                        }
                      } }
                  ] }
                """;
        FlowContext ctx = new FlowContext("full");
        ctx.setVariable("userName", "Alice");
        ctx.setVariable("amount", 50000);

        FlowResult result = engine.execute(engine.parse(json), ctx);

        assertTrue(result.isSuccess());
        assertEquals("PREMIUM", result.getReturnValues().get("tier"));
        assertEquals("DONE", result.getReturnValues().get("submitResult"));
    }

    // ======================================================================
    // Validation
    // ======================================================================

    @Test
    void validate_versionMissing() {
        String json = """
                { "id": "nv", "name": "X", "startNodeId": "e",
                  "nodes": [{ "id": "e", "type": "end", "name": "E" }] }
                """;
        assertThrows(FlowValidationException.class, () -> engine.execute(engine.parse(json)));
    }

    @Test
    void validate_noEndNode() {
        String json = """
                { "version": "2.0", "id": "ne", "name": "X",
                  "nodes": [{ "id": "a", "type": "start", "name": "A" }] }
                """;
        assertThrows(FlowValidationException.class, () -> engine.execute(engine.parse(json)));
    }

    @Test
    void validate_cycle() {
        String json = """
                { "version": "2.0", "id": "cyc", "name": "X", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "start", "name": "A", "next": "b" },
                    { "id": "b", "type": "start", "name": "B", "next": "a" },
                    { "id": "e", "type": "end",   "name": "E" }
                  ] }
                """;
        assertThrows(FlowValidationException.class, () -> engine.execute(engine.parse(json)));
    }

    @Test
    void validate_unreachableNode() {
        String json = """
                { "version": "2.0", "id": "ur", "name": "X", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "start", "name": "A", "next": "e" },
                    { "id": "e", "type": "end",   "name": "E" },
                    { "id": "orphan", "type": "start", "name": "O", "next": "e" }
                  ] }
                """;
        assertThrows(FlowValidationException.class, () -> engine.execute(engine.parse(json)));
    }

    @Test
    void validate_brokenReference() {
        String json = """
                { "version": "2.0", "id": "br", "name": "X", "startNodeId": "a",
                  "nodes": [
                    { "id": "a", "type": "start", "name": "A", "next": "ghost" },
                    { "id": "e", "type": "end",   "name": "E" }
                  ] }
                """;
        assertThrows(FlowValidationException.class, () -> engine.execute(engine.parse(json)));
    }

    @Test
    void validate_validMinimalFlow() {
        String json = """
                { "version": "2.0", "id": "ok", "name": "OK",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S" },
                    { "id": "e", "type": "end",   "name": "E", "prevNodes": ["s"] }
                  ] }
                """;
        assertTrue(engine.execute(engine.parse(json)).isSuccess());
    }

    // ======================================================================
    // Fork-join via prevNodes (capability nodes)
    // ======================================================================

    @Test
    void forkJoin_viaPrevNodes() {
        registerCapability("work");

        String json = """
                { "version": "2.0", "id": "fj", "name": "FJ",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S" },
                    { "id": "b", "type": "work", "name": "B", "prevNodes": ["s"],
                      "inputMappings": [{ "name": "x", "source": "bVal", "dataType": "STRING" }] },
                    { "id": "d", "type": "work", "name": "D", "prevNodes": ["s"],
                      "inputMappings": [{ "name": "x", "source": "dVal", "dataType": "STRING" }] },
                    { "id": "e", "type": "end", "name": "E", "prevNodes": ["b", "d"],
                      "properties": {
                        "returnValues": {
                          "bResult": "${b.result}",
                          "dResult": "${d.result}"
                        }
                      } }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));

        assertTrue(result.isSuccess());
        assertEquals("DONE", result.getReturnValues().get("bResult"));
        assertEquals("DONE", result.getReturnValues().get("dResult"));

        int eIdx = result.getExecutionTrace().indexOf("e");
        assertTrue(eIdx > result.getExecutionTrace().indexOf("b"));
        assertTrue(eIdx > result.getExecutionTrace().indexOf("d"));
    }

    // ======================================================================
    // Recording
    // ======================================================================

    @Test
    void recording_onSuccess() {
        String json = """
                { "version": "2.0", "id": "rec", "name": "Rec",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "S" },
                    { "id": "e", "type": "end",   "name": "E", "prevNodes": ["s"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        ExecutionLog log = recorder.getExecutionLog(result.getExecutionId());
        assertEquals(ExecutionLog.Status.SUCCESS, log.getStatus());
        assertEquals(2, log.getTotalNodes());
    }

    @Test
    void recording_capturesNodeDetails() {
        String json = """
                { "version": "2.0", "id": "det", "name": "Det",
                  "nodes": [
                    { "id": "s", "type": "start", "name": "Start" },
                    { "id": "e", "type": "end",   "name": "End", "prevNodes": ["s"] }
                  ] }
                """;
        FlowResult result = engine.execute(engine.parse(json));
        List<NodeExecutionLog> logs = recorder.getExecutionLog(result.getExecutionId()).getNodeExecutionLogs();
        assertEquals("s", logs.get(0).getNodeId());
        assertEquals("start", logs.get(0).getNodeType());
    }
}
