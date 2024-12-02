/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.pointsto.phases;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.flow.AnalysisParsedGraph;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.svm.util.ClassUtil;

import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.graphbuilderconf.InlineInvokePlugin;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.phases.common.inlining.InliningUtil;
import jdk.graal.compiler.printer.GraalDebugHandlersFactory;
import jdk.graal.compiler.util.json.JsonParser;
import org.graalvm.collections.EconomicMap;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

/**
 * Inlining before the static analysis improves the precision of the analysis especially when
 * constants are propagated. So the goal is to inline callees that are folded to constants.
 *
 * Sometimes, constant folding of callees requires quite deep inlining, when constants are
 * propagated through chains of wrapper methods. So we want to be able to look deep for potential
 * inlining. On the other hand, only very small methods can be inlined before analysis in order to
 * not bloat the type flow graph and later on machine code for infrequently executed methods. So we
 * do not want to use the regular method inlining mechanism that propagates argument constants into
 * a possibly large graph and then runs the CanonicalizerPhase. Instead, we leverage the graph
 * decoding also used by the Truffle partial evaluator. When a direct method invoke is seen during
 * decoding, we by default go into the callee. When the callee produces too many nodes (configurable
 * via the {@link InlineBeforeAnalysisPolicy}) inlining is aborted, i.e., already created nodes from
 * the callee are deleted and a non-inlined invoke is created instead.
 */
public class InlineBeforeAnalysis {

    public static class Options {
        @Option(help = "Deprecated, option no longer has any effect", deprecated = true, deprecationMessage = "It no longer has any effect, and no replacement is available")//
        public static final OptionKey<Boolean> InlineBeforeAnalysis = new OptionKey<>(true);
    }

    static HttpClient client = HttpClient.newHttpClient();

    record GraphNode(int nodeId, int nodeType, int size, int cycles) {
        public String toJson() {
            return "{" +
                    "\"nodeType\": " + nodeType + "," +
                    "\"size\": " + size + "," +
                    "\"cycles\": " + cycles +
                    "}";
        }
    }

    record GraphEdge(GraphNode node1, GraphNode node2) {
        public String toJson() {
            return "[%d, %d]".formatted(node1.nodeId(), node2.nodeId());
        }
    }

    static GraphNode collectNodeMetrics(FixedNode node, AtomicInteger nextNodeId) {
        return new GraphNode(nextNodeId.getAndIncrement(), node.getNodeClass().iterableId(), node.estimatedNodeSize().value, node.estimatedNodeCycles().value);
    }

    static Graph getValuePredecessorsGraph(FixedNode rootNode, GraphNode rootGraphNode, AtomicInteger nextNodeId) {
        List<GraphNode> valuePredecessors = rootNode.inputs().stream()
                .map(input -> new GraphNode(nextNodeId.getAndIncrement(), input.getNodeClass().iterableId(), input.estimatedNodeSize().value, input.estimatedNodeCycles().value))
                .toList();
        return new Graph(valuePredecessors, valuePredecessors.stream().map(v -> new GraphEdge(v, rootGraphNode)).toList());
    }

    record Graph(List<GraphNode> nodes, List<GraphEdge> edges) {
        public String toJson() {
            return "{\n" +
                "\"nodes\": [" + String.join(", ", nodes().stream().map(GraphNode::toJson).toList()) + "],\n" +
                "\"edges\": [" + String.join(", ", edges().stream().map(GraphEdge::toJson).toList()) + "]\n" +
                "}";
        }
    }

    static Graph buildGraph(FixedNode node, AtomicInteger nextNodeId) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();

        GraphNode graphNode = collectNodeMetrics(node, nextNodeId);
        nodes.add(graphNode);

        {
            Graph predecessorsGraph = getValuePredecessorsGraph(node, graphNode, nextNodeId);
            nodes.addAll(predecessorsGraph.nodes());
            edges.addAll(predecessorsGraph.edges());
        }

        List<AbstractMap.SimpleEntry<? extends Node, GraphNode>> predecessors = new ArrayList<>(StreamSupport.stream(node.cfgPredecessors().spliterator(), false)
                .map(predecessor -> new AbstractMap.SimpleEntry<>(predecessor, graphNode)).toList());
        while (!predecessors.isEmpty()) {
            // sanity check
            if (nodes.size() >= 50)
                break;
            Map.Entry<? extends Node, GraphNode> predecessor = predecessors.removeFirst();
            GraphNode currentGraphNode = collectNodeMetrics((FixedNode) predecessor.getKey(), nextNodeId);
            nodes.add(currentGraphNode);
            edges.add(new GraphEdge(currentGraphNode, predecessor.getValue()));

            Graph predecessorsGraph = getValuePredecessorsGraph((FixedNode) predecessor.getKey(), currentGraphNode, nextNodeId);
            nodes.addAll(predecessorsGraph.nodes());
            edges.addAll(predecessorsGraph.edges());

            predecessors.addAll(StreamSupport.stream(predecessor.getKey().cfgPredecessors().spliterator(), false)
                    .map(predecessorNode -> new AbstractMap.SimpleEntry<>(predecessorNode, currentGraphNode)).toList());
        };

