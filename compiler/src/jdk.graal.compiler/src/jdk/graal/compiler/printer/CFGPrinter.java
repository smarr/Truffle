/*
 * Copyright (c) 2009, 2022, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.printer;

import static java.lang.Character.toLowerCase;
import static jdk.graal.compiler.core.match.ComplexMatchValue.INTERIOR_MATCH;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jdk.graal.compiler.bytecode.Bytecode;
import jdk.graal.compiler.bytecode.BytecodeDisassembler;
import jdk.graal.compiler.core.common.alloc.Trace;
import jdk.graal.compiler.core.common.alloc.TraceBuilderResult;
import jdk.graal.compiler.core.common.cfg.AbstractControlFlowGraph;
import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.core.gen.NodeLIRBuilder;
import jdk.graal.compiler.core.match.ComplexMatchValue;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeBitMap;
import jdk.graal.compiler.graph.NodeMap;
import jdk.graal.compiler.graph.Position;
import jdk.graal.compiler.java.BciBlockMapping;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.alloc.trace.GlobalLivenessInfo;
import jdk.graal.compiler.lir.debug.IntervalDumper;
import jdk.graal.compiler.lir.debug.IntervalDumper.IntervalVisitor;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.nodeinfo.Verbosity;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.AbstractEndNode;
import jdk.graal.compiler.nodes.AbstractMergeNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.PhiNode;
import jdk.graal.compiler.nodes.StateSplit;
import jdk.graal.compiler.nodes.StructuredGraph.ScheduleResult;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.calc.FloatingNode;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.graal.compiler.nodes.cfg.HIRBlock;

import jdk.vm.ci.code.DebugInfo;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Value;
import org.graalvm.collections.UnmodifiableMapCursor;

/**
 * Utility for printing Graal IR at various compilation phases.
 */
class CFGPrinter extends CompilationPrinter {

    protected LIR lir;
    protected NodeLIRBuilder nodeLirGenerator;
    protected ControlFlowGraph cfg;
    protected ScheduleResult schedule;
    protected ResolvedJavaMethod method;
    protected GlobalLivenessInfo livenessInfo;
    protected LIRGenerationResult res;

    /**
     * Creates a control flow graph printer.
     *
     * @param out where the output generated via this printer shown be written
     */
    CFGPrinter(OutputStream out) {
        super(out);
    }

    /**
     * Prints the control flow graph denoted by a given block map.
     *
     * @param label A label describing the compilation phase that produced the control flow graph.
     * @param blockMap A data structure describing the blocks in a method and how they are
     *            connected.
     */
    public void printCFG(String label, BciBlockMapping blockMap) {
        begin("cfg");
        out.print("name \"").print(label).println('"');
        for (BciBlockMapping.BciBlock block : blockMap.getBlocks()) {
            begin("block");
            printBlock(block);
            end("block");
        }
        end("cfg");
    }

    private void printBlock(BciBlockMapping.BciBlock block) {
        out.print("name \"B").print(block.getStartBci()).println('"');
        out.print("from_bci ").println(block.getStartBci());
        out.print("to_bci ").println(block.getEndBci());

        out.println("predecessors ");

        out.print("successors ");
        for (BciBlockMapping.BciBlock succ : block.getSuccessors()) {
            if (!succ.isExceptionEntry()) {
                out.print("\"B").print(succ.getStartBci()).print("\" ");
            }
        }
        out.println();

        out.print("xhandlers");
        for (BciBlockMapping.BciBlock succ : block.getSuccessors()) {
            if (succ.isExceptionEntry()) {
                out.print("\"B").print(succ.getStartBci()).print("\" ");
            }
        }
        out.println();

        out.print("flags ");
        if (block.isExceptionEntry()) {
            out.print("\"ex\" ");
        }
        if (block.isLoopHeader()) {
            out.print("\"plh\" ");
        }
        out.println();

        out.print("loop_depth ").println(block.getLoops().cardinality());
    }

    private NodeMap<HIRBlock> latestScheduling;
    private NodeBitMap printedNodes;

