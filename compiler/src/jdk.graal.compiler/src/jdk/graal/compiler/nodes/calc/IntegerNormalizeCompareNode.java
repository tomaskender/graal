/*
 * Copyright (c) 2009, 2023, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.nodes.calc;

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.options.OptionValues;

import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;

@NodeInfo
public final class IntegerNormalizeCompareNode extends AbstractNormalizeCompareNode {
    public static final NodeClass<IntegerNormalizeCompareNode> TYPE = NodeClass.create(IntegerNormalizeCompareNode.class);
    protected final boolean unsigned;

    public IntegerNormalizeCompareNode(ValueNode x, ValueNode y, JavaKind kind, boolean unsigned) {
        super(TYPE, kind, x, y);
        this.unsigned = unsigned;
    }

    public boolean isUnsigned() {
        return unsigned;
    }

    public static ValueNode create(ValueNode x, ValueNode y, boolean unsigned, JavaKind kind, ConstantReflectionProvider constantReflection) {
        ValueNode result = tryConstantFold(x, y, false, unsigned, kind, constantReflection);
        if (result != null) {
            return result;
        }

        return new IntegerNormalizeCompareNode(x, y, kind, unsigned);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {
        NodeView view = NodeView.from(tool);
        ValueNode result = tryConstantFold(x, y, false, unsigned, stamp(view).getStackKind(), tool.getConstantReflection());
        if (result != null) {
            return result;
        }
        return this;
    }

    @Override
    public LogicNode createLowerComparison(boolean swapInputs) {
        ValueNode a = swapInputs ? y : x;
        ValueNode b = swapInputs ? x : y;
        if (unsigned) {
            return IntegerBelowNode.create(a, b, NodeView.DEFAULT);
        } else {
            return IntegerLessThanNode.create(a, b, NodeView.DEFAULT);
        }
    }

    @Override
    public LogicNode createLowerComparison(boolean swapInputs, ConstantReflectionProvider constantReflection, MetaAccessProvider metaAccess, OptionValues options, Integer smallestCompareWidth,
                    NodeView view) {
        ValueNode a = swapInputs ? y : x;
        ValueNode b = swapInputs ? x : y;
        if (unsigned) {
            return IntegerBelowNode.create(constantReflection, metaAccess, options, smallestCompareWidth, a, b, NodeView.DEFAULT);
        } else {
            return IntegerLessThanNode.create(constantReflection, metaAccess, options, smallestCompareWidth, a, b, NodeView.DEFAULT);
        }
    }

    @Override
    public LogicNode createEqualComparison() {
        return IntegerEqualsNode.create(x, y, NodeView.DEFAULT);
    }

    @Override
    public LogicNode createEqualComparison(ConstantReflectionProvider constantReflection, MetaAccessProvider metaAccess, OptionValues options, Integer smallestCompareWidth, NodeView view) {
        return IntegerEqualsNode.create(constantReflection, metaAccess, options, smallestCompareWidth, x, y, view);
    }
}
