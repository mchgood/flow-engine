package io.github.mchgood.flow.compiler;

import io.github.mchgood.flow.internal.compiler.FlowCompiler;
import io.github.mchgood.flow.internal.graph.Definition;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.spi.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 注册期格式、拓扑、源码位置及限额测试；每个负向用例检查明确错误码。 */
class CompilerContractTest {
    private static final ConditionEvaluator CONDITIONS = new ConditionEvaluator() {
        public CompiledCondition parse(String text, SourceLocation loc) { return new CompiledCondition() {}; }
        public boolean evaluate(CompiledCondition condition, NodeContext context) { throw new AssertionError("Registration must not evaluate"); }
    };
    private final FlowCompiler compiler = new FlowCompiler(id -> context -> null, CONDITIONS);
    private static String md(String body) { return "```mermaid\nflowchart TD\n" + body + "\n```"; }
    private static final String SERIAL = "start([开始]) --> work --> finish([结束])";

    static Stream<Arguments> malformed() {
        return Stream.of(
            Arguments.of("missing block", "plain text", "MERMAID_BLOCK_COUNT"),
            Arguments.of("duplicate blocks", md(SERIAL)+"\n"+md(SERIAL), "MERMAID_BLOCK_COUNT"),
            Arguments.of("nested block", "> ```mermaid\n> flowchart TD\n> "+SERIAL+"\n> ```", "MERMAID_BLOCK_LOCATION"),
            Arguments.of("old header", md(SERIAL).replace("flowchart TD","graph TD"), "INVALID_HEADER"),
            Arguments.of("unsupported direction", md(SERIAL).replace("flowchart TD","flowchart BT"), "INVALID_HEADER"),
            Arguments.of("old mapping", md("%% @bean work=other\n"+SERIAL), "DEPRECATED_BINDING"),
            Arguments.of("directive", md("%%{init: {}}%%\n"+SERIAL), "UNSUPPORTED_SYNTAX"),
            Arguments.of("empty flow", md("start([开始]) --> finish([结束])"), "EMPTY_FLOW"),
            Arguments.of("wrong endpoint shape", md(SERIAL.replace("start([开始])","start[\"start\"]")), "NODE_SHAPE"),
            Arguments.of("unsupported event", md(SERIAL.replace("work","work([event])")), "NODE_SHAPE"),
            Arguments.of("conflicting label", md(SERIAL+"\nwork[\"one\"]\nwork[\"two\"]"), "CONFLICTING_NODE"),
            Arguments.of("self edge", md(SERIAL+"\nwork --> work"), "DUPLICATE_OR_SELF_EDGE"),
            Arguments.of("duplicate edge", md(SERIAL+"\nwork --> finish"), "DUPLICATE_OR_SELF_EDGE"),
            Arguments.of("cycle", md("start([s]) --> a --> b --> a\nb --> finish([f])"), "GRAPH_CYCLE"),
            Arguments.of("orphan", md(SERIAL+"\norphan"), "UNREACHABLE_NODE"),
            Arguments.of("dead end", md(SERIAL+"\nwork --> dead"), "UNREACHABLE_NODE"),
            Arguments.of("missing start", md("work --> finish([f])"), "INVALID_ENDPOINTS"),
            Arguments.of("outgoing finish", md(SERIAL+"\nfinish --> work"), "INVALID_ENDPOINTS"),
            Arguments.of("incoming start", md(SERIAL+"\nwork --> start"), "INVALID_ENDPOINTS"),
            Arguments.of("task condition", md(SERIAL.replace("work -->", "work -->|\"true\"|")), "CONDITION_NOT_ALLOWED"),
            Arguments.of("empty condition", md(SERIAL.replace("work -->", "work -->|\"\"|")), "EMPTY_CONDITION"),
            Arguments.of("dangling gateway", md("start([s]) --> gate{\"+\"} --> work --> finish([f])"), "GATEWAY_DEGREE"),
            Arguments.of("unclosed label", md("start([s]) --> work[\"oops"), "UNCLOSED_LABEL"),
            Arguments.of("unsupported arrow", md("start([s]) -.-> work --> finish([f])"), "UNSUPPORTED_SYNTAX"),
            Arguments.of("invalid node id", md(SERIAL.replace("work","1work")), "INVALID_NODE_ID"),
            Arguments.of("unpaired xor join", md("start([s]) --> a\nstart --> b\na --> join{\"X\"}\nb --> join\njoin --> finish([f])"), "GATEWAY_STRUCTURE_INVALID")
        );
    }
    @ParameterizedTest(name="{0}") @MethodSource("malformed")
    void rejectsMalformedDefinitions(String name, String source, String code) {
        assertEquals(code, assertThrows(FlowException.class, () -> compiler.compile("sample", source)).code());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"Upper","with_under","with-dash","中文","1first"})
    void rejectsInvalidFlowIds(String id) {
        assertEquals("INVALID_FLOW_ID", assertThrows(FlowException.class, () -> compiler.compile(id, md(SERIAL))).code());
    }
    @ParameterizedTest @ValueSource(strings={"work_", "work__x", "Work", "_work"})
    void rejectsInvalidTaskNames(String name) {
        assertEquals("INVALID_NODE_ID", assertThrows(FlowException.class, () -> compiler.compile("sample",md(SERIAL.replace("work",name)))).code());
    }
    @Test void normalizesBomCrLfAndAllowsLr() {
        var source="\uFEFF# title\r\n\r\n"+md(SERIAL).replace("TD","LR").replace("\n","\r\n");
        var graph=compiler.compile("sample",source);
        assertEquals(List.of("start","work","finish"),graph.ordered.stream().map(n->n.id).toList());
        assertEquals(5,graph.nodes.get("work").location.line());
    }
    @Test void sameBeanIsBoundForDistinctAliasesAndForwardDeclarations() {
        List<String> ids=new ArrayList<>();
        var c=new FlowCompiler(id->{ids.add(id);return ctx->null;},CONDITIONS);
        var g=c.compile("sample",md("start([s]) --> work_before --> work_after_check --> finish([f])\nwork_before[\"first\"]"));
        assertEquals(List.of("work","work"),ids);
        assertEquals(Set.of("start","work_before"),g.nodes.get("work_after_check").ancestors);
    }
    @Test void missingBeanHasSpecificCode() {
        var c=new FlowCompiler(id->null,CONDITIONS);
        assertEquals("BEAN_NOT_FOUND",assertThrows(FlowException.class,()->c.compile("sample",md(SERIAL))).code());
    }
    @Test void sourceLocationUsesMarkdownCoordinates() {
        var ex=assertThrows(FlowException.class,()->compiler.compile("sample","# title\n\n"+md("start([s]) --> work[\"oops")));
        assertEquals("UNCLOSED_LABEL",ex.code());assertTrue(ex.getMessage().startsWith("sample:5:"),ex.getMessage());
    }
    @Test void sizeLimitsCountUtf8BytesAndRejectNull() {
        assertEquals("DEFINITION_LIMIT",assertThrows(FlowException.class,()->compiler.compile("sample",null)).code());
        assertEquals("DEFINITION_LIMIT",assertThrows(FlowException.class,()->compiler.compile("sample","中".repeat(350_000))).code());
    }
    @Test void nodeLimitBoundary() {
        StringBuilder body=new StringBuilder("start([s])");
        for(int i=0;i<510;i++)body.append(" --> work_").append(i);
        body.append(" --> finish([f])");
        assertEquals(512,compiler.compile("sample",md(body.toString())).nodes.size());
        assertEquals("DEFINITION_LIMIT",assertThrows(FlowException.class,()->compiler.compile("sample",md(body+"\nextra"))).code());
    }
    @Test void edgeLimitIsCheckedBeforeTopologyCompilation() {
        String body="start([s]) --> work\n".repeat(4097);
        assertEquals("DEFINITION_LIMIT",assertThrows(FlowException.class,()->compiler.compile("sample",md(body))).code());
    }
    @Test void definitionHashIsStableAndSensitiveToSourceChanges() {
        var first=compiler.compile("sample",md(SERIAL));
        assertEquals(first.hash,compiler.compile("sample",md(SERIAL)).hash);
        assertNotEquals(first.hash,compiler.compile("sample",md(SERIAL)+"\n").hash);
        assertEquals(64,first.hash.length());
    }
}
