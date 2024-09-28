/*
 * Copyright (c) 2009, 2018, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.lir.alloc.trace.lsra;

import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.isRegister;
import static jdk.graal.compiler.lir.LIRValueUtil.asVariable;
import static jdk.graal.compiler.lir.alloc.trace.lsra.TraceLinearScanPhase.isVariableOrRegister;

import java.util.ArrayList;
import java.util.EnumSet;

import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.core.common.cfg.BlockMap;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.debug.Indent;
import jdk.graal.compiler.lir.InstructionValueConsumer;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.LIRInstruction.OperandFlag;
import jdk.graal.compiler.lir.LIRInstruction.OperandMode;
import jdk.graal.compiler.lir.Variable;
import jdk.graal.compiler.lir.alloc.trace.lsra.TraceLinearScanPhase.TraceLinearScan;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Value;

final class TraceRegisterVerifier {

    TraceLinearScan allocator;
    ArrayList<BasicBlock<?>> workList; // all blocks that must be processed
    BlockMap<TraceInterval[]> savedStates; // saved information of previous check

    // simplified access to methods of LinearScan
    TraceInterval intervalAt(Variable operand) {
        return allocator.intervalFor(operand);
    }

    // currently, only registers are processed
    int stateSize() {
        return allocator.numRegisters();
    }

    // accessors
    TraceInterval[] stateForBlock(BasicBlock<?> block) {
        return savedStates.get(block);
    }

    void setStateForBlock(BasicBlock<?> block, TraceInterval[] savedState) {
        savedStates.put(block, savedState);
    }

    void addToWorkList(BasicBlock<?> block) {
        if (!workList.contains(block)) {
            workList.add(block);
        }
    }

    TraceRegisterVerifier(TraceLinearScan allocator) {
        this.allocator = allocator;
        workList = new ArrayList<>(16);
        this.savedStates = new BlockMap<>(allocator.getLIR().getControlFlowGraph());

    }

    @SuppressWarnings("try")
    void verify(BasicBlock<?> start) {
        DebugContext debug = allocator.getDebug();
        try (DebugContext.Scope s = debug.scope("RegisterVerifier")) {
            // setup input registers (method arguments) for first block
            TraceInterval[] inputState = new TraceInterval[stateSize()];
            setStateForBlock(start, inputState);
            addToWorkList(start);

            // main loop for verification
            do {
                BasicBlock<?> block = workList.get(0);
                workList.remove(0);

                processBlock(block);
            } while (!workList.isEmpty());
        }
    }

    @SuppressWarnings("try")
    private void processBlock(BasicBlock<?> block) {
        DebugContext debug = allocator.getDebug();
        try (Indent indent = debug.logAndIndent("processBlock B%d", block.getId())) {
            // must copy state because it is modified
            TraceInterval[] inputState = copy(stateForBlock(block));

            try (Indent indent2 = debug.logAndIndent("Input-State of intervals:")) {
                printState(inputState);
            }

            // process all operations of the block
            processOperations(block, inputState);

            try (Indent indent2 = debug.logAndIndent("Output-State of intervals:")) {
                printState(inputState);
            }

            // iterate all successors
            for (int j = 0; j < block.getSuccessorCount(); j += 1) {
                BasicBlock<?> succ = block.getSuccessorAt(j);
                processSuccessor(succ, inputState);
            }
        }
    }

    protected void printState(TraceInterval[] inputState) {
        DebugContext debug = allocator.getDebug();
        for (int i = 0; i < stateSize(); i++) {
            Register reg = allocator.getRegisters().get(i);
            assert reg.number == i : "register number mismatch";
            if (inputState[i] != null) {
                debug.log(" %6s %4d  --  %s", reg, inputState[i].operandNumber, inputState[i]);
            } else {
                debug.log(" %6s   __", reg);
            }
        }
    }

    private void processSuccessor(BasicBlock<?> block, TraceInterval[] inputState) {
        TraceInterval[] savedState = stateForBlock(block);

        DebugContext debug = allocator.getDebug();
        if (savedState != null) {
            // this block was already processed before.
            // check if new inputState is consistent with savedState

            boolean savedStateCorrect = true;
            for (int i = 0; i < stateSize(); i++) {
                if (inputState[i] != savedState[i]) {
                    // current inputState and previous savedState assume a different
                    // interval in this register . assume that this register is invalid
                    if (savedState[i] != null) {
                        // invalidate old calculation only if it assumed that
                        // register was valid. when the register was already invalid,
                        // then the old calculation was correct.
                        savedStateCorrect = false;
                        savedState[i] = null;

                        debug.log("processSuccessor B%d: invalidating slot %d", block.getId(), i);
                    }
                }
            }

            if (savedStateCorrect) {
                // already processed block with correct inputState
                debug.log("processSuccessor B%d: previous visit already correct", block.getId());
            } else {
                // must re-visit this block
                debug.log("processSuccessor B%d: must re-visit because input state changed", block.getId());
                addToWorkList(block);
            }

        } else {
            // block was not processed before, so set initial inputState
            debug.log("processSuccessor B%d: initial visit", block.getId());

            setStateForBlock(block, copy(inputState));
            addToWorkList(block);
        }
    }

    static TraceInterval[] copy(TraceInterval[] inputState) {
        return inputState.clone();
    }

    static void statePut(DebugContext debug, TraceInterval[] inputState, Value location, TraceInterval interval) {
        if (location != null && isRegister(location)) {
            Register reg = asRegister(location);
            int regNum = reg.number;
            if (interval != null) {
                debug.log("%s = v%d", reg, interval.operandNumber);
            } else if (inputState[regNum] != null) {
                debug.log("%s = null", reg);
            }

            inputState[regNum] = interval;
        }
    }

    static boolean checkState(BasicBlock<?> block, LIRInstruction op, TraceInterval[] inputState, Value operand, Value reg, TraceInterval interval) {
        if (reg != null && isRegister(reg)) {
            if (inputState[asRegister(reg).number] != interval) {
                throw new GraalError(
                                "Error in register allocation: operation (%s) in block %s expected register %s (operand %s) to contain the value of interval %s but data-flow says it contains interval %s",
                                op, block, reg, operand, interval, inputState[asRegister(reg).number]);
            }
        }
        return true;
    }

    void processOperations(BasicBlock<?> block, final TraceInterval[] inputState) {
        ArrayList<LIRInstruction> ops = allocator.getLIR().getLIRforBlock(block);
        DebugContext debug = allocator.getDebug();
        InstructionValueConsumer useConsumer = new InstructionValueConsumer() {

            @Override
            public void visitValue(LIRInstruction op, Value operand, OperandMode mode, EnumSet<OperandFlag> flags) {
                // we skip spill moves inserted by the spill position optimization
                if (isVariableOrRegister(operand) && allocator.isProcessed(operand) && op.id() != TraceLinearScanPhase.DOMINATOR_SPILL_MOVE_ID) {
                    TraceInterval interval = intervalAt(asVariable(operand));
                    if (op.id() != -1) {
                        interval = interval.getSplitChildAtOpId(op.id(), mode);
                    }

                    assert checkState(block, op, inputState, allocator.getOperand(interval), interval.location(), interval.splitParent());
                }
            }
        };

        InstructionValueConsumer defConsumer = (op, operand, mode, flags) -> {
            if (isVariableOrRegister(operand) && allocator.isProcessed(operand)) {
                TraceInterval interval = intervalAt(asVariable(operand));
                if (op.id() != -1) {
                    interval = interval.getSplitChildAtOpId(op.id(), mode);
                }

                statePut(debug, inputState, interval.location(), interval.splitParent());
            }
        };

        // visit all instructions of the block
        for (int i = 0; i < ops.size(); i++) {
            final LIRInstruction op = ops.get(i);

            if (debug.isLogEnabled()) {
                debug.log("%s", op.toStringWithIdPrefix());
            }

            // check if input operands are correct
            op.visitEachInput(useConsumer);
            // invalidate all caller save registers at calls
            if (op.destroysCallerSavedRegisters()) {
                for (Register r : allocator.getRegisterAllocationConfig().getRegisterConfig().getCallerSaveRegisters()) {
                    statePut(debug, inputState, r.asValue(), null);
                }
            }
            op.visitEachAlive(useConsumer);
            // set temp operands (some operations use temp operands also as output operands, so
            // can't set them null)
            op.visitEachTemp(defConsumer);
            // set output operands
            op.visitEachOutput(defConsumer);
        }
    }
}
