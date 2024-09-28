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
package jdk.graal.compiler.lir.alloc.trace;

import java.util.ArrayList;

import jdk.graal.compiler.core.common.alloc.Trace;
import jdk.graal.compiler.core.common.alloc.TraceBuilderResult;
import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.StandardOp.JumpOp;
import jdk.graal.compiler.lir.StandardOp.LabelOp;

import jdk.vm.ci.meta.Value;

public class TraceUtil {

    public static boolean isShadowedRegisterValue(Value value) {
        assert value != null;
        return value instanceof ShadowedRegisterValue;
    }

    public static ShadowedRegisterValue asShadowedRegisterValue(Value value) {
        assert isShadowedRegisterValue(value);
        return (ShadowedRegisterValue) value;
    }

    public static boolean isTrivialTrace(LIR lir, Trace trace) {
        if (trace.size() != 1) {
            return false;
        }
        ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(trace.getBlocks()[0]);
        if (instructions.size() != 2) {
            return false;
        }
        assert instructions.get(0) instanceof LabelOp : "First instruction not a LabelOp: " + instructions.get(0);
        if (((LabelOp) instructions.get(0)).isPhiIn()) {
            /*
             * Merge blocks are in general not trivial block because variables defined by a PHI
             * always need a location. If the outgoing value of the predecessor is a constant we
             * need to find an appropriate location (register or stack).
             *
             * Note that this case should not happen in practice since the trace containing the
             * merge block should also contain one of the predecessors. For non-standard trace
             * builders (e.g. the single block trace builder) this is not the true, though.
             */
            return false;
        }
        /*
         * Now we need to check if the BlockEndOp has no special operand requirements (i.e.
         * stack-slot, register). For now we just check for JumpOp because we know that it doesn't.
         */
        return instructions.get(1) instanceof JumpOp;
    }

    public static boolean hasInterTracePredecessor(TraceBuilderResult result, Trace trace, BasicBlock<?> block) {
        assert result.getTraceForBlock(block).equals(trace);
        if (block.getPredecessorCount() == 0) {
            // start block
            return false;
        }
        if (block.getPredecessorCount() == 1) {
            return !result.getTraceForBlock(block.getPredecessorAt(0)).equals(trace);
        }
        return true;
    }

    public static boolean hasInterTraceSuccessor(TraceBuilderResult result, Trace trace, BasicBlock<?> block) {
        assert result.getTraceForBlock(block).equals(trace);
        if (block.getSuccessorCount() == 0) {
            // method end block
            return false;
        }
        if (block.getSuccessorCount() == 1) {
            return !result.getTraceForBlock(block.getSuccessorAt(0)).equals(trace);
        }
        return true;
    }
}
