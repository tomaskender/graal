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
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.BreakpointNode;
import jdk.graal.compiler.nodes.ComputeObjectAddressNode;
import jdk.graal.compiler.nodes.ConditionAnchorNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.DeadEndNode;
import jdk.graal.compiler.nodes.DeoptimizeNode;
import jdk.graal.compiler.nodes.DirectCallTargetNode;
import jdk.graal.compiler.nodes.DynamicDeoptimizeNode;
import jdk.graal.compiler.nodes.DynamicPiNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.EntryMarkerNode;
import jdk.graal.compiler.nodes.EntryProxyNode;
import jdk.graal.compiler.nodes.FixedGuardNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.FullInfopointNode;
import jdk.graal.compiler.nodes.GetObjectAddressNode;
import jdk.graal.compiler.nodes.GuardNode;
import jdk.graal.compiler.nodes.GuardPhiNode;
import jdk.graal.compiler.nodes.GuardProxyNode;
import jdk.graal.compiler.nodes.GuardedValueNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.IndirectCallTargetNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.InvokeWithExceptionNode;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNegationNode;
import jdk.graal.compiler.nodes.LoopBeginNode;
import jdk.graal.compiler.nodes.LoopEndNode;
import jdk.graal.compiler.nodes.LoopExitNode;
import jdk.graal.compiler.nodes.MemoryProxyNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.OptimizationLogImpl;
import jdk.graal.compiler.nodes.ParameterNode;
import jdk.graal.compiler.nodes.PauseNode;
import jdk.graal.compiler.nodes.PiArrayNode;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.PluginReplacementNode;
import jdk.graal.compiler.nodes.PluginReplacementWithExceptionNode;
import jdk.graal.compiler.nodes.PrefetchAllocateNode;
import jdk.graal.compiler.nodes.ReturnNode;
import jdk.graal.compiler.nodes.SafepointNode;
import jdk.graal.compiler.nodes.ShortCircuitOrNode;
import jdk.graal.compiler.nodes.SimplifyingGraphDecoder;
import jdk.graal.compiler.nodes.SnippetAnchorNode;
import jdk.graal.compiler.nodes.SpinWaitNode;
import jdk.graal.compiler.nodes.StartNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.UnreachableBeginNode;
import jdk.graal.compiler.nodes.UnreachableControlSinkNode;
import jdk.graal.compiler.nodes.UnreachableNode;
import jdk.graal.compiler.nodes.UnwindNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.ValueProxyNode;
import jdk.graal.compiler.nodes.calc.AbsNode;
import jdk.graal.compiler.nodes.calc.AddNode;
import jdk.graal.compiler.nodes.calc.AndNode;
import jdk.graal.compiler.nodes.calc.CompressBitsNode;
import jdk.graal.compiler.nodes.calc.ConditionalNode;
import jdk.graal.compiler.nodes.calc.CopySignNode;
import jdk.graal.compiler.nodes.calc.ExpandBitsNode;
import jdk.graal.compiler.nodes.calc.FloatConvertNode;
import jdk.graal.compiler.nodes.calc.FloatDivNode;
import jdk.graal.compiler.nodes.calc.FloatEqualsNode;
import jdk.graal.compiler.nodes.calc.FloatLessThanNode;
import jdk.graal.compiler.nodes.calc.FloatNormalizeCompareNode;
import jdk.graal.compiler.nodes.calc.FloatTypeTestNode;
import jdk.graal.compiler.nodes.calc.IntegerBelowNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.calc.IntegerLessThanNode;
import jdk.graal.compiler.nodes.calc.IntegerMulHighNode;
import jdk.graal.compiler.nodes.calc.IntegerNormalizeCompareNode;
import jdk.graal.compiler.nodes.calc.IntegerTestNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.calc.LeftShiftNode;
import jdk.graal.compiler.nodes.calc.MaxNode;
import jdk.graal.compiler.nodes.calc.MinNode;
import jdk.graal.compiler.nodes.calc.MulNode;
import jdk.graal.compiler.nodes.calc.NarrowNode;
import jdk.graal.compiler.nodes.calc.NegateNode;
import jdk.graal.compiler.nodes.calc.NotNode;
import jdk.graal.compiler.nodes.calc.ObjectEqualsNode;
import jdk.graal.compiler.nodes.calc.OpMaskOrTestNode;
import jdk.graal.compiler.nodes.calc.OpMaskTestNode;
import jdk.graal.compiler.nodes.calc.OrNode;
import jdk.graal.compiler.nodes.calc.PointerEqualsNode;
import jdk.graal.compiler.nodes.calc.ReinterpretNode;
import jdk.graal.compiler.nodes.calc.RemNode;
import jdk.graal.compiler.nodes.calc.RightShiftNode;
import jdk.graal.compiler.nodes.calc.RoundFloatToIntegerNode;
import jdk.graal.compiler.nodes.calc.RoundNode;
import jdk.graal.compiler.nodes.calc.SignExtendNode;
import jdk.graal.compiler.nodes.calc.SignedDivNode;
import jdk.graal.compiler.nodes.calc.SignedFloatingIntegerDivNode;
import jdk.graal.compiler.nodes.calc.SignedFloatingIntegerRemNode;
import jdk.graal.compiler.nodes.calc.SignedRemNode;
import jdk.graal.compiler.nodes.calc.SignumNode;
import jdk.graal.compiler.nodes.calc.SqrtNode;
import jdk.graal.compiler.nodes.calc.SubNode;
import jdk.graal.compiler.nodes.calc.UnpackEndianHalfNode;
import jdk.graal.compiler.nodes.calc.UnsignedDivNode;
import jdk.graal.compiler.nodes.calc.UnsignedMaxNode;
import jdk.graal.compiler.nodes.calc.UnsignedMinNode;
import jdk.graal.compiler.nodes.calc.UnsignedRemNode;
import jdk.graal.compiler.nodes.calc.UnsignedRightShiftNode;
import jdk.graal.compiler.nodes.calc.XorNode;
import jdk.graal.compiler.nodes.calc.ZeroExtendNode;
import jdk.graal.compiler.nodes.debug.BindToRegisterNode;
import jdk.graal.compiler.nodes.debug.BlackholeNode;
import jdk.graal.compiler.nodes.debug.ControlFlowAnchorNode;
import jdk.graal.compiler.nodes.debug.DynamicCounterNode;
import jdk.graal.compiler.nodes.debug.NeverStripMineNode;
import jdk.graal.compiler.nodes.debug.NeverWriteSinkNode;
import jdk.graal.compiler.nodes.debug.SideEffectNode;
import jdk.graal.compiler.nodes.debug.SpillRegistersNode;
import jdk.graal.compiler.nodes.debug.StringToBytesNode;
import jdk.graal.compiler.nodes.debug.VerifyHeapNode;
import jdk.graal.compiler.nodes.extended.BoxNode;
import jdk.graal.compiler.nodes.extended.BranchProbabilityNode;
import jdk.graal.compiler.nodes.extended.BytecodeExceptionNode;
import jdk.graal.compiler.nodes.extended.CacheWritebackNode;
import jdk.graal.compiler.nodes.extended.CacheWritebackSyncNode;
import jdk.graal.compiler.nodes.extended.CaptureStateBeginNode;
import jdk.graal.compiler.nodes.extended.ClassIsArrayNode;
import jdk.graal.compiler.nodes.extended.FixedValueAnchorNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.extended.ForeignCallWithExceptionNode;
import jdk.graal.compiler.nodes.extended.GetClassNode;
import jdk.graal.compiler.nodes.extended.GuardedUnsafeLoadNode;
import jdk.graal.compiler.nodes.extended.IntegerSwitchNode;
import jdk.graal.compiler.nodes.extended.JavaReadNode;
import jdk.graal.compiler.nodes.extended.JavaWriteNode;
import jdk.graal.compiler.nodes.extended.LoadArrayComponentHubNode;
import jdk.graal.compiler.nodes.extended.LoadHubNode;
import jdk.graal.compiler.nodes.extended.LoadHubOrNullNode;
import jdk.graal.compiler.nodes.extended.LoadMethodNode;
import jdk.graal.compiler.nodes.extended.MembarNode;
import jdk.graal.compiler.nodes.extended.MultiGuardNode;
import jdk.graal.compiler.nodes.extended.NullCheckNode;
import jdk.graal.compiler.nodes.extended.OSRLocalNode;
import jdk.graal.compiler.nodes.extended.OSRLockNode;
import jdk.graal.compiler.nodes.extended.OSRMonitorEnterNode;
import jdk.graal.compiler.nodes.extended.OSRStartNode;
import jdk.graal.compiler.nodes.extended.ObjectIsArrayNode;
import jdk.graal.compiler.nodes.extended.OpaqueGuardNode;
import jdk.graal.compiler.nodes.extended.OpaqueLogicNode;
import jdk.graal.compiler.nodes.extended.OpaqueValueNode;
import jdk.graal.compiler.nodes.extended.RawLoadNode;
import jdk.graal.compiler.nodes.extended.RawStoreNode;
import jdk.graal.compiler.nodes.extended.SpeculationFenceNode;
import jdk.graal.compiler.nodes.extended.StateSplitProxyNode;
import jdk.graal.compiler.nodes.extended.StoreHubNode;
import jdk.graal.compiler.nodes.extended.SwitchCaseProbabilityNode;
import jdk.graal.compiler.nodes.extended.UnboxNode;
import jdk.graal.compiler.nodes.extended.UnsafeCopyNode;
import jdk.graal.compiler.nodes.extended.UnsafeMemoryLoadNode;
import jdk.graal.compiler.nodes.extended.UnsafeMemoryStoreNode;
import jdk.graal.compiler.nodes.extended.ValueAnchorNode;
import jdk.graal.compiler.nodes.gc.G1ArrayRangePostWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.G1ArrayRangePreWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.G1PostWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.G1PreWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.G1ReferentFieldReadBarrierNode;
import jdk.graal.compiler.nodes.gc.SerialArrayRangeWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.SerialWriteBarrierNode;
import jdk.graal.compiler.nodes.graphbuilderconf.InlineInvokePlugin;
import jdk.graal.compiler.nodes.java.ArrayLengthNode;
import jdk.graal.compiler.nodes.java.AtomicReadAndAddNode;
import jdk.graal.compiler.nodes.java.AtomicReadAndWriteNode;
import jdk.graal.compiler.nodes.java.ClassIsAssignableFromNode;
import jdk.graal.compiler.nodes.java.DynamicNewArrayNode;
import jdk.graal.compiler.nodes.java.DynamicNewArrayWithExceptionNode;
import jdk.graal.compiler.nodes.java.DynamicNewInstanceNode;
import jdk.graal.compiler.nodes.java.DynamicNewInstanceWithExceptionNode;
import jdk.graal.compiler.nodes.java.ExceptionObjectNode;
import jdk.graal.compiler.nodes.java.FinalFieldBarrierNode;
import jdk.graal.compiler.nodes.java.InstanceOfDynamicNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.java.LoadExceptionObjectNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.LoadIndexedNode;
import jdk.graal.compiler.nodes.java.LogicCompareAndSwapNode;
import jdk.graal.compiler.nodes.java.LoweredAtomicReadAndAddNode;
import jdk.graal.compiler.nodes.java.LoweredAtomicReadAndWriteNode;
import jdk.graal.compiler.nodes.java.LoweredRegisterFinalizerNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.nodes.java.MonitorEnterNode;
import jdk.graal.compiler.nodes.java.MonitorExitNode;
import jdk.graal.compiler.nodes.java.MonitorIdNode;
import jdk.graal.compiler.nodes.java.NewArrayNode;
import jdk.graal.compiler.nodes.java.NewArrayWithExceptionNode;
import jdk.graal.compiler.nodes.java.NewInstanceNode;
import jdk.graal.compiler.nodes.java.NewInstanceWithExceptionNode;
import jdk.graal.compiler.nodes.java.NewMultiArrayNode;
import jdk.graal.compiler.nodes.java.NewMultiArrayWithExceptionNode;
import jdk.graal.compiler.nodes.java.ReachabilityFenceNode;
import jdk.graal.compiler.nodes.java.RegisterFinalizerNode;
import jdk.graal.compiler.nodes.java.StoreFieldNode;
import jdk.graal.compiler.nodes.java.StoreIndexedNode;
import jdk.graal.compiler.nodes.java.TypeSwitchNode;
import jdk.graal.compiler.nodes.java.UnsafeCompareAndExchangeNode;
import jdk.graal.compiler.nodes.java.UnsafeCompareAndSwapNode;
import jdk.graal.compiler.nodes.java.ValidateNewInstanceClassNode;
import jdk.graal.compiler.nodes.java.ValueCompareAndSwapNode;
import jdk.graal.compiler.nodes.memory.FloatingReadNode;
import jdk.graal.compiler.nodes.memory.MemoryAnchorNode;
import jdk.graal.compiler.nodes.memory.MemoryMapNode;
import jdk.graal.compiler.nodes.memory.MemoryPhiNode;
import jdk.graal.compiler.nodes.memory.ReadNode;
import jdk.graal.compiler.nodes.memory.SideEffectFreeWriteNode;
import jdk.graal.compiler.nodes.memory.WriteNode;
import jdk.graal.compiler.nodes.memory.address.IndexAddressNode;
import jdk.graal.compiler.nodes.memory.address.OffsetAddressNode;
import jdk.graal.compiler.nodes.virtual.AllocatedObjectNode;
import jdk.graal.compiler.nodes.virtual.CommitAllocationNode;
import jdk.graal.compiler.nodes.virtual.EnsureVirtualizedNode;
import jdk.graal.compiler.nodes.virtual.VirtualArrayNode;
import jdk.graal.compiler.nodes.virtual.VirtualBoxingNode;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
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
    static List<Class> classes = List.of(
            InvokeNode.class,
            LogicNegationNode.class,
            ConstantNode.class,
            ValuePhiNode.class,
            NewMultiArrayNode.class,
            LoadFieldNode.class,
            LoweredAtomicReadAndWriteNode.class,
            UnsafeCompareAndSwapNode.class,
            DynamicNewInstanceWithExceptionNode.class,
            TypeSwitchNode.class,
            ArrayLengthNode.class,
            UnsafeCompareAndExchangeNode.class,
            AtomicReadAndAddNode.class,
            LoadIndexedNode.class,
            NewInstanceWithExceptionNode.class,
            NewArrayNode.class,
            InstanceOfNode.class,
            StoreFieldNode.class,
            LoadExceptionObjectNode.class,
            StoreIndexedNode.class,
            NewArrayWithExceptionNode.class,
            LogicCompareAndSwapNode.class,
            InstanceOfDynamicNode.class,
            DynamicNewArrayNode.class,
            AtomicReadAndWriteNode.class,
            ValueCompareAndSwapNode.class,
            MethodCallTargetNode.class,
            NewMultiArrayWithExceptionNode.class,
            ReachabilityFenceNode.class,
            DynamicNewArrayWithExceptionNode.class,
            DynamicNewInstanceNode.class,
            NewInstanceNode.class,
            LoweredAtomicReadAndAddNode.class,
            ExceptionObjectNode.class,
            DynamicCounterNode.class,
            SideEffectNode.class,
            StringToBytesNode.class,
            UnreachableNode.class,
            DynamicPiNode.class,
            IfNode.class,
            LoopExitNode.class,
            PiArrayNode.class,
            IntegerTestNode.class,
            SqrtNode.class,
            FloatConvertNode.class,
            XorNode.class,
            NarrowNode.class,
            IntegerBelowNode.class,
            FloatDivNode.class,
            ConditionalNode.class,
            NotNode.class,
            IntegerLessThanNode.class,
            ZeroExtendNode.class,
            SignedRemNode.class,
            RightShiftNode.class,
            RoundFloatToIntegerNode.class,
            AbsNode.class,
            CopySignNode.class,
            SignumNode.class,
            SignedDivNode.class,
            UnsignedRightShiftNode.class,
            NegateNode.class,
            SubNode.class,
            SignExtendNode.class,
            ObjectEqualsNode.class,
            AndNode.class,
            FloatLessThanNode.class,
            FloatNormalizeCompareNode.class,
            ExpandBitsNode.class,
            SignedFloatingIntegerRemNode.class,
            UnsignedDivNode.class,
            UnsignedMaxNode.class,
            OrNode.class,
            FloatTypeTestNode.class,
            FloatEqualsNode.class,
            IntegerEqualsNode.class,
            IsNullNode.class,
            MinNode.class,
            MaxNode.class,
            OpMaskTestNode.class,
            AddNode.class,
            MulNode.class,
            IntegerNormalizeCompareNode.class,
            PointerEqualsNode.class,
            ReinterpretNode.class,
            LeftShiftNode.class,
            RoundNode.class,
            SignedFloatingIntegerDivNode.class,
            UnsignedMinNode.class,
            ShortCircuitOrNode.class,
            MergeNode.class,
            PauseNode.class,
            ConditionAnchorNode.class,
            SpinWaitNode.class,
            StartNode.class,
            BeginNode.class,
            DeadEndNode.class,
            ComputeObjectAddressNode.class,
            UnwindNode.class,
            AllocatedObjectNode.class,
            CommitAllocationNode.class,
            VirtualBoxingNode.class,
            VirtualInstanceNode.class,
            VirtualArrayNode.class,
            EnsureVirtualizedNode.class,
            ValueProxyNode.class,
            GetObjectAddressNode.class,
            SwitchCaseProbabilityNode.class,
            LoadArrayComponentHubNode.class,
            IntegerSwitchNode.class,
            ValueAnchorNode.class,
            UnsafeMemoryLoadNode.class,
            ClassIsArrayNode.class,
            ForeignCallNode.class,
            NullCheckNode.class,
            OpaqueLogicNode.class,
            LoadHubOrNullNode.class,
            UnsafeMemoryStoreNode.class,
            UnsafeCopyNode.class,
            ObjectIsArrayNode.class,
            StateSplitProxyNode.class,
            OpaqueValueNode.class,
            SpeculationFenceNode.class,
            GuardedUnsafeLoadNode.class,
            RawLoadNode.class,
            JavaReadNode.class,
            BranchProbabilityNode.class,
            GetClassNode.class,
            BytecodeExceptionNode.class,
            LoadMethodNode.class,
            JavaWriteNode.class,
            CaptureStateBeginNode.class,
            UnboxNode.class,
            ForeignCallWithExceptionNode.class,
            MemoryMapNode.class,
            WriteNode.class,
            MemoryPhiNode.class,
            ReadNode.class,
            OffsetAddressNode.class,
            IndexAddressNode.class,
            FloatingReadNode.class,
            SideEffectFreeWriteNode.class,
            MemoryAnchorNode.class,
            UnreachableControlSinkNode.class,
            IndirectCallTargetNode.class,
            DirectCallTargetNode.class,
            LoopBeginNode.class,
            ReturnNode.class,
            InvokeWithExceptionNode.class,
            GuardedValueNode.class,
            ParameterNode.class,
            FixedGuardNode.class,
            PiNode.class,
            LogicConstantNode.class,
            EndNode.class,
            LoopEndNode.class,
            FrameState.class
    );

    static int getNodeClassId(Class c) {
        return classes.indexOf(c)+1;
    }

    record GraphNode(int nodeId, Node node) {
        public String toJson() {
            return "{" +
                    "\"nodeType\": " + getNodeClassId(node.getClass()) +
                    "}";
        }
    }

    record GraphEdge(GraphNode node1, GraphNode node2) {
        public String toJson() {
            return "[%d, %d]".formatted(node1.nodeId(), node2.nodeId());
        }
    }

    static GraphNode collectNodeMetrics(FixedNode node, AtomicInteger nextNodeId) {
        return new GraphNode(nextNodeId.getAndIncrement(), node);
    }

    static Graph getValuePredecessorsGraph(FixedNode rootNode, GraphNode rootGraphNode, AtomicInteger nextNodeId) {
        List<GraphNode> valuePredecessors = rootNode.inputs().stream()
                .map(input -> new GraphNode(nextNodeId.getAndIncrement(), input))
                .toList();https://www.linkedin.com/in/karel-bene%C5%A1-967554176/
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

    static void replaceArgumentNodesInCalleeGraph(Graph callerGraph, List<? extends Node> callerArguments, Graph calleeGraph, List<? extends Node> calleeArguments) {
        IntStream.range(0, Math.min(callerArguments.size(), calleeArguments.size())).forEach(i -> {
            // replace method parameter
            Optional<GraphNode> originalGraphNode = calleeGraph.nodes().stream().filter(n -> n.node == calleeArguments.get(i)).findAny();
            if (originalGraphNode.isEmpty())
                return;

            // with invoke argument
            Optional<GraphNode> replacementGraphNode = callerGraph.nodes().stream().filter(n -> n.node == callerArguments.get(i)).findAny();
            if (replacementGraphNode.isEmpty())
                return;

            // perform replacement
            calleeGraph.nodes().remove(calleeGraph.nodes.indexOf(originalGraphNode.get())); // callerGraph already contains this node, no need to duplicate it in the unified list
            calleeGraph.edges().replaceAll(edge -> {
                if (edge.node1 == originalGraphNode.get())
                    return new GraphEdge(replacementGraphNode.get(), edge.node2);
                else if (edge.node2 == originalGraphNode.get())
                    return new GraphEdge(edge.node1, replacementGraphNode.get());
                return edge;
            });
        });
    }

    protected static boolean shouldInline(Invoke invoke, StructuredGraph targetGraph) {
        AtomicInteger nextNodeId = new AtomicInteger(0);
        Graph callerGraph = buildGraph(invoke.asFixedNode(), nextNodeId);
        Graph calleeGraph = buildGraph(targetGraph.start(), nextNodeId);

        replaceArgumentNodesInCalleeGraph(
                callerGraph,
                invoke.callTarget().arguments().stream().toList(),
                calleeGraph,
                targetGraph.getNodes(ParameterNode.TYPE).stream().toList()
        );

        List<GraphNode> joinedNodes = new ArrayList<>();
        joinedNodes.addAll(callerGraph.nodes());
        joinedNodes.addAll(calleeGraph.nodes());

        List<GraphEdge> joinedEdges = new ArrayList<>();
        joinedEdges.addAll(callerGraph.edges());
        joinedEdges.addAll(calleeGraph.edges());

        String postData = new Graph(joinedNodes, joinedEdges).toJson();

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
                            invoke.getTargetMethod().canBeInlined()) {
                        StructuredGraph targetGraph = decodeGraph(bb, (AnalysisMethod) invoke.getTargetMethod(), targetMethod.ensureGraphParsed(bb), depth + 1);

                        if (InlineBeforeAnalysis.shouldInline(invoke, targetGraph)) {
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
            }
            debug.dump(DebugContext.BASIC_LEVEL, result, "InlineBeforeAnalysis after decode");
            return result;
        } catch (Throwable ex) {
            throw debug.handle(ex);
        }
    }
}
