package org.graalvm.compiler.lir;

import java.util.ArrayList;

import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.gen.LIRGenerator;
import org.graalvm.compiler.lir.phases.PreAllocationOptimizationPhase;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.Value;

public class EarlyThreadCodeInterpPhase extends PreAllocationOptimizationPhase {

    private static Variable addIncommingBytecodeIndexVar(LIRGenerator lirGen, LIR ir,
                    LIRKind bytecodeIndexKind,
                    int blockIdx, int blockId,
                    AbstractBlockBase<?>[] blocks) {
        AbstractBlockBase<?> block = blocks[blockIdx];
        assert block.getId() == blockId;

        Variable var = lirGen.newVariable(bytecodeIndexKind);

        ArrayList<LIRInstruction> b = ir.getLIRforBlock(block);
        LabelOp label = (LabelOp) b.get(0);
        label.addIncomingValues(new Value[]{var});
        return var;
    }

    private static void addBytecodeDispatchPrelude(LIRGenerator lirGen, LIR ir,
                    LIRKind bytecodeKind, LIRKind bytecodeIndexKind, LIRKind qwordKind,
                    int blockIdx, int blockId, AbstractBlockBase<?>[] blocks,
                    Variable bytecodeIndexIn,
                    LIRInstruction readByteFromBytecodeArray, LIRInstruction incBytecodeIndex,
                    LIRInstruction tableSwitch) {
        AbstractBlockBase<?> block = blocks[blockIdx];
        assert block.getId() == blockId;

        ArrayList<LIRInstruction> b = ir.getLIRforBlock(block);
        LIRInstruction last = b.get(b.size() - 1);

        // 0.2 Create result variable for bytecode read
        Variable bytecodeResult = lirGen.newVariable(bytecodeKind);

        // 0.3 Create result variable for bytecode index increment
        Variable bytecodeIndexIncremented = lirGen.newVariable(bytecodeIndexKind);

        // 1. add instruction to read from bytecode array
        // 1.1 create bytecode read instruction with new target variable
        LIRInstruction adaptedReadByteFromBytecodeArray = lirGen.getArithmetic().makeAdaptedCopy(
                        readByteFromBytecodeArray, bytecodeIndexIn, bytecodeResult);

        b.add(b.size() - 1, adaptedReadByteFromBytecodeArray);

        // 2. incremented bytecode index
        LIRInstruction adapterBytecodeIndexIncrement = lirGen.getArithmetic().makeAdaptedCopy(incBytecodeIndex, bytecodeIndexIn, bytecodeIndexIncremented);
        b.add(b.size() - 1, adapterBytecodeIndexIncrement);

        // TODO: do this in other pass after everything else is done
        // 3. add range table switch
        // 3.1 adapt the range variable to use the new variable from the bytecode read

        // 4. update the outgoing value of jump instruction to use output variable of
        // increment
        JumpOp jump = (JumpOp) last;
        assert b.get(b.size() - 1) == last;
        jump.clearOutgoingValues();
        jump.addOutgoingValue(bytecodeIndexIncremented);

        // Replace Jump with a dummy jump/switch combo
        LIRInstruction adaptedJumpSwitchDummy = lirGen.getArithmetic().makeAdaptedDummy(jump,
                        bytecodeResult, bytecodeKind, qwordKind);
        b.set(b.size() - 1, adaptedJumpSwitchDummy);
    }

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, PreAllocationOptimizationContext context) {
        LIR ir = lirGenRes.getLIR();
        if (!ir.getControlFlowGraph().isComputeBytecodeLoop() || ir.getControlFlowGraph().isDeoptTarget()) {
            return;
        }

        AbstractBlockBase<?>[] blocks = ir.linearScanOrder();

// ArrayList<LIRInstruction> blockWithReadOfBytecodeArray = ir.getLIRforBlock(blocks[1]);
// LIRInstruction bytecodeArrayReadIntoV5 = blockWithReadOfBytecodeArray.get(4);

        ArrayList<LIRInstruction> blockWithBytecodeDispatch = ir.getLIRforBlock(blocks[2]);
        LIRInstruction readByteFromBytecodeArray = blockWithBytecodeDispatch.get(2);
        LIRInstruction incBytecodeIndex = blockWithBytecodeDispatch.get(3);
        LIRInstruction tableSwitch = blockWithBytecodeDispatch.get(4);

        LIRGenerator lirGen = (LIRGenerator) context.lirGen;
        LIRKind qwordKind = lirGen.getValueKind(readByteFromBytecodeArray);
        LIRKind bytecodeKind = lirGen.getResultLIRKind(readByteFromBytecodeArray);
        LIRKind bytecodeIndexKind = lirGen.getResultLIRKind(incBytecodeIndex);

        // create the input variables for the target blocks of the range switch
        // and add bytecode index as input
        // strictly speaking, this needs to be an input on the block that the dispatch
        // jumps to, not the last block in the series of blocks that eventually has the jump to
        // the next at the end there, it really just needs to be an output
        Variable bytecodeIndexInB3 = addIncommingBytecodeIndexVar(lirGen, ir, bytecodeIndexKind, 4, 3, blocks);
        Variable bytecodeIndexInB5 = addIncommingBytecodeIndexVar(lirGen, ir, bytecodeIndexKind, 6, 5, blocks);
        Variable bytecodeIndexInB8 = addIncommingBytecodeIndexVar(lirGen, ir, bytecodeIndexKind, 10, 8, blocks);
        Variable bytecodeIndexInB11 = addIncommingBytecodeIndexVar(lirGen, ir, bytecodeIndexKind, 12, 11, blocks);
        Variable bytecodeIndexInB14 = addIncommingBytecodeIndexVar(lirGen, ir, bytecodeIndexKind, 9, 14, blocks);
        Variable bytecodeIndexInB15 = addIncommingBytecodeIndexVar(lirGen, ir, bytecodeIndexKind, 8, 15, blocks);

        addBytecodeDispatchPrelude(lirGen, ir, bytecodeKind, bytecodeIndexKind, qwordKind,
                        5, 4, blocks, bytecodeIndexInB3, readByteFromBytecodeArray, incBytecodeIndex,
                        tableSwitch);
        addBytecodeDispatchPrelude(lirGen, ir, bytecodeKind, bytecodeIndexKind, qwordKind,
                        7, 6, blocks, bytecodeIndexInB5, readByteFromBytecodeArray, incBytecodeIndex,
                        tableSwitch);
        addBytecodeDispatchPrelude(lirGen, ir, bytecodeKind, bytecodeIndexKind, qwordKind,
                        11, 9, blocks, bytecodeIndexInB8, readByteFromBytecodeArray, incBytecodeIndex,
                        tableSwitch);
        addBytecodeDispatchPrelude(lirGen, ir, bytecodeKind, bytecodeIndexKind, qwordKind,
                        13, 12, blocks, bytecodeIndexInB11, readByteFromBytecodeArray, incBytecodeIndex,
                        tableSwitch);
        addBytecodeDispatchPrelude(lirGen, ir, bytecodeKind, bytecodeIndexKind, qwordKind,
                        9, 14, blocks, bytecodeIndexInB14, readByteFromBytecodeArray, incBytecodeIndex,
                        tableSwitch);
        addBytecodeDispatchPrelude(lirGen, ir, bytecodeKind, bytecodeIndexKind, qwordKind,
                        8, 15, blocks, bytecodeIndexInB15, readByteFromBytecodeArray, incBytecodeIndex,
                        tableSwitch);
    }

}