    private boolean inFixedSchedule(Node node) {
        return lir != null || schedule != null || node.isDeleted() || cfg.getNodeToBlock().get(node) != null;
    }

    /**
     * Prints the specified list of blocks.
     *
     * @param label A label describing the compilation phase that produced the control flow graph.
     * @param blockIds The list of block ids for which the associated blocks have to be printed.
     */
    public void printCFG(String label, int[] blockIds) {
        begin("cfg");
        out.print("name \"").print(label).println('"');
        for (int blockId : blockIds) {
            if (blockId == AbstractControlFlowGraph.INVALID_BLOCK_ID) {
                continue;
            }
            BasicBlock<?> block = cfg.getBlocks()[blockId];
            printBlockProlog(block);
            printBlockEpilog(block);
        }
        end("cfg");
        // NOTE: we do this only because the c1visualizer does not recognize the bytecode block if
        // it is proceeding the cfg blocks. Currently we have no direct influence on the emit order.
        // As a workaround we dump the bytecode after every cfg.
        if (method != null) {
            printBytecodes(new BytecodeDisassembler(false).disassemble(method));
        }

        latestScheduling = null;
    }

    private void scheduleInputs(Node node, HIRBlock nodeBlock) {
        if (node instanceof ValuePhiNode) {
            PhiNode phi = (PhiNode) node;
            HIRBlock phiBlock = latestScheduling.get(phi.merge());
            assert phiBlock != null;
            for (int i = 0; i < phiBlock.getPredecessorCount(); i += 1) {
                HIRBlock pred = phiBlock.getPredecessorAt(i);
                schedule(phi.valueAt((AbstractEndNode) pred.getEndNode()), pred);
            }

        } else {
            for (Node input : node.inputs()) {
                schedule(input, nodeBlock);
            }
        }
    }

    private void schedule(Node input, HIRBlock block) {
        if (!inFixedSchedule(input)) {
            HIRBlock inputBlock = block;
            if (latestScheduling.get(input) != null) {
                inputBlock = AbstractControlFlowGraph.commonDominatorTyped(inputBlock, latestScheduling.get(input));
            }
            if (inputBlock != latestScheduling.get(input)) {
                latestScheduling.set(input, inputBlock);
                scheduleInputs(input, inputBlock);
            }
        }
    }

    private void printBlock(BasicBlock<?> block, boolean printNodes) {
        if (block == null) {
            return;
        }
        printBlockProlog(block);
        if (printNodes) {
            assert block instanceof HIRBlock;
            printNodes((HIRBlock) block);
        }
        printBlockEpilog(block);
    }

    private void printBlockEpilog(BasicBlock<?> block) {
        printLIR(block);
        end("block");
    }

    private void printBlockProlog(BasicBlock<?> block) {
        begin("block");

        out.print("name \"").print(blockToString(block)).println('"');
        out.println("from_bci -1");
        out.println("to_bci -1");

        out.print("predecessors ");
        for (int i = 0; i < block.getPredecessorCount(); i++) {
            BasicBlock<?> pred = block.getPredecessorAt(i);
            out.print("\"").print(blockToString(pred)).print("\" ");
        }
        out.println();

        out.print("successors ");
        for (int i = 0; i < block.getSuccessorCount(); i++) {
            BasicBlock<?> succ = block.getSuccessorAt(i);
            if (!succ.isExceptionEntry()) {
                out.print("\"").print(blockToString(succ)).print("\" ");
            }
        }
        out.println();

        out.print("xhandlers");
        for (int i = 0; i < block.getSuccessorCount(); i++) {
            BasicBlock<?> succ = block.getSuccessorAt(i);
            if (succ.isExceptionEntry()) {
                out.print("\"").print(blockToString(succ)).print("\" ");
            }
        }
        out.println();

        out.print("flags ");
        if (block.isLoopHeader()) {
            out.print("\"llh\" ");
        }
        if (block.isLoopEnd()) {
            out.print("\"lle\" ");
        }
        if (block.isExceptionEntry()) {
            out.print("\"ex\" ");
        }
        out.println();

        if (block.getLoop() != null) {
            out.print("loop_index ").println(block.getLoop().getIndex());
            out.print("loop_depth ").println(block.getLoop().getDepth());
        }

        out.print("probability ").println(Double.doubleToRawLongBits(block.getRelativeFrequency()));
    }

