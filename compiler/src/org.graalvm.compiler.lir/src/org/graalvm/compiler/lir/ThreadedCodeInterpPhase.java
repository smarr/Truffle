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
// MoveFactory moveFactory = lirGen.getMoveFactory();
// lirGen.emitMove(null, null);
// lirGen.emitUncompress(null, null, false)

        LIR ir = lirGenRes.getLIR();
        if (!ir.getControlFlowGraph().isComputeBytecodeLoop()) {
            return;
        }

        AbstractBlockBase<?>[] blocks = ir.linearScanOrder();

        ArrayList<LIRInstruction> blockBytecodeArrayPtrAndUncompress = ir.getLIRforBlock(blocks[1]);
        ArrayList<LIRInstruction> blockWithStoreToR8 = ir.getLIRforBlock(blocks[4]);
        ArrayList<LIRInstruction> blockBytecodeWithALoadOfThis = ir.getLIRforBlock(blocks[5]);

        LIRInstruction storeOfR8 = blockWithStoreToR8.get(1);

        LIRInstruction loadThisIntoRdi = blockBytecodeWithALoadOfThis.get(1);
        if (!"rdi|QWORD[.] = MOVE stack:40|QWORD[.] moveKind: QWORD".equals(loadThisIntoRdi.toString())) {
            return;
        }

        ArrayList<LIRInstruction> blockWithRangeTableSwitch = ir.getLIRforBlock(blocks[2]);
        if (blockWithRangeTableSwitch.size() < 6) {
            return;
        }

        LIRInstruction inst = blockWithRangeTableSwitch.get(5);
        if (!inst.name().equals("RangeTableSwitch")) {
            return;
        }

        int blocksFound = 0;
        for (int i = 1; i < blocks.length; i += 1) {
            AbstractBlockBase<?> block = blocks[i];
            if (block == null) {
                continue;
            }

            switch (block.getId()) {
                case 4:
                case 5:
                case 11:
                case 14:
                case 21:
                case 25:
                case 27:

// case 8:
// case 15:
                {
                    ArrayList<LIRInstruction> b = ir.getLIRforBlock(block);
                    LIRInstruction last = b.get(b.size() - 1);
                    if (!last.name().equals("Jump")) {
                        break;
                    }

                    LIRInstruction loadBytecodeIntoRbp = blockWithRangeTableSwitch.get(3);
                    LIRInstruction incrementBytecodeIndex = blockWithRangeTableSwitch.get(4);

                    loadBytecodeIntoRbp = lirGen.getArithmetic().withIndexFromResult(loadBytecodeIntoRbp, incrementBytecodeIndex);

                    // rdi = this
                    b.set(b.size() - 1, loadThisIntoRdi);

                    // rax = this.bytecodeArray
                    b.add(blockBytecodeArrayPtrAndUncompress.get(3));
                    // rax = uncompress(rax)
                    b.add(blockBytecodeArrayPtrAndUncompress.get(4));

                    // load r8 from stack
                    LIRInstruction loadBytecodeIdxIntoR8 = lirGen.getArithmetic().makeMoveFromStackToReg(storeOfR8);
                    b.add(loadBytecodeIdxIntoR8);

                    // rbp = rax[r8] <- reading the bytecode
                    b.add(loadBytecodeIntoRbp);

                    // inc r8
                    b.add(incrementBytecodeIndex);

                    // range table switch
                    b.add(blockWithRangeTableSwitch.get(5));

                    blocksFound += 1;

                    if (blocksFound >= 7) {
                        break;
                    }
                }
                default:
            }

        }

        // TODO: can I find the RangeTableSwitchOp in it.litInstructions[2]? and copy it to the
        // correct places?
    }
}
