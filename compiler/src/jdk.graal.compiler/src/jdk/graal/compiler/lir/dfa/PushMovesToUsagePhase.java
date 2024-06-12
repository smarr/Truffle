package jdk.graal.compiler.lir.dfa;

import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.hotspot.aarch64.AArch64HotSpotDeoptimizeOp;
import jdk.graal.compiler.hotspot.aarch64.AArch64HotSpotReturnOp;
import jdk.graal.compiler.hotspot.aarch64.AArch64HotSpotUnwindOp;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.StandardOp;
import jdk.graal.compiler.lir.aarch64.AArch64ControlFlow;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.phases.FinalCodeAnalysisPhase;
import jdk.vm.ci.code.TargetDescription;

import java.util.ArrayList;
import java.util.List;

public final class PushMovesToUsagePhase extends FinalCodeAnalysisPhase {
    public static class BasicBlockBytecodeDetails {
        public boolean fullyProcessed;

        /** This block leads unconditional to the head of the bytecode loop, i.e., it's a back edge. */
        public boolean leadsToHeadOfLoop;

        /** This block leads unconditional to the slow path. */
        public boolean leadsToSlowPath;

        /** This block leads unconditional to a return. */
        public boolean leadsToReturn;

        /** This block is on a possible path to the head of the bytecode loop. */
        public boolean canLeadToHeadOfLoop;

        /** This block is on a possible path to the slow path. */
        public boolean canLeadToSlowPath;

        /** This block is on a possible path to a return. */
        public boolean canLeadToReturn;

        @Override
        public String toString() {
            return "BasicBlockBytecodeDetails{" +
                    (fullyProcessed ? "t" : "f") +
                    "," + (leadsToHeadOfLoop ? "t" : "f") +
                    "," + (leadsToSlowPath ? "t" : "f") +
                    "," + (leadsToReturn ? "t" : "f") +
                    "," + (canLeadToHeadOfLoop ? "t" : "f") +
                    "," + (canLeadToSlowPath ? "t" : "f") +
                    "," + (canLeadToReturn ? "t" : "f") +
                    '}';
        }
    }

    private static class PhaseState {
        public int dispatchBlockId = -1;
        public List<LIRInstruction> dispatchBlock;

        public int lastDispatchBlockId = -1;
        public List<LIRInstruction> lastDispatchBlock;

        public final List<ArrayList<LIRInstruction>> bytecodeHandlers = new ArrayList<>();
    }

    private static boolean isDispatchBlock(ArrayList<LIRInstruction> instructions) {
        if (instructions.size() < 2) {
            return false;
        }

        LIRInstruction last = instructions.get(instructions.size() - 1);
        return last instanceof AArch64ControlFlow.RangeTableSwitchOp;
    }

    private static boolean isBytecodeHandler(ArrayList<LIRInstruction> instructions) {
        if (instructions.size() < 2) {
            return false;
        }

        LIRInstruction first = instructions.get(0);
        return first instanceof StandardOp.LabelOp label && label.getBytecodeHandlerIndex() != -1;
    }


    private static BasicBlockBytecodeDetails establishControlFlowProperties(
            PhaseState state, LIR lir, BasicBlock<?> block, ArrayList<LIRInstruction> instructions, int prevBlockId) {
        if (instructions == state.dispatchBlock) {
            var details = new BasicBlockBytecodeDetails();
            details.fullyProcessed = true;
            details.leadsToHeadOfLoop = true;
            details.canLeadToHeadOfLoop = true;

            if (prevBlockId < state.dispatchBlockId && prevBlockId != -1) {
                // We found a block that is before the dispatch block,
                // and we will have reached `block` from a bytecode handler,
                // so, we can assume that `block` is part of the bytecode dispatch.
                // This means, we can replace state.dispatchBlock with `block` to get more precision.
                BasicBlock<?> prev = lir.getBlockById(prevBlockId);
                state.dispatchBlockId = prevBlockId;
                state.dispatchBlock = lir.getLIRforBlock(prev);
            }

            return details;
        }

        StandardOp.LabelOp label = (StandardOp.LabelOp) instructions.get(0);
        if (label.hackPushMovesToUsagePhaseData != null) {
            return (BasicBlockBytecodeDetails) label.hackPushMovesToUsagePhaseData;
        }

        LIRInstruction last = instructions.get(instructions.size() - 1);
        switch (last) {
            case AArch64ControlFlow.AbstractBranchOp b -> {
                BasicBlock<?> trueTarget = b.getTrueDestination().getTargetBlock();

                var tDetails = establishControlFlowProperties(state, lir, trueTarget, lir.getLIRforBlock(trueTarget), block.getId());

                BasicBlock<?> falseTarget = b.getFalseDestination().getTargetBlock();
                var fDetails = establishControlFlowProperties(state, lir, falseTarget, lir.getLIRforBlock(falseTarget), block.getId());

                var details = new BasicBlockBytecodeDetails();

                details.fullyProcessed = true;
                details.leadsToHeadOfLoop = tDetails.leadsToHeadOfLoop && fDetails.leadsToHeadOfLoop;
                details.leadsToSlowPath = tDetails.leadsToSlowPath && fDetails.leadsToSlowPath;
                details.leadsToReturn = tDetails.leadsToReturn && fDetails.leadsToReturn;
                details.canLeadToHeadOfLoop = tDetails.canLeadToHeadOfLoop || fDetails.canLeadToHeadOfLoop;
                details.canLeadToSlowPath = tDetails.canLeadToSlowPath || fDetails.canLeadToSlowPath;
                details.canLeadToReturn = tDetails.canLeadToReturn || fDetails.canLeadToReturn;

                label.hackPushMovesToUsagePhaseData = details;
                return details;
            }
            case StandardOp.JumpOp b -> {
                BasicBlock<?> target = b.destination().getTargetBlock();
                BasicBlockBytecodeDetails details = establishControlFlowProperties(state, lir, target, lir.getLIRforBlock(target), block.getId());
                label.hackPushMovesToUsagePhaseData = details;
                return details;
            }
            case AArch64HotSpotDeoptimizeOp b -> {
                return slowPath(label);
            }
            case AArch64HotSpotUnwindOp b -> {
                return slowPath(label);
            }
            case AArch64HotSpotReturnOp b -> {
                BasicBlockBytecodeDetails details = new BasicBlockBytecodeDetails();
                details.fullyProcessed = true;
                details.leadsToReturn = true;
                details.canLeadToReturn = true;
                label.hackPushMovesToUsagePhaseData = details;
                return details;
            }
            default -> {
                throw new AssertionError("Unexpected last instruction in block: " + last);
            }
        }
    }

