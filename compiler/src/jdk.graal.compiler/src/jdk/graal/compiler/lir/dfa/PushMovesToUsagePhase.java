package jdk.graal.compiler.lir.dfa;

import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.core.common.util.IntList;
import jdk.graal.compiler.hotspot.aarch64.AArch64HotSpotDeoptimizeOp;
import jdk.graal.compiler.hotspot.aarch64.AArch64HotSpotSafepointOp;
import jdk.graal.compiler.hotspot.aarch64.AArch64HotSpotUnwindOp;
import jdk.graal.compiler.hotspot.amd64.AMD64DeoptimizeOp;
import jdk.graal.compiler.hotspot.amd64.AMD64HotSpotSafepointOp;
import jdk.graal.compiler.hotspot.amd64.AMD64HotSpotUnwindOp;
import jdk.graal.compiler.lir.ConstantValue;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.LIRInstruction.OperandFlag;
import jdk.graal.compiler.lir.LIRInstruction.OperandMode;
import jdk.graal.compiler.lir.StandardOp;
import jdk.graal.compiler.lir.StandardOp.BytecodeLoopReturnOp;
import jdk.graal.compiler.lir.StandardOp.BytecodeLoopSlowPathOp;
import jdk.graal.compiler.lir.StandardOp.LabelOp;
import jdk.graal.compiler.lir.StandardOp.ValueMoveOp;
import jdk.graal.compiler.lir.aarch64.AArch64ControlFlow;
import jdk.graal.compiler.lir.amd64.AMD64ControlFlow;
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
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.asStackSlot;
import static jdk.vm.ci.code.ValueUtil.isIllegal;
import static jdk.vm.ci.code.ValueUtil.isRegister;
import static jdk.vm.ci.code.ValueUtil.isStackSlot;

public final class PushMovesToUsagePhase extends FinalCodeAnalysisPhase {
    public static final class InstRef {
        public final int blockId;
        public final int instIdx;
        public final LIRInstruction instruction;

        public InstRef(int blockId, int instId, LIRInstruction instruction) {
            this.blockId = blockId;
            this.instIdx = instId;
            this.instruction = instruction;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof InstRef other) {
                return blockId == other.blockId && instIdx == other.instIdx;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return blockId * 31 + instIdx;
        }

        @Override
        public String toString() {
            return "InstRef{" + blockId + ":" + instIdx + "}";
        }
    }

    public static class BasicBlockBytecodeDetails {
        public final BasicBlock<?> block;

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

        /** The instructions that wrote to a register. */
        public Map<Register, List<InstRef>> writtenToRegisters;

        /**
         * Registers that are read, without having been written to previously,
         * and the instructions reading them.
         */
        public Map<Register, List<InstRef>> readFromRegisters;

        public Map<Register, List<InstRef>> allRegisterReads;

        /** Stack locations that are written to, incl. instructions. */
        public Map<StackSlot, List<InstRef>> writtenToStack;

        /** Stack locations that are read from, without having been written to first. */
        public Map<StackSlot, List<InstRef>> readFromStack;

        public Map<StackSlot, List<InstRef>> allStackReads;


        public List<InstRef>[] instUsage;

        /** These blocks have already been origin of jumps to the current block. */
        public IntList registerUseOriginBlockIds;

        public BasicBlockBytecodeDetails(BasicBlock<?> block) {
            this.block = block;
        }

