/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates. All rights reserved.
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

import jdk.graal.compiler.core.common.alloc.TraceBuilderResult.TrivialTracePredicate;
import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.debug.DebugContext;

/**
 * Builds traces consisting of a single basic block.
 */
public final class SingleBlockTraceBuilder {

    public static TraceBuilderResult computeTraces(DebugContext debug, BasicBlock<?> startBlock, BasicBlock<?>[] blocks, TrivialTracePredicate pred) {
        return build(debug, startBlock, blocks, pred);
    }

    private static TraceBuilderResult build(DebugContext debug, BasicBlock<?> startBlock, BasicBlock<?>[] blocks, TrivialTracePredicate pred) {
        Trace[] blockToTrace = new Trace[blocks.length];
        ArrayList<Trace> traces = new ArrayList<>(blocks.length);

        for (BasicBlock<?> block : blocks) {
            Trace trace = new Trace(new BasicBlock<?>[]{block});
            blockToTrace[block.getId()] = trace;
            block.setLinearScanNumber(0);
            trace.setId(traces.size());
            traces.add(trace);
        }

        assert traces.get(0).getBlocks()[0].equals(startBlock) : "The first traces always contains the start block";
        return TraceBuilderResult.create(debug, blocks, traces, blockToTrace, pred);
    }

}