    private void printNodes(HIRBlock block) {
        printedNodes = new NodeBitMap(cfg.graph);
        begin("IR");
        out.println("HIR");
        out.disableIndentation();

        if (block.getBeginNode() instanceof AbstractMergeNode) {
            // Currently phi functions are not in the schedule, so print them separately here.
            for (ValueNode phi : ((AbstractMergeNode) block.getBeginNode()).phis()) {
                printNode(phi, false);
            }
        }

        Node cur = block.getBeginNode();
        while (true) { // TERMINATION ARGUMENT: suppress checkstyle
            printNode(cur, false);

            if (cur == block.getEndNode()) {
                UnmodifiableMapCursor<Node, HIRBlock> cursor = latestScheduling.getEntries();
                while (cursor.advance()) {
                    if (cursor.getValue() == block && !inFixedSchedule(cursor.getKey()) && !printedNodes.isMarked(cursor.getKey())) {
                        printNode(cursor.getKey(), true);
                    }
                }
                break;
            }
            assert cur.successors().count() == 1;
            cur = cur.successors().first();
        }

        out.enableIndentation();
        end("IR");
        printedNodes = null;
    }

    private void printNode(Node node, boolean unscheduled) {
        assert !printedNodes.isMarked(node);
        printedNodes.mark(node);

        if (!(node instanceof ValuePhiNode)) {
            for (Node input : node.inputs()) {
                if (!inFixedSchedule(input) && !printedNodes.isMarked(input)) {
                    printNode(input, true);
                }
            }
        }

        if (unscheduled) {
            assert lir == null && schedule == null : "unscheduled nodes can only be present before LIR generation";
            out.print("f ").print(HOVER_START).print("u").print(HOVER_SEP).print("unscheduled").print(HOVER_END).println(COLUMN_END);
        } else if (node instanceof FixedWithNextNode) {
            out.print("f ").print(HOVER_START).print("#").print(HOVER_SEP).print("fixed with next").print(HOVER_END).println(COLUMN_END);
        } else if (node instanceof FixedNode) {
            out.print("f ").print(HOVER_START).print("*").print(HOVER_SEP).print("fixed").print(HOVER_END).println(COLUMN_END);
        } else if (node instanceof FloatingNode) {
            out.print("f ").print(HOVER_START).print("~").print(HOVER_SEP).print("floating").print(HOVER_END).println(COLUMN_END);
        }
        out.print("tid ").print(nodeToString(node)).println(COLUMN_END);

        if (nodeLirGenerator != null) {
            Value operand = nodeLirGenerator.hasOperand(node) ? nodeLirGenerator.operand(node) : null;
            // Matcher related values aren't real values and confuse the c1visualizer parsing
            if (operand != null && !operand.equals(INTERIOR_MATCH) && !(operand instanceof ComplexMatchValue)) {
                out.print("result ").print(operand.toString()).println(COLUMN_END);
            }
        }

        if (node instanceof StateSplit) {
            StateSplit stateSplit = (StateSplit) node;
            if (stateSplit.stateAfter() != null) {
                String state = stateToString(stateSplit.stateAfter());
                out.print("st ").print(HOVER_START).print("st").print(HOVER_SEP).print(state).print(HOVER_END).println(COLUMN_END);
            }
        }

        Map<Object, Object> props = new TreeMap<>(node.getDebugProperties());
        out.print("d ").print(HOVER_START).print("d").print(HOVER_SEP);
        out.println("=== Debug Properties ===");
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            Object value = entry.getValue();
            String s;
            if (value == null) {
                s = "[null]";
            } else {
                if (value instanceof Object[]) {
                    s = Arrays.toString((Object[]) value);
                } else {
                    s = value.toString();
                }
            }
            out.print(entry.getKey().toString()).print(": ").print(value == null ? "[null]" : s).println();
        }
        out.println("=== Inputs ===");
        printNamedNodes(node, node.inputPositions().iterator(), "", "\n", null);
        out.println("=== Succesors ===");
        printNamedNodes(node, node.successorPositions().iterator(), "", "\n", null);
        out.println("=== Usages ===");
        if (!node.hasNoUsages()) {
            for (Node usage : node.usages()) {
                out.print(nodeToString(usage)).print(" ");
            }
            out.println();
        }
        out.println("=== Predecessor ===");
        out.print(nodeToString(node.predecessor())).print(" ");
        out.print(HOVER_END).println(COLUMN_END);