        public BasicBlockBytecodeDetails(BasicBlockBytecodeDetails details, BasicBlock<?> block) {
            this.block = block;
            this.fullyProcessed = details.fullyProcessed;
            this.leadsToHeadOfLoop = details.leadsToHeadOfLoop;
            this.leadsToSlowPath = details.leadsToSlowPath;
            this.leadsToReturn = details.leadsToReturn;
            this.canLeadToHeadOfLoop = details.canLeadToHeadOfLoop;
            this.canLeadToSlowPath = details.canLeadToSlowPath;
            this.canLeadToReturn = details.canLeadToReturn;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public void initUsage(int numberOfInstructions) {
            if (writtenToRegisters != null) {
                throw new IllegalStateException("Usage structures already initialized");
            }

            writtenToRegisters = new HashMap<>();
            readFromRegisters = new HashMap<>();
            allRegisterReads = new HashMap<>();
            writtenToStack = new HashMap<>();
            readFromStack = new HashMap<>();
            allStackReads = new HashMap<>();
            instUsage = new List[numberOfInstructions];
        }

        public void initUsageIfNeeded(int numberOfInstructions) {
            if (writtenToRegisters == null) {
                initUsage(numberOfInstructions);
            }
        }

        private static String registerMapToString(Map<Register, ?> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }

            boolean first = true;
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (Register r : map.keySet()) {
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

        private static String stackMapToString(Map<StackSlot, List<InstRef>> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }

            boolean first = true;
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (StackSlot s : map.keySet()) {
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

            String desc = registerMapToString(writtenToRegisters);
            if (desc != null) {
                start += ",wr" + desc;
            }
            desc = registerMapToString(readFromRegisters);
            if (desc != null) {
                start += ",rr" + desc;
            }
            desc = stackMapToString(writtenToStack);
            if (desc != null) {
                start += ",wm" + desc;
            }
            desc = stackMapToString(readFromStack);
            if (desc != null) {
                start += ",rm" + desc;
            }

            return  start + '}';
        }
    }

    private static class PhaseState {
        public BasicBlock<?> dispatchBlock;
        public BasicBlock<?> lastDispatchBlock;

        public final List<BasicBlock<?>> bytecodeHandlers = new ArrayList<>();
    }

    private static boolean isDispatchBlock(ArrayList<LIRInstruction> instructions) {
        if (instructions.size() < 2) {
            return false;
        }

        LIRInstruction last = instructions.getLast();
        return last instanceof AArch64ControlFlow.RangeTableSwitchOp || last instanceof AMD64ControlFlow.RangeTableSwitchOp;
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


    private static BasicBlockBytecodeDetails establishControlFlowProperties(PhaseState state, LIR lir, BasicBlock<?> block) {
        ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

        StandardOp.LabelOp label = (StandardOp.LabelOp) instructions.getFirst();
        if (label.hackPushMovesToUsagePhaseData != null) {
            return (BasicBlockBytecodeDetails) label.hackPushMovesToUsagePhaseData;
        }

        var details = new BasicBlockBytecodeDetails(block);
        details.fullyProcessed = false;
        label.hackPushMovesToUsagePhaseData = details;

        if (block == state.dispatchBlock) {
            details.fullyProcessed = true;
            details.leadsToHeadOfLoop = true;
            details.canLeadToHeadOfLoop = true;
            return details;
        }

        LIRInstruction last = instructions.getLast();
        switch (last) {
            case StandardOp.BranchOp b -> {
                BasicBlock<?> trueTarget = b.getTrueDestination().getTargetBlock();
                var tDetails = establishControlFlowProperties(state, lir, trueTarget);

                BasicBlock<?> falseTarget = b.getFalseDestination().getTargetBlock();
                var fDetails = establishControlFlowProperties(state, lir, falseTarget);

                details.fullyProcessed = true;
                details.leadsToHeadOfLoop = tDetails.leadsToHeadOfLoop && fDetails.leadsToHeadOfLoop;
                details.leadsToSlowPath = tDetails.leadsToSlowPath && fDetails.leadsToSlowPath;
                details.leadsToReturn = tDetails.leadsToReturn && fDetails.leadsToReturn;
                details.canLeadToHeadOfLoop = tDetails.canLeadToHeadOfLoop || fDetails.canLeadToHeadOfLoop;
                details.canLeadToSlowPath = tDetails.canLeadToSlowPath || fDetails.canLeadToSlowPath;
                details.canLeadToReturn = tDetails.canLeadToReturn || fDetails.canLeadToReturn;

                return details;
            }
            case StandardOp.JumpOp b -> {
                BasicBlock<?> target = b.destination().getTargetBlock();
                var jDetails = establishControlFlowProperties(state, lir, target);
                details.fullyProcessed = jDetails.fullyProcessed;
                details.leadsToHeadOfLoop = jDetails.leadsToHeadOfLoop;
                details.leadsToSlowPath = jDetails.leadsToSlowPath;
                details.leadsToReturn = jDetails.leadsToReturn;
                details.canLeadToHeadOfLoop = jDetails.canLeadToHeadOfLoop;
                details.canLeadToSlowPath = jDetails.canLeadToSlowPath;
                details.canLeadToReturn = jDetails.canLeadToReturn;

                return details;
            }
            case BytecodeLoopSlowPathOp b -> {
                return slowPath(label, block);
            }
            case BytecodeLoopReturnOp b -> {
                details.fullyProcessed = true;
                details.leadsToReturn = true;
                details.canLeadToReturn = true;
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

                if (block.getId() <= state.lastDispatchBlock.getId()) {
                    // we reached a block that is before the last dispatch block, or indeed is the last dispatch block
                    // since we reached it from the start of a bytecode handler,
                    // I'll assume this is the start of the dispatch blocks
                    state.dispatchBlock = block;

                    var details = new BasicBlockBytecodeDetails(block);
                    details.fullyProcessed = true;
                    details.leadsToHeadOfLoop = true;
                    details.canLeadToHeadOfLoop = true;
                    setDetails(lir.getLIRforBlock(state.dispatchBlock), details);

                    // and I think that's all that's needed
                    return;
                }

                ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);
                LIRInstruction last = instructions.getLast();

                switch (last) {
                    case StandardOp.BranchOp b -> {
                        workList.add(b.getTrueDestination().getTargetBlock());
                        workList.add(b.getFalseDestination().getTargetBlock());
                    }
                    case StandardOp.JumpOp b -> {
                        workList.add(b.destination().getTargetBlock());
                    }
                    case AArch64HotSpotDeoptimizeOp b -> { /* noop, doesn't to the top of the dispatch loop */ }
                    case AMD64DeoptimizeOp b -> { /* noop */ }

                    case AArch64HotSpotUnwindOp b -> { /* noop, doesn't to the top of the dispatch loop */ }
                    case AMD64HotSpotUnwindOp b -> { /* noop */ }

                    case BytecodeLoopReturnOp b -> { /* noop, doesn't to the top of the dispatch loop */ }

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

    private static BasicBlockBytecodeDetails getDetailsAndInitializeIfNecessary(BasicBlock<?> block, List<LIRInstruction> instructions) {
        BasicBlockBytecodeDetails details = getDetails(instructions);
        if (details == null) {
            details = new BasicBlockBytecodeDetails(block);
            setDetails(instructions, details);
        }

        details.initUsageIfNeeded(instructions.size());
        return details;
    }

    private static void setDetails(List<LIRInstruction> instructions, BasicBlockBytecodeDetails details) {
        ((LabelOp) instructions.getFirst()).hackPushMovesToUsagePhaseData = details;
    }

    private static <T> void addToList(Map<T, List<InstRef>> map, T key, InstRef instRef) {
        if (!map.containsKey(key)) {
            map.put(key, new ArrayList<>());
        }
        map.get(key).add(instRef);
    }

    private static void recordInput(BasicBlockBytecodeDetails details, Register inputReg, InstRef instRef) {
        if (!details.writtenToRegisters.containsKey(inputReg)) {
            addToList(details.readFromRegisters, inputReg, instRef);
        }

        addToList(details.allRegisterReads, inputReg, instRef);
    }

    private static void recordInput(BasicBlockBytecodeDetails details, Value input, InstRef instRef) {
        if (isRegister(input)) {
            Register inputReg = asRegister(input);
            if (!details.writtenToRegisters.containsKey(inputReg)) {
                addToList(details.readFromRegisters, inputReg, instRef);
            }
            addToList(details.allRegisterReads, inputReg, instRef);
        } else if (isStackSlot(input)) {
            StackSlot inputSlot = asStackSlot(input);
            if (!details.writtenToStack.containsKey(inputSlot)) {
                addToList(details.readFromStack, inputSlot, instRef);
            }

            addToList(details.allStackReads, inputSlot, instRef);
        } else if (isIllegal(input) || input instanceof ConstantValue) {
            // ignore
        } else {
            throw new AssertionError("Not yet handled input: " + input);
        }
    }

    private static void recordResult(BasicBlockBytecodeDetails details, Value result, InstRef instRef) {
        if (isRegister(result)) {
            if (usesRegisterAsReference(result)) {
                // this isn't a write to the register, but a read from it
                // and then a write to a memory address, though, it's not a stack slot
                recordInput(details, result, instRef);
            } else {
                addToList(details.writtenToRegisters, asRegister(result), instRef);
            }
        } else if (isStackSlot(result)) {
            addToList(details.writtenToStack, asStackSlot(result), instRef);
        } else if (isIllegal(result) || result instanceof ConstantValue) {
            // I did get IllegalValue for a direct call to SubstrateArraycopySnippets.doArraycopy
            // it's a bit odd, but fine, I'll ignore it for now
        } else {
            throw new AssertionError("Not yet handled result: " + result);
        }
    }

    private static void recordAllInputsAndOutputs(BasicBlockBytecodeDetails details, LIRInstruction ins, InstRef instRef) {
        ins.forEachInput((Value value, OperandMode mode, EnumSet<OperandFlag> flags) -> {
            recordInput(details, value, instRef);
            return value;
        });
        ins.forEachOutput((Value value, OperandMode mode, EnumSet<OperandFlag> flags) -> {
            recordResult(details, value, instRef);
            return value;
        });

        // TODO: I am not sure this is correct, but in a sense, if we need it for the debug
        // state, then we may as well just record it as a result
        ins.forEachState((Value value, OperandMode mode, EnumSet<OperandFlag> flags) -> {
            recordResult(details, value, instRef);
            return value;
        });
    }

    private static void establishInputs(PhaseState state, LIR lir, BasicBlock<?> startBlock) {
        List<LIRInstruction> currentIs = lir.getLIRforBlock(startBlock);
        assert currentIs != null : "No LIR for block: " + startBlock;

        BasicBlock<?> currentBlock = startBlock;
        var details = getDetailsAndInitializeIfNecessary(startBlock, currentIs);

        // go over the instructions until we reached state.lastDispatchBlock,
        // or the first dispatch block (in the case we processed a bytecode handler)
        // and process all instructions to collect the inputs
        while (currentIs != null) {
            assert currentBlock != null;
            final int numInstsInCurrentBlock = currentIs.size();

            for (int i = 1 /* ignore the label*/; i < numInstsInCurrentBlock; i += 1) {
                assert currentBlock != null;

                LIRInstruction ins = currentIs.get(i);
                switch (ins) {
                    case StandardOp.BranchOp b -> {
                        BasicBlock<?> trueTarget = b.getTrueDestination().getTargetBlock();
                        var trueInstructions = lir.getLIRforBlock(trueTarget);

                        if (trueTarget == state.lastDispatchBlock) {
                            currentIs = trueInstructions;
                            currentBlock = trueTarget;
                        } else if (getDetails(trueInstructions).canLeadToHeadOfLoop) {
                            currentIs = trueInstructions;
                            currentBlock = trueTarget;
                        } else {
                            BasicBlock<?> falseTarget = b.getFalseDestination().getTargetBlock();
                            var falseInstructions = lir.getLIRforBlock(falseTarget);

                            if (falseTarget == state.lastDispatchBlock) {
                                currentIs = falseInstructions;
                                currentBlock = falseTarget;
                            } else if (getDetails(falseInstructions).canLeadToHeadOfLoop) {
                                currentIs = falseInstructions;
                                currentBlock = falseTarget;
                            } else {
                                // none of the options lead back to the head of loop it seems
                                // ok, fine, let's see whether we can get to a return
                                if (getDetails(trueInstructions).leadsToReturn || getDetails(falseInstructions).leadsToReturn) {
                                    // good, we're returning, for the moment, we do not care about the performance of this path
                                    // and just stop here
                                    currentIs = null;
                                    currentBlock = null;
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
                        currentBlock = target;
                    }
                    case AArch64ControlFlow.RangeTableSwitchOp r -> {
                        // we reached the end of the dispatch blocks
                        currentIs = null;
                        currentBlock = null;
                    }
                    case AMD64ControlFlow.RangeTableSwitchOp r -> {
                        // we reached the end of the dispatch blocks
                        currentIs = null;
                        currentBlock = null;
                    }
                    case AArch64HotSpotSafepointOp s -> {
                        InstRef instRef = new InstRef(currentBlock.getId(), i, s);
                        recordResult(details, s.getScratchValue(), instRef);
                        recordInput(details, s.getThreadRegister(), instRef);
                    }
                    case AMD64HotSpotSafepointOp s -> {
                        InstRef instRef = new InstRef(currentBlock.getId(), i, s);
                        recordResult(details, s.getScratchValue(), instRef);
                        recordInput(details, s.getThreadRegister(), instRef);
                    }
                    case LIRInstruction instruction -> {
                        InstRef instRef = new InstRef(currentBlock.getId(), i, instruction);
                        recordAllInputsAndOutputs(details, instruction, instRef);
                    }
                    // default -> throw new AssertionError("Not yet supported instruction: " + ins);
                }
            }
        }
    }

    private static BasicBlockBytecodeDetails slowPath(StandardOp.LabelOp label, BasicBlock<?> block) {
        BasicBlockBytecodeDetails details = new BasicBlockBytecodeDetails(block);
        details.fullyProcessed = true;
        details.leadsToSlowPath = true;
        details.canLeadToSlowPath = true;
        label.hackPushMovesToUsagePhaseData = details;
        return details;
    }

    private static void recordUse(InstRef lastWrite, LIR lir, InstRef lastUse) {
        if (lastWrite != null) {
            List<LIRInstruction> instructions = lir.getLIRforBlock(lir.getBlockById(lastWrite.blockId));
            BasicBlockBytecodeDetails details = getDetails(instructions);
            if (details.instUsage[lastWrite.instIdx] == null) {
                details.instUsage[lastWrite.instIdx] = new ArrayList<>();
            }
            details.instUsage[lastWrite.instIdx].add(lastUse);
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
     * @param block
     * @param lir
     * @param dispatchInputs
     */
    private static void determineRegisterUsage(PhaseState state, BasicBlock<?> block, LIR lir, Map<Register, List<InstRef>> dispatchInputs, HashMap<Register, InstRef> liveSet, int originBlockId) {
        var instructions = lir.getLIRforBlock(block);
        final var details = getDetailsAndInitializeIfNecessary(block, instructions);

        // A basic block from different paths,
        // and for each of these we would want to update the liveSet, but only once.
        // so, here we check for that.
        if (details.registerUseOriginBlockIds == null) {
            details.registerUseOriginBlockIds = new IntList(2);
        } else if (details.registerUseOriginBlockIds.contains(originBlockId)) {
            return;
        }
        details.registerUseOriginBlockIds.add(originBlockId);

        assert details.fullyProcessed == true : "Block must be fully processed, but wasn't";
//        assert details.canLeadToHeadOfLoop || details.canLeadToReturn : "Block is expected either lead to dispatch or return";

        // we go over each instruction and:
        // - if the instruction uses a register, we append the current i to the usage list
        //   of the instruction that wrote to the register
        // - if the instruction writes to a register, we update the live set
        for (int i = 1; i < instructions.size(); i += 1) {
            LIRInstruction ins = instructions.get(i);
            InstRef inst = new InstRef(block.getId(), i, ins);

            ins.forEachInput((Value value, OperandMode mode, EnumSet<OperandFlag> flags) -> {
                if (isRegister(value)) {
                    Register reg = asRegister(value);
                    recordUse(liveSet.get(reg), lir, inst);
                }
                return value;
            });

            ins.forEachOutput((Value value, OperandMode mode, EnumSet<OperandFlag> flags) -> {
                if (isRegister(value)) {
                    Register reg = asRegister(value);
                    if (usesRegisterAsReference(value)) {
                        recordUse(liveSet.get(reg), lir, inst);
                    } else {
                        liveSet.put(reg, inst);
                    }
                }
                return value;
            });

            // TODO: I am not sure this is correct, but in a sense, if we need it for the debug
            // state, then we may as well just record it as a result
            ins.forEachState((Value value, OperandMode mode, EnumSet<OperandFlag> flags) -> {
                if (isRegister(value)) {
                    Register reg = asRegister(value);
                    if (usesRegisterAsReference(value)) {
                        recordUse(liveSet.get(reg), lir, inst);
                    } else {
                        liveSet.put(reg, inst);
                    }
                }
                return value;
            });
        }

        // process the last instruction
        LIRInstruction last = instructions.getLast();
        switch (last) {
            case StandardOp.BranchOp b -> {
                BasicBlock<?> trueTarget = b.getTrueDestination().getTargetBlock();
                BasicBlock<?> falseTarget = b.getFalseDestination().getTargetBlock();

                // got two branches to follow.
                // since I record instruction usage on the relevant basic block,
                // I really only need to copy the live set here, and actually only once
                // this way, I split the branches, and will end up with acurate usage info
                var liveSet2 = new HashMap<>(liveSet);
                determineRegisterUsage(state, trueTarget, lir, dispatchInputs, liveSet, block.getId());
                determineRegisterUsage(state, falseTarget, lir, dispatchInputs, liveSet2, block.getId());
            }
            case StandardOp.JumpOp b -> {
                BasicBlock<?> target = b.destination().getTargetBlock();
                if (target.getId() == state.dispatchBlock.getId()) {
                    // mark all registers that are used in the dispatch blocks as used by instruction -1
                    for (Register reg : dispatchInputs.keySet()) {
                        if (liveSet.get(reg) != null && liveSet.get(reg).blockId == block.getId()) {
                            int instIdx = liveSet.get(reg).instIdx;
                            if (details.instUsage[instIdx] == null) {
                                details.instUsage[instIdx] = new ArrayList<>();
                            }
                            details.instUsage[instIdx].add(new InstRef(-1, -1, null));
                        }
                    }
                    return;
                }

                determineRegisterUsage(state, target, lir, dispatchInputs, liveSet, block.getId());
            }
            case BytecodeLoopSlowPathOp b -> { return; }
            case BytecodeLoopReturnOp b -> { return; }

            default -> {
                throw new AssertionError("Unexpected last instruction in block: " + last);
            }
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
                assert state.dispatchBlock == null : "Only one dispatch block expected. But found a second one in blockId: " + blockId;
                // SM: I assume there are multiple dispatch blocks.
                // This assumption might not be correct, but it's good enough for now
                // if we need to support a single block at some point (which is the ideal case for performance)
                // then we need to also set state.dispatchBlock and its id.
                state.lastDispatchBlock = block;
                continue;
            }

            if (state.lastDispatchBlock == null) {
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
            establishControlFlowProperties(state, lir, block);
        }
    }

    private static void establishInputs(PhaseState state, LIR lir) {
        establishInputs(state, lir, state.dispatchBlock);

        for (BasicBlock<?> bytecodeHandlerBlock : state.bytecodeHandlers) {
            establishInputs(state, lir, bytecodeHandlerBlock);
        }
    }

    private static void determineRegisterUsage(PhaseState state, LIR lir) {
        var dispatchInputs = getDetails(lir.getLIRforBlock(state.dispatchBlock)).readFromRegisters;

        for (BasicBlock<?> bytecodeHandlerBlock : state.bytecodeHandlers) {
            // live set from register to where it was written
            HashMap<Register, InstRef> liveSet = new HashMap<>();
            determineRegisterUsage(state, bytecodeHandlerBlock, lir, dispatchInputs, liveSet, -1);
        }
    }

    private static void findTooEagerRegisterSpills(PhaseState state, LIR lir) {
        List<InstRef> unnecessarySpillOperations = new ArrayList<>();
        for (BasicBlock<?> bytecodeHandlerStart : state.bytecodeHandlers) {
            findTooEagerRegisterSpills(state, bytecodeHandlerStart, lir, unnecessarySpillOperations);
        }

        int i = 0;
        if (!unnecessarySpillOperations.isEmpty()) {
            System.out.println("Removing unnecessary Spill Operations: ");
            for (InstRef instRef : unnecessarySpillOperations) {
                i += 1;
                deleteInstruction(state, lir, instRef);
            }
        }
    }

    /** We assume that for all dispatch blocks, the block id is smaller or equal to the block id of the last dispatch block. */
    private static boolean isNotInDispatchBlock(PhaseState state, InstRef instRef) {
        return instRef.blockId > state.lastDispatchBlock.getId();
    }

    /** Single instruction accessing the stack slot, but not from the dispatch blocks. */
    private static List<Entry<StackSlot, List<InstRef>>> doneOnce(PhaseState state, Set<Entry<StackSlot, List<InstRef>>> set) {
        List<Entry<StackSlot, List<InstRef>>> result = new ArrayList<>();
        for (Entry<StackSlot, List<InstRef>> entry : set) {
            List<InstRef> instructions = entry.getValue();
            if (instructions.size() == 1 && isNotInDispatchBlock(state, instructions.get(0))) {
                result.add(entry);
            }
        }
        return result;
    }


    private static LIRInstruction getInstruction(LIR lir, InstRef instRef) {
        return lir.getLIRforBlock(lir.getBlockById(instRef.blockId)).get(instRef.instIdx);
    }

    private static void deleteInstruction(PhaseState state, LIR lir, InstRef instRef) {
        int blockId = instRef.blockId;
        var instructions = lir.getLIRforBlock(lir.getBlockById(blockId));
        if (instructions.get(instRef.instIdx) != instRef.instruction) {
            throw new AssertionError("Instruction mismatch at " + blockId + ":" + instRef.instIdx + " " + instRef.instruction + " was: " + instructions.get(instRef.instIdx));
        }

        System.out.println("  - remove instruction: " + blockId + ":" + instRef.instIdx);
        instructions.set(instRef.instIdx, null);
    }

    private static void findTooEagerRegisterSpills(PhaseState state, BasicBlock<?> start, LIR lir, List<InstRef> unnecessarySpillOperations) {
        var details = getDetails(lir.getLIRforBlock(start));
        assert details != null : "Details not found for block: " + start;

        // find all the stack locations that written to once, in the bytecode handler
        // these might be register spills
        List<Entry<StackSlot, List<InstRef>>> candidates = doneOnce(state, details.writtenToStack.entrySet());

        List<Entry<StackSlot, List<InstRef>>> remainingCandidates = new ArrayList<>();

        for (var slotAccesses : candidates) {
            // find the candidates that are then read back again
            if (details.allStackReads.containsKey(slotAccesses.getKey())) {
                remainingCandidates.add(slotAccesses);
            }
        }

        if (remainingCandidates.isEmpty()) {
            return;
        }

        // make sure the register is not used else where
        for (var slotAccess : remainingCandidates) {
            InstRef instRef = slotAccess.getValue().getFirst();
            LIRInstruction inst = getInstruction(lir, instRef);

            if (inst instanceof ValueMoveOp move) {
                var input = move.getInput();
                var output = move.getResult();
                if (isRegister(input) && isStackSlot(output)) {
                    Register reg = asRegister(input);
                    StackSlot slot = asStackSlot(output);

                    // make sure the reads are either in the dispatch blocks, or the read we started with
                    var allReads = details.allRegisterReads.get(reg);
                    boolean instsAreInDispatchBlockOrCandidate = true;
                    for (InstRef read : allReads) {
                        if (isNotInDispatchBlock(state, read) && !read.equals(instRef)) {
                            instsAreInDispatchBlockOrCandidate = false;
                            break;
                        }
                    }

                    if (!instsAreInDispatchBlockOrCandidate) {
                        break;
                    }

                    // make sure the write is the one write we found
                    var allWrites = details.writtenToRegisters.get(reg);

                    if (allWrites == null || allWrites.size() > 1) {
                        break;
                    }

                    LIRInstruction writeInst = getInstruction(lir, allWrites.getFirst());
                    if (writeInst instanceof ValueMoveOp move2) {
                        var i2 = move2.getInput();
                        var o2 = move2.getResult();

                        if (isStackSlot(i2) && isRegister(o2) && asStackSlot(i2).equals(slot) && asRegister(o2).equals(reg)) {
                            unnecessarySpillOperations.add(instRef);
                            unnecessarySpillOperations.add(allWrites.getFirst());
                        }
                    }
                }
            }
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
        LIRGenerationResult result = context.lirGen.getResult();
        String name = result.getCompilationUnitName();
        if (!name.contains("BytecodeLoopNode.executeGeneric") && !name.contains("smallBytecodeLoop")) {
            return;
        }

        var state = new PhaseState();
        LIR lir = result.getLIR();

        // 1. find the dispatch block and bytecode handlers
        findDispatchBlockAndBytecodeHandlers(state, lir);

        if (state.bytecodeHandlers.isEmpty()) {
            // no bytecode handlers found
            // this could the Deopt_Target_Method version of our method
            // let's just ignore this for now
            System.out.println("No bytecode handlers found for " + name);
            return;
        }

        // 2. discover full set of dispatch blocks
        discoverAllDispatchBlocks(state, lir);

        // 3. discover control flow
        discoverControlFlowOfBytecodeHandlers(state, lir);

        // 4. establish inputs
        establishInputs(state, lir);

        // 5. determine register usage
        determineRegisterUsage(state, lir);

        // 6. find bytecode handlers that spill register, but only use it on slow path
        findTooEagerRegisterSpills(state, lir);

        removeUnusedMoves(state, lir);
    }

    private static void removeUnusedMoves(PhaseState state, LIR lir) {
        List<InstRef> candidates = new ArrayList<>();

        // first count
        for (int blockId : lir.codeEmittingOrder()) {
            if (LIR.isBlockDeleted(blockId)) {
                continue;
            }

            BasicBlock<?> block = lir.getBlockById(blockId);
            var details = getDetails(lir.getLIRforBlock(block));

            if (details == null || details.instUsage == null) {
                continue;
            }

            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

            // we skip the label and the jump/return/deopt/deadend at the end
            // we go backwards to not mess up the indexes for earlier instructions when removing things
            for (int i = instructions.size() - 1; i > 0; i -= 1) {
                if (instructions.get(i) instanceof StandardOp.MoveOp move && details.instUsage[i] == null && isRegister(move.getResult())) {
                    // I don't really understand what these references are yet.
                    // so, let's ignore them
                    // and just deal with plain moves
                    if (!usesRegisterAsReference(move.getResult())) {
                        candidates.add(new InstRef(blockId, i, instructions.get(i)));
                    }
                }
            }
        }

        System.out.println("Removing unused moves, candidates: " + candidates.size());
        for (InstRef instRef : candidates) {
            deleteInstruction(state, lir, instRef);
        }
    }

    public static boolean usesRegisterAsReference(Value value) {
        var kind = value.getValueKind(LIRKind.class);
        boolean isReference = kind.getReferenceCount() > 0;
        assert isReference ^ kind.isValue();
        return isReference;
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
