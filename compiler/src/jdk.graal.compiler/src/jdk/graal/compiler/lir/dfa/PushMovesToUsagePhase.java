package jdk.graal.compiler.lir.dfa;

import jdk.graal.compiler.core.common.cfg.AbstractControlFlowGraph;
import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.StandardOp;
import jdk.graal.compiler.lir.aarch64.AArch64ControlFlow;
import jdk.graal.compiler.lir.aarch64.AArch64Move;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.phases.FinalCodeAnalysisPhase;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.TargetDescription;

import java.util.ArrayList;

public final class PushMovesToUsagePhase extends FinalCodeAnalysisPhase {
    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, FinalCodeAnalysisContext context) {
        LIRGenerationResult result = context.lirGen.getResult();
        String name = result.getCompilationUnitName();
        if (!name.contains("BytecodeLoopNode.executeGeneric")) {
            return;
        }

        LIR lir = result.getLIR();

        for (int blockId : lir.codeEmittingOrder()) {
            if (LIR.isBlockDeleted(blockId)) {
                continue;
            }
            BasicBlock<?> block = lir.getBlockById(blockId);
            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);
            assert instructions.get(0) instanceof StandardOp.LabelOp : "First instruction in block must be a label";
            StandardOp.LabelOp label = (StandardOp.LabelOp) instructions.get(0);

            if (label.getBytecodeHandlerIndex() == -1) {
                continue;
            }

            // now we have the start of a bytecode handler
            // the next step would be to try to push down a move instruction

            // Hard code this for now
            switch (label.getBytecodeHandlerIndex()) {
                case 1: { // TruffleSOM's DUP bytecode
                    if (// check the first instruction to be a spill
                            instructions.get(1) instanceof AArch64Move.Move m1 &&
                                    m1.getResult() instanceof StackSlot r1 &&
                                    m1.getInput() instanceof RegisterValue i1 && i1.getRegister().name.equals("r5")
                                    &&
                                    // check the last instruction to be a branch
                                    instructions.get(instructions.size() - 1) instanceof AArch64ControlFlow.BitTestAndBranchOp b1
                    ) {
                        // looks like with spill
                        LIRInstruction spill1 = instructions.get(1);
                        ArrayList<LIRInstruction> trueInstructions = lir.getLIRforBlock(b1.getTrueDestination().getTargetBlock());

                        if (trueInstructions.get(1) instanceof AArch64Move.Move m2 &&
                                m2.getResult() instanceof RegisterValue r2 &&
                                m2.getInput() instanceof StackSlot i2
                        ) {
                            // remove the spill
                            instructions.remove(1);
                            trueInstructions.remove(1);

                            // postpone it to false branch
                            ArrayList<LIRInstruction> falseInstructions = lir.getLIRforBlock(b1.getFalseDestination().getTargetBlock());
                            falseInstructions.add(1, spill1);
                        }
                    }
                    break;
                }
                case 20: { // TruffleSOM's PUSH_1 bytecode
                    // same dance as above, just without the safe guards...
                    LIRInstruction spill1 = instructions.get(1);
                    AArch64ControlFlow.BitTestAndBranchOp b1 = (AArch64ControlFlow.BitTestAndBranchOp) instructions.get(instructions.size() - 1);
                    ArrayList<LIRInstruction> trueInstructions = lir.getLIRforBlock(b1.getTrueDestination().getTargetBlock());

                    // remove the spill
                    instructions.remove(1);
                    trueInstructions.remove(1);

                    // postpone it to false branch
                    ArrayList<LIRInstruction> falseInstructions = lir.getLIRforBlock(b1.getFalseDestination().getTargetBlock());
                    falseInstructions.add(1, spill1);
                }
            }


//            for (LIRInstruction ins : lir.getLIRforBlock(block)) {
//                int i =0;
//                i += 1;
//            }
        }

//        ControlFlowGraph cfg = (ControlFlowGraph) result.getLIR().getControlFlowGraph();
//
//        AbstractBeginNode dupBytecodeStart = null;
//        for (AbstractBeginNode n : cfg.graph.getNodes(AbstractBeginNode.TYPE)) {
//            if (n.getBytecodeHandlerIndex() == 1) {
//                // the DUP bytecode should be a good start
//                dupBytecodeStart = n;
//                break;
//            }
//        }
//
//        if (dupBytecodeStart == null) {
//            return;
//        }
//
////        dupBytecodeStart.getId()
//
//        int[] blockIds = result.getLIR().linearScanOrder();
//        // cfg.getNodeToBlock().get(dupBytecodeStart);
//
//        // find the bytecode handler starting blocks
//
////        for (int blockId : layout) {
////            BasicBlock<?> block = lir.getBlockById(blockId);
////            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);
//
//        try {
//            for (int blockId : blockIds) {
//                BasicBlock<?> bb = result.getLIR().getBlockById(blockId);
//                bb.getId();
//            }
//        } catch (ArrayIndexOutOfBoundsException e) {
//            return;
//        }
    }
}
