/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

import static jdk.graal.compiler.lir.LIRValueUtil.asVariable;
import static jdk.graal.compiler.lir.LIRValueUtil.isVariable;
import static jdk.graal.compiler.lir.alloc.trace.TraceUtil.isTrivialTrace;

import java.util.Arrays;
import java.util.EnumSet;

import jdk.graal.compiler.core.common.alloc.Trace;
import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.LIRInstruction.OperandFlag;
import jdk.graal.compiler.lir.LIRInstruction.OperandMode;
import jdk.graal.compiler.lir.StandardOp.JumpOp;
import jdk.graal.compiler.lir.StandardOp.LabelOp;
import jdk.graal.compiler.lir.ValueProcedure;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.ssa.SSAUtil;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.Value;

/**
 * Allocates a trivial trace i.e. a trace consisting of a single block with no instructions other
 * than the {@link LabelOp} and the {@link JumpOp}.
 */
public final class TrivialTraceAllocator extends TraceAllocationPhase<TraceAllocationPhase.TraceAllocationContext> {

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, Trace trace, TraceAllocationContext context) {
        LIR lir = lirGenRes.getLIR();
        assert isTrivialTrace(lir, trace) : "Not a trivial trace! " + trace;
        BasicBlock<?> block = trace.getBlocks()[0];
        assert TraceAssertions.singleHeadPredecessor(trace) : "Trace head with more than one predecessor?!" + trace;
        BasicBlock<?> pred = block.getPredecessors()[0];

        GlobalLivenessInfo livenessInfo = context.livenessInfo;
        allocate(block, pred, livenessInfo, SSAUtil.phiOutOrNull(lir, block));
    }

    public static void allocate(BasicBlock<?> block, BasicBlock<?> pred, GlobalLivenessInfo livenessInfo, LIRInstruction jump) {
        // exploit that the live sets are sorted
        assert TraceAssertions.liveSetsAreSorted(livenessInfo, block);
        assert TraceAssertions.liveSetsAreSorted(livenessInfo, pred);

        // setup incoming variables/locations
        final int[] blockIn = livenessInfo.getBlockIn(block);
        final Value[] predLocOut = livenessInfo.getOutLocation(pred);
        int inLenght = blockIn.length;

        // setup outgoing variables/locations
        final int[] blockOut = livenessInfo.getBlockOut(block);
        int outLength = blockOut.length;
        final Value[] locationOut = new Value[outLength];

        assert outLength <= inLenght : "Trivial Trace! There cannot be more outgoing values than incoming.";
        for (int outIdx = 0, inIdx = 0; outIdx < outLength; inIdx++) {
            if (blockOut[outIdx] == blockIn[inIdx]) {
                // set the outgoing location to the incoming value
                locationOut[outIdx++] = predLocOut[inIdx];
            }
        }

        /*
         * Since we do not change any of the location we can just use the outgoing of the
         * predecessor.
         */
        livenessInfo.setInLocations(block, predLocOut);
        livenessInfo.setOutLocations(block, locationOut);
        if (jump != null) {
            handlePhiOut(jump, blockIn, predLocOut);
        }
    }

    private static void handlePhiOut(LIRInstruction jump, int[] varIn, Value[] locIn) {
        // handle outgoing phi values
        ValueProcedure outputConsumer = new ValueProcedure() {
            @Override
            public Value doValue(Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
                if (isVariable(value)) {
                    // since incoming variables are sorted, we can do a binary search
                    return locIn[Arrays.binarySearch(varIn, asVariable(value).index)];
                }
                return value;
            }
        };

        // Jumps have only alive values (outgoing phi values)
        jump.forEachAlive(outputConsumer);
    }

}
