/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.core.common.alloc;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.PriorityQueue;

import jdk.graal.compiler.core.common.alloc.TraceBuilderResult.TrivialTracePredicate;
import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.Indent;
import jdk.graal.compiler.lir.LIR;

/**
 * Computes traces by starting at a trace head and keep adding successors as long as possible.
 */
public final class UniDirectionalTraceBuilder {

    public static TraceBuilderResult computeTraces(DebugContext debug, BasicBlock<?> startBlock, int[] blocks, TrivialTracePredicate pred, LIR lir) {
        return new UniDirectionalTraceBuilder(blocks, lir).build(debug, startBlock, blocks, pred);
    }

    private final PriorityQueue<BasicBlock<?>> worklist;
    private final BitSet processed;
    /**
     * Contains the number of unprocessed predecessors for every {@link BasicBlock#getId() block}.
     */
    private final int[] blocked;
    private final Trace[] blockToTrace;

    private UniDirectionalTraceBuilder(int[] blocks, LIR lir) {
        processed = new BitSet(blocks.length);
        worklist = new PriorityQueue<>(UniDirectionalTraceBuilder::compare);
        assert (worklist != null);

        blocked = new int[blocks.length];
        blockToTrace = new Trace[blocks.length];
        for (int blockId : blocks) {
            blocked[blockId] = lir.getBlockById(blockId).getPredecessorCount();
        }
    }

    private static int compare(BasicBlock<?> a, BasicBlock<?> b) {
        return Double.compare(b.getRelativeFrequency(), a.getRelativeFrequency());
    }

    private boolean processed(BasicBlock<?> b) {
        return processed.get(b.getId());
    }

    @SuppressWarnings("try")
    private TraceBuilderResult build(DebugContext debug, BasicBlock<?> startBlock, int[] blocks, TrivialTracePredicate pred) {
        try (Indent indent = debug.logAndIndent("UniDirectionalTraceBuilder: start trace building: %s", startBlock)) {
            ArrayList<Trace> traces = buildTraces(debug, startBlock);
            return TraceBuilderResult.create(debug, blocks, traces, blockToTrace, pred);
        }
    }

    protected ArrayList<Trace> buildTraces(DebugContext debug, BasicBlock<?> startBlock) {
        ArrayList<Trace> traces = new ArrayList<>();
        // add start block
        worklist.add(startBlock);
        // process worklist
        while (!worklist.isEmpty()) {
            BasicBlock<?> block = worklist.poll();
            assert block != null;
            if (!processed(block)) {
                Trace trace = new Trace(findTrace(debug, block));
                for (BasicBlock<?> traceBlock : trace.getBlocks()) {
                    blockToTrace[traceBlock.getId()] = trace;
                }
                trace.setId(traces.size());
                traces.add(trace);
            }
        }
        return traces;
    }

    /**
     * Build a new trace starting at {@code block}.
     */
    @SuppressWarnings("try")
    private List<BasicBlock<?>> findTrace(DebugContext debug, BasicBlock<?> traceStart) {
        assert checkPredecessorsProcessed(traceStart);
        ArrayList<BasicBlock<?>> trace = new ArrayList<>();
        int blockNumber = 0;
        try (Indent i = debug.logAndIndent("StartTrace: %s", traceStart)) {
            for (BasicBlock<?> block = traceStart; block != null; block = selectNext(block)) {
                debug.log("add %s (freq: %f)", block, block.getRelativeFrequency());
                processed.set(block.getId());
                trace.add(block);
                unblock(block);
                block.setLinearScanNumber(blockNumber++);
            }
        }
        return trace;
    }

    private boolean checkPredecessorsProcessed(BasicBlock<?> block) {
        for (int j = 0; j < block.getPredecessorCount(); j += 1) {
            BasicBlock<?> pred = block.getPredecessorAt(j);
            assert processed(pred) : "Predecessor unscheduled: " + pred;
        }
        return true;
    }

    /**
     * Decrease the {@link #blocked} count for all predecessors and add them to the worklist once
     * the count reaches 0.
     */
    private void unblock(BasicBlock<?> block) {
        for (int j = 0; j < block.getSuccessorCount(); j += 1) {
            BasicBlock<?> successor = block.getSuccessorAt(j);
            if (!processed(successor)) {
                int blockCount = --blocked[successor.getId()];
                assert blockCount >= 0 : "Unexpected negative block count: " + blockCount;
                if (blockCount == 0) {
                    worklist.add(successor);
                }
            }
        }
    }

    /**
     * @return The unprocessed predecessor with the highest probability, or {@code null}.
     */
    private BasicBlock<?> selectNext(BasicBlock<?> block) {
        BasicBlock<?> next = null;
        for (int j = 0; j < block.getSuccessorCount(); j += 1) {
            BasicBlock<?> successor = block.getSuccessorAt(j);
            if (!processed(successor) && (next == null || successor.getRelativeFrequency() > next.getRelativeFrequency())) {
                next = successor;
            }
        }
        return next;
    }
}
