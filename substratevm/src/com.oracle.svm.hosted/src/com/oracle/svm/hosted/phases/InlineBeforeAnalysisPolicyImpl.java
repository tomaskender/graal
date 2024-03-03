/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.phases;

import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.phases.InlineBeforeAnalysisPolicy;
import com.oracle.svm.core.ParsingReason;
import com.oracle.svm.hosted.SVMHost;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.InlineInvokePlugin.InlineInfo;
import jdk.graal.compiler.nodes.graphbuilderconf.NodePlugin;
import jdk.graal.compiler.util.json.JSONParser;
import org.graalvm.collections.EconomicMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The defaults for node limits are very conservative. Only small methods should be inlined. The
 * only exception are constants - an arbitrary number of constants is always allowed. Limiting to 1
 * node (which can be also 1 invoke) means that field accessors can be inlined and forwarding
 * methods can be inlined. But null checks and class initialization checks are already putting a
 * method above the limit. On the other hand, the inlining depth is generous because we do do not
 * need to limit it. Note that more experimentation is necessary to come up with the optimal
 * configuration.
 *
 * Important: the implementation details of this class are publicly observable API. Since
 * {@link java.lang.reflect.Method} constants can be produced by inlining lookup methods with
 * constant arguments, reducing inlining can break customer code. This means we can never reduce the
 * amount of inlining in a future version without breaking compatibility. This also means that we
 * must be conservative and only inline what is necessary for known use cases.
 */
public class InlineBeforeAnalysisPolicyImpl extends InlineBeforeAnalysisPolicy {
    private final int maxInliningDepth = InlineBeforeAnalysisPolicyUtils.Options.InlineBeforeAnalysisAllowedDepth.getValue();

    private final SVMHost hostVM;
    private final InlineBeforeAnalysisPolicyUtils inliningUtils;

    public InlineBeforeAnalysisPolicyImpl(SVMHost hostVM, InlineBeforeAnalysisPolicyUtils inliningUtils) {
        super(new NodePlugin[]{new ConstantFoldLoadFieldPlugin(ParsingReason.PointsToAnalysis)});
        this.hostVM = hostVM;
        this.inliningUtils = inliningUtils;
    }

    private boolean getPredictionFromEndpoint(GraphBuilderContext b, AnalysisMethod method, ValueNode[] args) {
        boolean decision = false;
        try {
            URL url = new URL("http://localhost:8001/predict");

            // Open a connection
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            boolean alwaysInlineInvoke = inliningUtils.alwaysInlineInvoke((AnalysisMetaAccess) b.getMetaAccess(), method);
            int depth = b.getDepth();
            int recursiveDepth = b.recursiveInliningDepth(method);
            boolean inliningAllowed = InlineBeforeAnalysisPolicyUtils.inliningAllowed(hostVM, b, method);

            String postData = "{" +
                    "'alwaysInlineInvoke': " + alwaysInlineInvoke + ", " +
                    "'depth': " + depth + ", " +
                    "'recursiveInliningDepth': " + recursiveDepth + ", "  +
                    "'inliningAllowed': " + inliningAllowed +
                    "}";
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = postData.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();

            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Object result = new JSONParser(String.valueOf(response)).parse();
                    EconomicMap<String, Object> map = (EconomicMap<String, Object>) result;
                    decision = Boolean.parseBoolean(map.get("result").toString());
                } else {
                    System.out.println("Error in API call: " + response + ", args used: " + postData);
                }
            }
            conn.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return decision;
    }

    @Override
    protected boolean shouldInlineInvoke(GraphBuilderContext b, AnalysisMethod method, ValueNode[] args) {
        return getPredictionFromEndpoint(b, method, args);
    }

    @Override
    protected InlineInfo createInvokeInfo(AnalysisMethod method) {
        return InlineInfo.createStandardInlineInfo(method);
    }

    @Override
    protected boolean needsExplicitExceptions() {
        return true;
    }

    @Override
    protected boolean tryInvocationPlugins() {
        /*
         * We conditionally allow the invocation plugin to be triggered during graph decoding to see
         * what happens.
         */
        return true;
    }

    @Override
    protected AbstractPolicyScope createRootScope() {
        /* We do not need a scope for the root method. */
        return null;
    }

    @Override
    protected AbstractPolicyScope openCalleeScope(AbstractPolicyScope outer, AnalysisMetaAccess metaAccess,
                    AnalysisMethod method, boolean[] constArgsWithReceiver, boolean intrinsifiedMethodHandle) {
        return inliningUtils.createAccumulativeInlineScope((InlineBeforeAnalysisPolicyUtils.AccumulativeInlineScope) outer, metaAccess, method, constArgsWithReceiver, intrinsifiedMethodHandle);
    }

    @Override
    protected boolean shouldOmitIntermediateMethodInState(AnalysisMethod method) {
        return inliningUtils.shouldOmitIntermediateMethodInState(method);
    }

    @Override
    protected FixedWithNextNode processInvokeArgs(AnalysisMethod targetMethod, FixedWithNextNode insertionPoint, ValueNode[] arguments, NodeSourcePosition sourcePosition) {
        // No action is needed
        return insertionPoint;
    }
}
