/*
 * Copyright (c) 2011, 2020, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.nodes;

import java.util.Iterator;
import java.util.NoSuchElementException;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.IterableNodeType;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.Position;
import jdk.graal.compiler.graph.iterators.NodeIterable;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.extended.AnchoringNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;

@NodeInfo(allowedUsageTypes = {InputType.Guard, InputType.Anchor})
public abstract class AbstractBeginNode extends FixedWithNextNode implements LIRLowerable, GuardingNode, AnchoringNode, IterableNodeType {

    public static final NodeClass<AbstractBeginNode> TYPE = NodeClass.create(AbstractBeginNode.class);

    private boolean hasSpeculationFence;

    private int bytecodeHandlerIndex;

    protected AbstractBeginNode(NodeClass<? extends AbstractBeginNode> c) {
        this(c, StampFactory.forVoid());
    }

    protected AbstractBeginNode(NodeClass<? extends AbstractBeginNode> c, Stamp stamp) {
        super(c, stamp);
        bytecodeHandlerIndex = -1;
    }

    public int getBytecodeHandlerIndex() {
        return bytecodeHandlerIndex;
    }

    public void markAsBytecodeHandler(int handlerIndex) {
        assert handlerIndex >= 0;
        bytecodeHandlerIndex = handlerIndex;
    }

    public static AbstractBeginNode prevBegin(FixedNode from) {
        Node next = from;
        while (next != null) {
            if (next instanceof AbstractBeginNode) {
                return (AbstractBeginNode) next;
            }
            next = next.predecessor();
        }
        return null;
    }

    private void evacuateAnchored(FixedNode evacuateFrom) {
        if (!hasNoUsages()) {
            AbstractBeginNode prevBegin = prevBegin(evacuateFrom);
            assert prevBegin != null;
            replaceAtUsages(prevBegin, InputType.Anchor);
            replaceAtUsages(prevBegin, InputType.Guard);
            assert anchored().isEmpty() : anchored().snapshot();
        }
    }

    public void prepareDelete() {
        prepareDelete((FixedNode) predecessor());
    }

    public void prepareDelete(FixedNode evacuateFrom) {
        evacuateAnchored(evacuateFrom);
    }

    @Override
    public boolean verifyNode() {
        assertTrue(predecessor() != null || this == graph().start() || this instanceof AbstractMergeNode, "begin nodes must be connected");
        return super.verifyNode();
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        if (bytecodeHandlerIndex != -1) {
            gen.getLIRGeneratorTool().markBlockAsBytecodeHandlerStart(bytecodeHandlerIndex);
        }

        if (hasSpeculationFence) {
            gen.getLIRGeneratorTool().emitSpeculationFence();
        }
    }

    public boolean isUsedAsGuardInput() {
        if (this.hasUsages()) {
            for (Node n : usages()) {
                for (Position inputPosition : n.inputPositions()) {
                    if (inputPosition.getInputType() == InputType.Guard && inputPosition.get(n) == this) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public NodeIterable<GuardNode> guards() {
        return usages().filter(GuardNode.class);
    }

    public NodeIterable<Node> anchored() {
        return usages();
    }

    public boolean hasAnchored() {
        return this.hasUsages();
    }

    public NodeIterable<FixedNode> getBlockNodes() {
        return new NodeIterable<>() {

            @Override
            public Iterator<FixedNode> iterator() {
                return new BlockNodeIterator(AbstractBeginNode.this);
            }
        };
    }

    /**
     * Set this begin node to be a speculation fence. This will prevent speculative execution of
     * this block.
     */
    public void setHasSpeculationFence() {
        this.hasSpeculationFence = true;
    }

    public boolean hasSpeculationFence() {
        return hasSpeculationFence;
    }

    /**
     * Determines if the optimizer is allowed to move guards from the current begin to earlier
     * points in control flow.
     */
    public boolean mustNotMoveAttachedGuards() {
        return false;
    }

    private static class BlockNodeIterator implements Iterator<FixedNode> {

        private FixedNode current;

        BlockNodeIterator(FixedNode next) {
            this.current = next;
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public FixedNode next() {
            FixedNode ret = current;
            if (ret == null) {
                throw new NoSuchElementException();
            }
            if (current instanceof FixedWithNextNode) {
                current = ((FixedWithNextNode) current).next();
                if (current instanceof AbstractBeginNode) {
                    current = null;
                }
            } else {
                current = null;
            }
            return ret;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
