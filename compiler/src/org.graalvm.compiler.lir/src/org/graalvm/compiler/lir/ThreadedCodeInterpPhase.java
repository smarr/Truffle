package org.graalvm.compiler.lir;

import java.util.ArrayList;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.gen.LIRGenerator;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool.BlockScope;
import org.graalvm.compiler.lir.gen.MoveFactory;
import org.graalvm.compiler.lir.phases.PostAllocationOptimizationPhase;

import jdk.vm.ci.code.TargetDescription;

public class ThreadedCodeInterpPhase extends PostAllocationOptimizationPhase {

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, PostAllocationOptimizationContext context) {
        LIRGenerator lirGen = (LIRGenerator) context.diagnosticLirGenTool;

        LIR ir = lirGenRes.getLIR();
        if (!ir.getControlFlowGraph().isComputeBytecodeLoop() || ir.getControlFlowGraph().isDeoptTarget()) {
            return;
        }

        AbstractBlockBase<?>[] blocks = ir.linearScanOrder();

        ArrayList<LIRInstruction> blockWithRangeTableSwitch = ir.getLIRforBlock(blocks[2]);
        LIRInstruction rangeTableSwitch = blockWithRangeTableSwitch.get(5);

// if (true) {
// return;
// }

// ArrayList<LIRInstruction> blockBytecodeArrayPtrAndUncompress = ir.getLIRforBlock(blocks[1]);
// ArrayList<LIRInstruction> blockWithStoreToR8 = ir.getLIRforBlock(blocks[4]);
// ArrayList<LIRInstruction> blockBytecodeWithALoadOfThis = ir.getLIRforBlock(blocks[5]);
//
// LIRInstruction storeOfR8 = blockWithStoreToR8.get(1);

// LIRInstruction loadThisIntoRdi = blockBytecodeWithALoadOfThis.get(1);
// if (!"rdi|QWORD[.] = MOVE stack:40|QWORD[.] moveKind: QWORD".equals(loadThisIntoRdi.toString()))
// {
// return;
// }

        for (int i = 1; i < blocks.length; i += 1) {
            AbstractBlockBase<?> block = blocks[i];
            if (block == null) {
                continue;
            }

            switch (block.getId()) {
                case 4:
                case 6:
                case 9:
                case 12:
                case 14:
                case 15: {
                    ArrayList<LIRInstruction> b = ir.getLIRforBlock(block);
                    LIRInstruction last = b.get(b.size() - 1);

                    LIRInstruction properRangeSwitchOp = lirGen.getArithmetic().makeProper(last, rangeTableSwitch);
                    b.set(b.size() - 1, properRangeSwitchOp);

                }
                default:
            }

        }

        // TODO: can I find the RangeTableSwitchOp in it.litInstructions[2]? and copy it to the
        // correct places?
    }
}