        out.print("instruction ");
        out.print(HOVER_START).print(node.getNodeClass().shortName()).print(HOVER_SEP).print(node.getClass().getName()).print(HOVER_END).print(" ");
        printNamedNodes(node, node.inputPositions().iterator(), "", "", "#NDF");
        printNamedNodes(node, node.successorPositions().iterator(), "#", "", "#NDF");
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            String key = entry.getKey().toString();
            if (key.startsWith("data.") && !key.equals("data.stamp")) {
                out.print(key.substring("data.".length())).print(": ").print(entry.getValue() == null ? "[null]" : entry.getValue().toString()).print(" ");
            }
        }
        out.print(COLUMN_END).print(' ').println(COLUMN_END);
    }

    private void printNamedNodes(Node node, Iterator<Position> iter, String prefix, String suffix, String hideSuffix) {
        int lastIndex = -1;
        while (iter.hasNext()) {
            Position pos = iter.next();
            if (hideSuffix != null && pos.getName().endsWith(hideSuffix)) {
                continue;
            }

            if (pos.getIndex() != lastIndex) {
                if (lastIndex != -1) {
                    out.print(suffix);
                }
                out.print(prefix).print(pos.getName()).print(": ");
                lastIndex = pos.getIndex();
            }
            out.print(nodeToString(pos.get(node))).print(" ");
        }
        if (lastIndex != -1) {
            out.print(suffix);
        }
    }

    private String stateToString(FrameState state) {
        StringBuilder buf = new StringBuilder();
        FrameState curState = state;
        do {
            buf.append(Bytecode.toLocation(curState.getCode(), curState.bci)).append('\n');

            if (curState.stackSize() > 0) {
                buf.append("stack: ");
                for (int i = 0; i < curState.stackSize(); i++) {
                    buf.append(stateValueToString(curState.stackAt(i))).append(' ');
                }
                buf.append("\n");
            }

            buf.append("locals: ");
            for (int i = 0; i < curState.localsSize(); i++) {
                buf.append(stateValueToString(curState.localAt(i))).append(' ');
            }
            buf.append("\n");

            buf.append("locks: ");
            for (int i = 0; i < curState.locksSize(); i++) {
                buf.append(stateValueToString(curState.lockAt(i))).append(' ');
            }
            buf.append("\n");

            curState = curState.outerFrameState();
        } while (curState != null);

        return buf.toString();
    }

    private String stateValueToString(ValueNode value) {
        String result = nodeToString(value);
        if (nodeLirGenerator != null && value != null && nodeLirGenerator.hasOperand(value)) {
            Value operand = nodeLirGenerator.operand(value);
            assert operand != null;
            result += ": " + operand;
        }
        return result;
    }

    /**
     * Prints the LIR for each instruction in a given block.
     *
     * @param block the block to print
     */
    private void printLIR(BasicBlock<?> block) {
        if (lir == null) {
            return;
        }
        ArrayList<LIRInstruction> lirInstructions = lir.getLIRforBlock(block);
        if (lirInstructions == null) {
            return;
        }

        begin("IR");
        out.println("LIR");

        if (livenessInfo != null) {
            int opId = lirInstructions.get(0).id();
            printLiveVars(livenessInfo.getBlockIn(block), "in(var)", opId);
            printLiveLoc(livenessInfo.getInLocation(block), "in(loc)", opId);
        }
        for (int i = 0; i < lirInstructions.size(); i++) {
            LIRInstruction inst = lirInstructions.get(i);
            printLIRInstruction(inst);
        }
        if (livenessInfo != null) {
            int opId = lirInstructions.get(lirInstructions.size() - 1).id();
            printLiveVars(livenessInfo.getBlockOut(block), "out(var)", opId);
            printLiveLoc(livenessInfo.getOutLocation(block), "out(loc)", opId);
        }
        end("IR");
    }

    private void printLiveVars(int[] live, String lbl, int opId) {
        out.printf("nr %4d ", opId).print(COLUMN_END).print(" instruction ");
        out.print(lbl).print(" [");
        for (int i = 0; i < live.length; i++) {
            if (i > 0) {
                out.print(", ");
            }
            int varNum = live[i];
            Value value = varNum >= 0 ? livenessInfo.getVariable(varNum) : Value.ILLEGAL;
            out.print(i).print(": ").print(value.toString());
        }
        out.print(']');
        out.print(COLUMN_END);
        out.println(COLUMN_END);
    }

    private void printLiveLoc(Value[] values, String lbl, int opId) {
        if (values != null) {
            out.printf("nr %4d ", opId).print(COLUMN_END).print(" instruction ");
            out.print(lbl).print(" [");
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    out.print(", ");
                }
                out.print(i).print(": ").print(values[i].toString());
            }
            out.print(']');
            out.print(COLUMN_END);
            out.println(COLUMN_END);
        }
    }

    private void printLIRInstruction(LIRInstruction inst) {
        if (inst == null) {
            out.print("nr   -1 ").print(COLUMN_END).print(" instruction ").print("<deleted>").print(COLUMN_END);
            out.println(COLUMN_END);
        } else {
            out.printf("nr %4d ", inst.id()).print(COLUMN_END);

            final StringBuilder stateString = new StringBuilder();
            inst.forEachState(state -> {
                if (state.hasDebugInfo()) {
                    DebugInfo di = state.debugInfo();
                    stateString.append(debugInfoToString(di.getBytecodePosition(), di.getReferenceMap(), state.getLiveBasePointers(), di.getCalleeSaveInfo()));
                } else {
                    stateString.append(debugInfoToString(state.topFrame, null, state.getLiveBasePointers(), null));
                }
            });
            if (stateString.length() > 0) {
                int level = out.indentationLevel();
                out.adjustIndentation(-level);
                out.print(" st ").print(HOVER_START).print("st").print(HOVER_SEP).print(stateString.toString()).print(HOVER_END).print(COLUMN_END);
                out.adjustIndentation(level);
            }

            out.print(" instruction ").print(inst.toString(res)).print(COLUMN_END);
            out.println(COLUMN_END);
        }
    }

    private String nodeToString(Node node) {
        if (node == null) {
            return "-";
        }
        String prefix;
        if (node instanceof AbstractBeginNode && (lir == null && schedule == null)) {
            prefix = "B";
        } else if (node instanceof ValueNode) {
            ValueNode value = (ValueNode) node;
            if (value.getStackKind() == JavaKind.Illegal) {
                prefix = "v";
            } else {
                prefix = String.valueOf(toLowerCase(value.getStackKind().getTypeChar()));
            }
        } else {
            prefix = "?";
        }
        return prefix + node.toString(Verbosity.Id);
    }

    private String blockToString(BasicBlock<?> block) {
        if (lir == null && schedule == null && block instanceof HIRBlock) {
            // During all the front-end phases, the block schedule is built only for the debug
            // output.
            // Therefore, the block numbers would be different for every CFG printed -> use the id
            // of the first instruction.
            return "B" + ((HIRBlock) block).getBeginNode().toString(Verbosity.Id);
        } else {
            // LIR instructions contain references to blocks and these blocks are printed as the
            // blockID -> use the blockID.
            return "B" + block.getId();
        }
    }

    IntervalVisitor intervalVisitor = new IntervalVisitor() {

        /**
         * @return a formatted description of the operand that the C1Visualizer can handle.
         */
        String getFormattedOperand(Value operand) {
            String s = operand.toString();
            int last = s.lastIndexOf('|');
            if (last != -1) {
                return s.substring(0, last) + "|" + operand.getPlatformKind().getTypeChar();
            }
            return s;
        }

        @Override
        public void visitIntervalStart(Value parentOperand, Value splitOperand, Value location, Value hint, String typeName) {
            out.printf("%s %s ", getFormattedOperand(splitOperand), typeName);
            if (location != null) {
                out.printf("\"[%s]\"", getFormattedOperand(location));
            } else {
                out.printf("\"[%s]\"", getFormattedOperand(splitOperand));
            }
            out.printf(" %s %s ", getFormattedOperand(parentOperand), hint != null ? getFormattedOperand(hint) : -1);
        }

        @Override
        public void visitRange(int from, int to) {
            out.printf("[%d, %d[", from, to);
        }

        @Override
        public void visitUsePos(int usePos, Object registerPriority) {
            out.printf("%d %s ", usePos, registerPriority);
        }

        @Override
        public void visitIntervalEnd(Object spillState) {
            out.printf(" \"%s\"", spillState);
            out.println();
        }

    };

    public void printIntervals(String label, IntervalDumper intervals) {
        begin("intervals");
        out.println(String.format("name \"%s\"", label));

        intervals.visitIntervals(intervalVisitor);

        end("intervals");
    }

    public void printSchedule(String message, ScheduleResult theSchedule) {
        schedule = theSchedule;
        cfg = schedule.getCFG();
        printedNodes = new NodeBitMap(cfg.graph);

        begin("cfg");
        out.print("name \"").print(message).println('"');
        for (HIRBlock b : schedule.getCFG().getBlocks()) {
            if (schedule.nodesFor(b) != null) {
                printScheduledBlock(b, schedule.nodesFor(b));
            }
        }
        end("cfg");

        schedule = null;
        cfg = null;
        printedNodes = null;
    }

    private void printScheduledBlock(HIRBlock block, List<Node> nodesFor) {
        printBlockProlog(block);
        begin("IR");
        out.println("HIR");
        out.disableIndentation();

        if (block.getBeginNode() instanceof AbstractMergeNode) {
            // Currently phi functions are not in the schedule, so print them separately here.
            for (ValueNode phi : ((AbstractMergeNode) block.getBeginNode()).phis()) {
                printNode(phi, false);
            }
        }

        for (Node n : nodesFor) {
            printNode(n, false);
        }

        out.enableIndentation();
        end("IR");

        printBlockEpilog(block);
    }

    public void printTraces(String label, TraceBuilderResult traces) {
        begin("cfg");
        out.print("name \"").print(label).println('"');

        for (Trace trace : traces.getTraces()) {
            printTrace(trace, traces);
        }

        end("cfg");
    }

    private void printTrace(Trace trace, TraceBuilderResult traceBuilderResult) {
        printTraceProlog(trace, traceBuilderResult);
        printTraceInstructions(trace, traceBuilderResult);
        printTraceEpilog();
    }

    private void printTraceProlog(Trace trace, TraceBuilderResult traceBuilderResult) {
        begin("block");

        out.print("name \"").print(traceToString(trace)).println('"');
        out.println("from_bci -1");
        out.println("to_bci -1");

        out.print("predecessors ");
        for (Trace pred : getPredecessors(trace, traceBuilderResult)) {
            out.print("\"").print(traceToString(pred)).print("\" ");
        }
        out.println();

        out.print("successors ");
        for (Trace succ : getSuccessors(trace, traceBuilderResult)) {
            // if (!succ.isExceptionEntry()) {
            out.print("\"").print(traceToString(succ)).print("\" ");
            // }
        }
        out.println();

        out.print("xhandlers");
        // TODO(je) add support for exception handler
        out.println();

        out.print("flags ");
        // TODO(je) add support for flags
        out.println();
        // TODO(je) add support for loop infos
    }

    private void printTraceInstructions(Trace trace, TraceBuilderResult traceBuilderResult) {
        if (lir == null) {
            return;
        }
        begin("IR");
        out.println("LIR");

        for (BasicBlock<?> block : trace.getBlocks()) {
            ArrayList<LIRInstruction> lirInstructions = lir.getLIRforBlock(block);
            if (lirInstructions == null) {
                continue;
            }
            printBlockInstruction(block, traceBuilderResult);
            for (int i = 0; i < lirInstructions.size(); i++) {
                LIRInstruction inst = lirInstructions.get(i);
                printLIRInstruction(inst);
            }
        }
        end("IR");
    }

    private void printBlockInstruction(BasicBlock<?> block, TraceBuilderResult traceBuilderResult) {
        out.print("nr ").print(block.toString()).print(COLUMN_END).print(" instruction ");

        if (block.getPredecessorCount() > 0) {
            List<BasicBlock<?>> preds = new ArrayList<>();
            for (int i = 0; i < block.getPredecessorCount(); i += 1) {
                preds.add(block.getPredecessorAt(i));
            }

            out.print("<- ");
            printBlockListWithTrace(preds, traceBuilderResult);
            out.print(" ");
        }

        if (block.getSuccessorCount() > 0) {
            List<BasicBlock<?>> succs = new ArrayList<>();
            for (int i = 0; i < block.getSuccessorCount(); i += 1) {
                succs.add(block.getSuccessorAt(i));
            }

            out.print("-> ");
            printBlockListWithTrace(succs, traceBuilderResult);
        }

        out.print(COLUMN_END);
        out.println(COLUMN_END);
    }

    private void printBlockListWithTrace(List<? extends BasicBlock<?>> blocks, TraceBuilderResult traceBuilderResult) {
        Iterator<? extends BasicBlock<?>> it = blocks.iterator();
        printBlockWithTrace(it.next(), traceBuilderResult);
        while (it.hasNext()) {
            out.print(",");
            printBlockWithTrace(it.next(), traceBuilderResult);
        }
    }

    private void printBlockWithTrace(BasicBlock<?> block, TraceBuilderResult traceBuilderResult) {
        out.print(block.toString());
        out.print("[T").print(traceBuilderResult.getTraceForBlock(block).getId()).print("]");
    }

    private void printTraceEpilog() {
        end("block");
    }

    private static boolean isLoopBackEdge(BasicBlock<?> src, BasicBlock<?> dst) {
        return dst.isLoopHeader() && dst.getLoop().equals(src.getLoop());
    }

    private static List<Trace> getSuccessors(Trace trace, TraceBuilderResult traceBuilderResult) {
        BitSet bs = new BitSet(traceBuilderResult.getTraces().size());
        for (BasicBlock<?> block : trace.getBlocks()) {
            for (int j = 0; j < block.getSuccessorCount(); j += 1) {
                BasicBlock<?> s = block.getSuccessorAt(j);
                Trace otherTrace = traceBuilderResult.getTraceForBlock(s);
                int otherTraceId = otherTrace.getId();
                if (trace.getId() != otherTraceId || isLoopBackEdge(block, s)) {
                    bs.set(otherTraceId);
                }
            }
        }
        List<Trace> succ = new ArrayList<>();
        for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
            succ.add(traceBuilderResult.getTraces().get(i));
        }
        return succ;
    }

    private static List<Trace> getPredecessors(Trace trace, TraceBuilderResult traceBuilderResult) {
        BitSet bs = new BitSet(traceBuilderResult.getTraces().size());
        for (BasicBlock<?> block : trace.getBlocks()) {
            for (int j = 0; j < block.getPredecessorCount(); j += 1) {
                BasicBlock<?> p = block.getPredecessorAt(j);
                Trace otherTrace = traceBuilderResult.getTraceForBlock(p);
                int otherTraceId = otherTrace.getId();
                if (trace.getId() != otherTraceId || isLoopBackEdge(p, block)) {
                    bs.set(traceBuilderResult.getTraceForBlock(p).getId());
                }
            }
        }
        List<Trace> pred = new ArrayList<>();
        for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
            pred.add(traceBuilderResult.getTraces().get(i));
        }
        return pred;
    }

    private static String traceToString(Trace trace) {
        return new StringBuilder("T").append(trace.getId()).toString();
    }

}
