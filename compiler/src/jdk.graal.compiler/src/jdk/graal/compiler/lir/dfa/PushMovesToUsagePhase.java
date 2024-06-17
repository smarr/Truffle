package jdk.graal.compiler.lir.dfa;

import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.hotspot.aarch64.AArch64HotSpotDeoptimizeOp;
import jdk.graal.compiler.hotspot.aarch64.AArch64HotSpotMove;
import jdk.graal.compiler.hotspot.aarch64.AArch64HotSpotReturnOp;
import jdk.graal.compiler.hotspot.aarch64.AArch64HotSpotSafepointOp;
import jdk.graal.compiler.hotspot.aarch64.AArch64HotSpotUnwindOp;
import jdk.graal.compiler.lir.ConstantValue;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.LIRInstruction.OperandFlag;
import jdk.graal.compiler.lir.LIRInstruction.OperandMode;
import jdk.graal.compiler.lir.StandardOp;
import jdk.graal.compiler.lir.StandardOp.LabelOp;
import jdk.graal.compiler.lir.aarch64.AArch64ArithmeticOp;
import jdk.graal.compiler.lir.aarch64.AArch64Compare;
import jdk.graal.compiler.lir.aarch64.AArch64ControlFlow;
import jdk.graal.compiler.lir.aarch64.AArch64Move;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.phases.FinalCodeAnalysisPhase;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.Value;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.asStackSlot;
import static jdk.vm.ci.code.ValueUtil.isIllegal;
import static jdk.vm.ci.code.ValueUtil.isRegister;
import static jdk.vm.ci.code.ValueUtil.isStackSlot;

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

        /** Written to registers. */
        public Set<Register> writtenToRegisters;

        /** Registers that are read, without having been written to previously. */
        public Set<Register> readFromRegisters;

        /** Memory locations that are written to. */
        public Set<StackSlot> writtenToMemory;

        /** Memory locations that are read from, without having been written to first. */
        public Set<StackSlot> readFromMemory;

        public List<Integer>[] instUsage;

        @SuppressWarnings({"unchecked", "rawtypes"})
        public void initUsage(int numberOfInstructions) {
            if (writtenToRegisters != null) {
                throw new IllegalStateException("Usage structures already initialized");
            }

            writtenToRegisters = new HashSet<>();
            readFromRegisters = new HashSet<>();
            writtenToMemory = new HashSet<>();
            readFromMemory = new HashSet<>();
            instUsage = new List[numberOfInstructions];
        }

        private static String registerSetToString(Set<Register> set) {
            if (set == null || set.isEmpty()) {
                return null;
            }

            boolean first = true;
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (Register r : set) {
                if (first) {
                    first = false;
                } else {
                    sb.append(",");
                }
                sb.append(r.name);
            }
            sb.append("]");
            return sb.toString();
        }

        private static String stackSetToString(Set<StackSlot> set) {
            if (set == null || set.isEmpty()) {
                return null;
            }

            boolean first = true;
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (StackSlot s : set) {
                if (first) {
                    first = false;
                } else {
                    sb.append(",");
                }
                sb.append(s.toString());
            }
            sb.append("]");
            return sb.toString();
        }

        @Override
        public String toString() {
            String start = "BasicBlockBytecodeDetails{" +
                    (fullyProcessed ? "t" : "f") +
                    "," + (leadsToHeadOfLoop ? "t" : "f") +
                    "," + (leadsToSlowPath ? "t" : "f") +
                    "," + (leadsToReturn ? "t" : "f") +
                    "," + (canLeadToHeadOfLoop ? "t" : "f") +
                    "," + (canLeadToSlowPath ? "t" : "f") +
                    "," + (canLeadToReturn ? "t" : "f");

            String desc = registerSetToString(writtenToRegisters);
            if (desc != null) {
                start += ",wr" + desc;
            }
            desc = registerSetToString(readFromRegisters);
            if (desc != null) {
                start += ",rr" + desc;
            }
            desc = stackSetToString(writtenToMemory);
            if (desc != null) {
                start += ",wm" + desc;
            }
            desc = stackSetToString(readFromMemory);
            if (desc != null) {
                start += ",rm" + desc;
            }

            return  start + '}';
        }
    }

    private static class PhaseState {
        public int dispatchBlockId = -1;
        public List<LIRInstruction> dispatchBlock;

        public int lastDispatchBlockId = -1;
        public List<LIRInstruction> lastDispatchBlock;

        public final List<BasicBlock<?>> bytecodeHandlers = new ArrayList<>();
    }

    private static boolean isDispatchBlock(ArrayList<LIRInstruction> instructions) {
        if (instructions.size() < 2) {
            return false;
        }

        LIRInstruction last = instructions.getLast();
        return last instanceof AArch64ControlFlow.RangeTableSwitchOp;
    }

    private static boolean isBytecodeHandler(List<LIRInstruction> instructions) {
        if (instructions.size() < 2) {
            return false;
        }

        LIRInstruction first = instructions.getFirst();
        return first instanceof StandardOp.LabelOp label && label.getBytecodeHandlerIndex() != -1;
    }

    private static int getBytecodeHandlerIndex(List<LIRInstruction> instructions) {
        if (!isBytecodeHandler(instructions)) {
            throw new AssertionError("Not a bytecode handler: " + instructions);
        }
        StandardOp.LabelOp label = (StandardOp.LabelOp) instructions.getFirst();
        return label.getBytecodeHandlerIndex();
    }


    private static BasicBlockBytecodeDetails establishControlFlowProperties(
            PhaseState state, LIR lir, BasicBlock<?> block, ArrayList<LIRInstruction> instructions) {
        if (instructions == state.dispatchBlock) {
            var details = new BasicBlockBytecodeDetails();
            details.fullyProcessed = true;
            details.leadsToHeadOfLoop = true;
            details.canLeadToHeadOfLoop = true;
            return details;
        }

        StandardOp.LabelOp label = (StandardOp.LabelOp) instructions.getFirst();
        if (label.hackPushMovesToUsagePhaseData != null) {
            return (BasicBlockBytecodeDetails) label.hackPushMovesToUsagePhaseData;
        }

        LIRInstruction last = instructions.getLast();
        switch (last) {
            case AArch64ControlFlow.AbstractBranchOp b -> {
                BasicBlock<?> trueTarget = b.getTrueDestination().getTargetBlock();

                var tDetails = establishControlFlowProperties(state, lir, trueTarget, lir.getLIRforBlock(trueTarget));

                BasicBlock<?> falseTarget = b.getFalseDestination().getTargetBlock();
                var fDetails = establishControlFlowProperties(state, lir, falseTarget, lir.getLIRforBlock(falseTarget));

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
                BasicBlockBytecodeDetails details = establishControlFlowProperties(state, lir, target, lir.getLIRforBlock(target));
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

    private static void discoverAllDispatchBlocks(PhaseState state, LIR lir) {
        assert state.dispatchBlock == null : "Dispatch block already found";
        assert !state.bytecodeHandlers.isEmpty() : "No bytecode handlers found";

        for (BasicBlock<?> handlerStart : state.bytecodeHandlers) {
            ArrayList<BasicBlock<?>> workList = new ArrayList<>();

            workList.add(handlerStart);

            while (!workList.isEmpty()) {
                var block = workList.removeLast();

                if (block.getId() < state.lastDispatchBlockId) {
                    // we reached a block that is before the last dispatch block
                    // since we reached it from the start of a bytecode handler,
                    // I'll assume this is the start of the dispatch blocks
                    state.dispatchBlockId = block.getId();
                    state.dispatchBlock = lir.getLIRforBlock(block);

                    var details = new BasicBlockBytecodeDetails();
                    details.fullyProcessed = true;
                    details.leadsToHeadOfLoop = true;
                    details.canLeadToHeadOfLoop = true;
                    setDetails(state.dispatchBlock, details);

                    // and I think that's all that's needed
                    return;
                }

                ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);
                LIRInstruction last = instructions.getLast();

                switch (last) {
                    case AArch64ControlFlow.AbstractBranchOp b -> {
                        workList.add(b.getTrueDestination().getTargetBlock());
                        workList.add(b.getFalseDestination().getTargetBlock());
                    }
                    case StandardOp.JumpOp b -> {
                        workList.add(b.destination().getTargetBlock());
                    }
                    case AArch64HotSpotDeoptimizeOp b -> {
                        // noop, doesn't to the top of the dispatch loop
                    }
                    case AArch64HotSpotUnwindOp b -> {
                        // noop, doesn't to the top of the dispatch loop
                    }
                    case AArch64HotSpotReturnOp b -> {
                        // noop, doesn't to the top of the dispatch loop
                    }
                    default -> {
                        throw new AssertionError("Unexpected last instruction in block: " + last);
                    }
                }
            }
        }
    }

    public static BasicBlockBytecodeDetails getDetails(List<LIRInstruction> instructions) {
        return (BasicBlockBytecodeDetails) ((LabelOp) instructions.getFirst()).hackPushMovesToUsagePhaseData;
    }

    private static BasicBlockBytecodeDetails getDetailsAndInitializeIfNecessary(List<LIRInstruction> instructions) {
        BasicBlockBytecodeDetails details = getDetails(instructions);
        if (details == null) {
            details = new BasicBlockBytecodeDetails();
            details.initUsage(instructions.size());
            setDetails(instructions, details);
        }
        return details;
    }

    private static void setDetails(List<LIRInstruction> instructions, BasicBlockBytecodeDetails details) {
        ((LabelOp) instructions.getFirst()).hackPushMovesToUsagePhaseData = details;
    }

    private static void recordInput(BasicBlockBytecodeDetails details, Register inputReg) {
        if (!details.writtenToRegisters.contains(inputReg)) {
            details.readFromRegisters.add(inputReg);
        }
    }

    private static void recordInput(BasicBlockBytecodeDetails details, Value input) {
        if (isRegister(input)) {
            Register inputReg = asRegister(input);
            if (!details.writtenToRegisters.contains(inputReg)) {
                details.readFromRegisters.add(inputReg);
            }
        } else if (isStackSlot(input)) {
            StackSlot inputSlot = asStackSlot(input);
            if (!details.writtenToMemory.contains(inputSlot)) {
                details.readFromMemory.add(inputSlot);
            }
        } else if (isIllegal(input) || input instanceof ConstantValue) {
            // ignore
        } else {
            throw new AssertionError("Not yet handled input: " + input);
        }
    }

    private static void recordResult(BasicBlockBytecodeDetails details, Value result) {
        if (isRegister(result)) {
            details.writtenToRegisters.add(asRegister(result));
        } else if (isStackSlot(result)) {
            details.writtenToMemory.add(asStackSlot(result));
        } else {
            throw new AssertionError("Not yet handled result: " + result);
        }
    }

    private static void recordAllInputsAndOutputs(BasicBlockBytecodeDetails details, LIRInstruction ins) {
        ins.forEachInput((Value value, OperandMode mode, EnumSet<OperandFlag> flags) -> {
            recordInput(details, value);
            return value;
        });
        ins.forEachOutput((Value value, OperandMode mode, EnumSet<OperandFlag> flags) -> {
            recordResult(details, value);
            return value;
        });
    }

    private static void establishInputs(PhaseState state, LIR lir, List<LIRInstruction> startIns) {
        List<LIRInstruction> currentIs = startIns;
        var details = getDetails(currentIs);
        details.initUsage(currentIs.size());

        // go over the instructions until we reached state.lastDispatchBlock,
        // or the first dispatch block (in the case we processed a bytecode handler)
        // and process all instructions to collect the inputs
        while (currentIs != null) {
            for (LIRInstruction ins : currentIs) {
                switch (ins) {
                    case LabelOp label -> {
                        // ignore the label, it's just a marker
                    }
                    case AArch64Move.Move m -> {
                        recordInput(details, m.getInput());
                        recordResult(details, m.getResult());
                    }
                    case AArch64Compare.CompareOp compare -> {
                        compare.forEachInput((Value value, OperandMode mode, EnumSet<OperandFlag> flags) -> {
                            recordInput(details, value);
                            return value;
                        });
                    }
                    case AArch64ControlFlow.AbstractBranchOp b -> {
                        BasicBlock<?> trueTarget = b.getTrueDestination().getTargetBlock();
                        var trueInstructions = lir.getLIRforBlock(trueTarget);

                        if (trueInstructions == state.lastDispatchBlock) {
                            currentIs = trueInstructions;
                        } else if (getDetails(trueInstructions).canLeadToHeadOfLoop) {
                            currentIs = trueInstructions;
                        } else {
                            BasicBlock<?> falseTarget = b.getFalseDestination().getTargetBlock();
                            var falseInstructions = lir.getLIRforBlock(falseTarget);

                            if (falseInstructions == state.lastDispatchBlock) {
                                currentIs = falseInstructions;
                            } else if (getDetails(falseInstructions).canLeadToHeadOfLoop) {
                                currentIs = falseInstructions;
                            } else {
                                // none of the options lead back to the head of loop it seems
                                // ok, fine, let's see whether we can get to a return
                                if (getDetails(trueInstructions).leadsToReturn || getDetails(falseInstructions).leadsToReturn) {
                                    // good, we're returning, for the moment, we do not care about the performance of this path
                                    // and just stop here
                                    currentIs = null;
                                } else {
                                   throw new AssertionError("We don't know which branch to take..." + b);
                                }
                            }
                        }
                    }
                    case StandardOp.JumpOp jump -> {
                        BasicBlock<?> target = jump.destination().getTargetBlock();
                        var targetInstructions = lir.getLIRforBlock(target);
                        currentIs = targetInstructions;
                    }
                    case AArch64ArithmeticOp.BinaryConstOp bin -> {
                        recordAllInputsAndOutputs(details, bin);
                    }
                    case AArch64ArithmeticOp.BinaryOp bin -> {
                        recordAllInputsAndOutputs(details, bin);
                    }
                    case AArch64Move.LoadOp load -> recordAllInputsAndOutputs(details, load);
                    case AArch64Move.LoadAddressOp load -> recordAllInputsAndOutputs(details, load);
                    case AArch64Move.LoadInlineConstant load -> {
                        recordResult(details, load.getResult());
                    }
                    case AArch64Move.StoreOp store -> recordAllInputsAndOutputs(details, store);
                    case AArch64Move.NullCheckOp n -> recordAllInputsAndOutputs(details, n);
                    case AArch64ControlFlow.RangeTableSwitchOp r -> {
                        // we reached the end of the dispatch blocks
                        currentIs = null;
                    }
                    case AArch64HotSpotSafepointOp s -> {
                        recordResult(details, s.getScratchValue());
                        recordInput(details, s.getThreadRegister());
                    }
                    case AArch64HotSpotMove.CompressPointer p -> recordAllInputsAndOutputs(details, p);
                    default -> throw new AssertionError("Not yet supported instruction: " + ins);
                }
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

    private static void recordLastWrite(Integer lastWrite, BasicBlockBytecodeDetails details, int lastUse) {
        if (lastWrite != null) {
            if (details.instUsage[lastWrite] == null) {
                details.instUsage[lastWrite] = new ArrayList<>();
            }
            details.instUsage[lastWrite].add(lastUse);
        }
    }

    /**
     * To determine the register usage of a while bytecode handler,
     * I need to:
     * - start at the first block of the handler
     * - go through the blocks that lead back to the dispatch block
     * - go into the slow path up to a specific limit to determine whether I need to
     *   push moves in there, or whether I can guarantee that it isn't used anywhere
     *
     * How to make sure that I find "global" uses of a register?
     * @param blockById
     * @param lir
     * @param dispatchInputs
     */
    private static void determineRegisterUsage(BasicBlock<?> blockById, LIR lir, Set<Register> dispatchInputs) {
        var instructions = lir.getLIRforBlock(blockById);
        var details = getDetailsAndInitializeIfNecessary(instructions);
        assert getBytecodeHandlerIndex(instructions) != -1 : "Block must be a bytecode handler, but isn't";
        assert details.fullyProcessed == true : "Block must be fully processed, but wasn't";
        assert details.canLeadToHeadOfLoop || details.canLeadToReturn : "Block is expected either lead to dispatch or return";

        // live set from register to where it was written
        HashMap<Register, Integer> liveSet = new HashMap<>();

        // we go over each instruction and:
        // - if the instruction uses a register, we append the current i to the usage list
        //   of the instruction that wrote to the register
        // - if the instruction writes to a register, we update the live set
        for (int i = 0; i < instructions.size(); i += 1) {
            final int finalI = i;
            LIRInstruction ins = instructions.get(i);

            ins.forEachInput((Value value, OperandMode mode, EnumSet<OperandFlag> flags) -> {
                if (isRegister(value)) {
                    Register reg = asRegister(value);
                    recordLastWrite(liveSet.get(reg), details, finalI);
                }
                return value;
            });

            ins.forEachOutput((Value value, OperandMode mode, EnumSet<OperandFlag> flags) -> {
                if (isRegister(value)) {
                    Register reg = asRegister(value);
                    liveSet.put(reg, finalI);
                }
                return value;
            });
        }

        // mark all registers that are used in the dispatch blocks as used by instruction -1
        for (Register reg : dispatchInputs) {
            recordLastWrite(liveSet.get(reg), details, -1);
        }
    }




    private static void findDispatchBlockAndBytecodeHandlers(PhaseState state, LIR lir) {
        for (int blockId : lir.codeEmittingOrder()) {
            if (LIR.isBlockDeleted(blockId)) {
                continue;
            }

            BasicBlock<?> block = lir.getBlockById(blockId);
            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);
            assert instructions.get(0) instanceof StandardOp.LabelOp : "First instruction in block must be a label";

            if (isDispatchBlock(instructions)) {
                assert state.dispatchBlockId == -1 : "Only one dispatch block expected. But found a second one in blockId: " + blockId;
                // SM: I assume there are multiple dispatch blocks.
                // This assumption might not be correct, but it's good enough for now
                // if we need to support a single block at some point (which is the ideal case for performance)
                // then we need to also set state.dispatchBlock and its id.
                state.lastDispatchBlockId = blockId;
                state.lastDispatchBlock = instructions;
                continue;
            }

            if (state.lastDispatchBlockId == -1) {
                // we expect the dispatch block before any bytecode handlers
                continue;
            }

            // only handle bytecode handlers
            if (!isBytecodeHandler(instructions)) {
                continue;
            }
            state.bytecodeHandlers.add(block);
        }
    }

    private static void discoverControlFlowOfBytecodeHandlers(PhaseState state, LIR lir) {
        for (BasicBlock<?> block : state.bytecodeHandlers) {
            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);
            establishControlFlowProperties(state, lir, block, instructions);
        }
    }

    private static void establishInputs(PhaseState state, LIR lir) {
        establishInputs(state, lir, state.dispatchBlock);

        for (BasicBlock<?> bytecodeHandlerBlock : state.bytecodeHandlers) {
            establishInputs(state, lir, lir.getLIRforBlock(bytecodeHandlerBlock));
        }
    }

    private static void determineRegisterUsage(PhaseState state, LIR lir) {
        var dispatchInputs = getDetails(state.dispatchBlock).readFromRegisters;

        for (BasicBlock<?> bytecodeHandlerBlock : state.bytecodeHandlers) {
            determineRegisterUsage(bytecodeHandlerBlock, lir, dispatchInputs);
        }
    }

    /**
     * Under which conditions can we push a move instruction into the slow path?
     *  1. the value read from memory is not an input to the dispatch blocks
     *  2. the value read from memory is not an input to the remaining fast-path blocks of the bytecode handler
     *  3. the register is not used as input to other bytecode handlers, i.e., some kind of global value
     *
     *  We should therefore, do the following:
     *  1. determine the input registers for the dispatch blocks
     *  2. determine the input registers for each bytecode handler
     *
     *  Input registers are the registers that are dependent on/read from,
     *  without previously being written to in the relevant set of blocks
     *
     *  Input memory locations:
     *  It would also be good for the general understanding to have a list of memory locations
     *  that a set of blocks read from as input.
     *
     *  now we have the start of a bytecode handler
     *  the next step would be to try to push down a move instruction
     *
     * @param target
     * @param lirGenRes
     * @param context
     */
    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, FinalCodeAnalysisContext context) {
        var state = new PhaseState();

        LIRGenerationResult result = context.lirGen.getResult();
        String name = result.getCompilationUnitName();
        if (!name.contains("BytecodeLoopNode.executeGeneric") && !name.contains("smallBytecodeLoop")) {
            return;
        }

        LIR lir = result.getLIR();

        // 1. find the dispatch block and bytecode handlers
        findDispatchBlockAndBytecodeHandlers(state, lir);

        // 2. discover full set of dispatch blocks
        discoverAllDispatchBlocks(state, lir);

        // 3. discover control flow
        discoverControlFlowOfBytecodeHandlers(state, lir);

        // 4. establish inputs
        establishInputs(state, lir);

        // 5. determine register usage
        determineRegisterUsage(state, lir);
    }

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
//                    // same dance as above, just without the safeguards...
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