        List<AbstractMap.SimpleEntry<? extends Node, GraphNode>> successors = new ArrayList<>(StreamSupport.stream(node.cfgSuccessors().spliterator(), false)
                .map(successor -> new AbstractMap.SimpleEntry<>(successor, graphNode)).toList());
        while (!successors.isEmpty()) {
            // sanity check
            if (nodes.size() >= 100)
                break;
            Map.Entry<? extends Node, GraphNode> successor = successors.removeFirst();
            GraphNode currentGraphNode = collectNodeMetrics((FixedNode) successor.getKey(), nextNodeId);
            nodes.add(currentGraphNode);
            edges.add(new GraphEdge(successor.getValue(), currentGraphNode));

            Graph predecessorsGraph = getValuePredecessorsGraph((FixedNode) successor.getKey(), currentGraphNode, nextNodeId);
            nodes.addAll(predecessorsGraph.nodes());
            edges.addAll(predecessorsGraph.edges());

            successors.addAll(successor.getKey().successors().stream()
                    .map(successorNode -> new AbstractMap.SimpleEntry<>(successorNode, currentGraphNode)).toList());
        };

        return new Graph(nodes, edges);
    }

    protected static boolean shouldInline(Invoke invoke) {
        AtomicInteger nextNodeId = new AtomicInteger(0);
        Graph graph = buildGraph(invoke.asFixedNode(), nextNodeId);
        String postData = graph.toJson();

//        String postData = "{\n" +
//                "\"estNodeSize\": " + invoke.asNode().estimatedNodeSize().value + ",\n" +
//                "\"codeSize\": "+invoke.getTargetMethod().getCodeSize() + ",\n" +
//                "\"maxStackSize\": "+invoke.getTargetMethod().getMaxStackSize() + ",\n" +
//                "\"estNodeCycles\": "+invoke.asNode().estimatedNodeCycles().value + "\n" +
//                "}";

        int retries = 3;
        Exception exception = null;
        Boolean inline = null;
        do {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:8001/predict"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(postData))
                        .build();
                // long start = System.currentTimeMillis(); 
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                // System.err.println(String.format("response delay: %d", System.currentTimeMillis()-start));

                if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                    Object result = new JsonParser(response.body()).parse();
                    EconomicMap<String, Object> map = (EconomicMap<String, Object>) result;
                    inline = (boolean) map.get("result");
                } else {
                    throw new Exception("Invalid HTTP status code returned from API. Response: " + response + ", args used: " + postData);
                }
            } catch (Exception e) {
                exception = e;
            }
        } while (inline == null && retries-- > 0);

        if (inline == null) {
            inline = false;
            System.out.println("API call for inlining failed, defaulting to inline='false'. Error: " + exception);
        }

        return inline;
    }

    @SuppressWarnings("try")
    public static StructuredGraph decodeGraph(BigBang bb, AnalysisMethod method, AnalysisParsedGraph analysisParsedGraph) {
        return decodeGraph(bb, method, analysisParsedGraph, 0);
    }

        @SuppressWarnings("try")
    public static StructuredGraph decodeGraph(BigBang bb, AnalysisMethod method, AnalysisParsedGraph analysisParsedGraph, int depth) {
        DebugContext.Description description = new DebugContext.Description(method, ClassUtil.getUnqualifiedName(method.getClass()) + ":" + method.getId());
        DebugContext debug = new DebugContext.Builder(bb.getOptions(), new GraalDebugHandlersFactory(bb.getSnippetReflectionProvider())).description(description).build();

        StructuredGraph result = new StructuredGraph.Builder(bb.getOptions(), debug, bb.getHostVM().allowAssumptions(method))
                        .method(method)
                        .trackNodeSourcePosition(analysisParsedGraph.getEncodedGraph().trackNodeSourcePosition())
                        .recordInlinedMethods(analysisParsedGraph.getEncodedGraph().isRecordingInlinedMethods())
                        .build();

        try (DebugContext.Scope s = debug.scope("InlineBeforeAnalysis", result)) {
            InlineBeforeAnalysisGraphDecoder decoder = bb.getHostVM().createInlineBeforeAnalysisGraphDecoder(bb, method, result);
            decoder.decode(method);

            // 3rd pass
            if (depth < 3) {
                for (Invoke invoke : result.getInvokes()) {
                    AnalysisMethod targetMethod = (AnalysisMethod) invoke.getTargetMethod();

                    if (invoke.asNode().graph().getNodeCount() > 4500)
                        break;

                    if (invoke.getInvokeKind().isDirect() &&
                            !targetMethod.hasNeverInlineDirective() &&
                            !targetMethod.isIntrinsicMethod() &&
                            !targetMethod.isNative() &&
                            invoke.useForInlining() &&
                            bb.getHostVM().inliningAllowed(method, targetMethod) &&
                            invoke.getTargetMethod().canBeInlined() &&
                            InlineBeforeAnalysis.shouldInline(invoke)) {
                        StructuredGraph targetGraph = decodeGraph(bb, (AnalysisMethod) invoke.getTargetMethod(), targetMethod.ensureGraphParsed(bb), depth + 1);

//                        if (invoke.asNode().graph().getNodeCount() + targetGraph.getNodeCount() > 4500)
//                            break;

                        InliningUtil.inline(
                                invoke,
                                targetGraph,
                                false,
                                targetMethod);
                    }
                }
            }
            debug.dump(DebugContext.BASIC_LEVEL, result, "InlineBeforeAnalysis after decode");
            return result;
        } catch (Throwable ex) {
            throw debug.handle(ex);
        }
    }
}