    private static BasicBlockBytecodeDetails slowPath(StandardOp.LabelOp label) {
        BasicBlockBytecodeDetails details = new BasicBlockBytecodeDetails();
        details.fullyProcessed = true;
        details.leadsToSlowPath = true;
        details.canLeadToSlowPath = true;
        label.hackPushMovesToUsagePhaseData = details;
        return details;
    }

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, FinalCodeAnalysisContext context) {
        var state = new PhaseState();

        LIRGenerationResult result = context.lirGen.getResult();
        String name = result.getCompilationUnitName();
        if (!name.contains("BytecodeLoopNode.executeGeneric") && !name.contains("smallBytecodeLoop")) {
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

            if (isDispatchBlock(instructions)) {
                assert state.dispatchBlockId == -1 : "Only one dispatch block expected. But found a second one in blockId: " + blockId;
                state.dispatchBlockId = blockId;
                state.dispatchBlock = instructions;
                state.lastDispatchBlockId = blockId;
                state.lastDispatchBlock = instructions;
                continue;
            }

            if (state.dispatchBlockId == -1) {
                // we expect the dispatch block before any bytecode handlers
                continue;
            }

            // only handle bytecode handlers
            if (!isBytecodeHandler(instructions)) {
                continue;
            }

            // now, we know we have a bytecode handler
            // 1. remember the bytecode handler
            state.bytecodeHandlers.add(instructions);

            // 2. establish control flow properties
            establishControlFlowProperties(state, lir, block, instructions, -1);

            // now we have the start of a bytecode handler
            // the next step would be to try to push down a move instruction

//            // Hard code this for now
//            switch (label.getBytecodeHandlerIndex()) {
//                case 1: { // TruffleSOM's DUP bytecode
//                    if (// check the first instruction to be a spill
//                            instructions.get(1) instanceof AArch64Move.Move m1 &&
//                                    m1.getResult() instanceof StackSlot r1 &&
//                                    m1.getInput() instanceof RegisterValue i1 && i1.getRegister().name.equals("r5")
//                                    &&
//                                    // check the last instruction to be a branch
//                                    instructions.get(instructions.size() - 1) instanceof AArch64ControlFlow.BitTestAndBranchOp b1
//                    ) {
//                        // looks like with spill
//                        LIRInstruction spill1 = instructions.get(1);
//                        ArrayList<LIRInstruction> trueInstructions = lir.getLIRforBlock(b1.getTrueDestination().getTargetBlock());
//
//                        if (trueInstructions.get(1) instanceof AArch64Move.Move m2 &&
//                                m2.getResult() instanceof RegisterValue r2 &&
//                                m2.getInput() instanceof StackSlot i2
//                        ) {
//                            // remove the spill
//                            instructions.remove(1);
//                            trueInstructions.remove(1);
//
//                            // postpone it to false branch
//                            ArrayList<LIRInstruction> falseInstructions = lir.getLIRforBlock(b1.getFalseDestination().getTargetBlock());
//                            falseInstructions.add(1, spill1);
//                        }
//                    }
//                    break;
//                }
//                case 20: { // TruffleSOM's PUSH_1 bytecode
//                    // same dance as above, just without the safe guards...
//                    LIRInstruction spill1 = instructions.get(1);
//                    AArch64ControlFlow.BitTestAndBranchOp b1 = (AArch64ControlFlow.BitTestAndBranchOp) instructions.get(instructions.size() - 1);
//                    ArrayList<LIRInstruction> trueInstructions = lir.getLIRforBlock(b1.getTrueDestination().getTargetBlock());
//
//                    // remove the spill
//                    instructions.remove(1);
//                    trueInstructions.remove(1);
//
//                    // postpone it to false branch
//                    ArrayList<LIRInstruction> falseInstructions = lir.getLIRforBlock(b1.getFalseDestination().getTargetBlock());
//                    falseInstructions.add(1, spill1);
//                }
//            }


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
