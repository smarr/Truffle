package jdk.graal.compiler.core.aarch64.test;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.HostCompilerDirectives.BytecodeInterpreterSwitch;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.StandardOp;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.jtt.LIRTest;
import jdk.graal.compiler.lir.phases.FinalCodeAnalysisPhase;
import jdk.graal.compiler.lir.phases.LIRSuites;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.code.TargetDescription;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Predicate;

public class AArch64PushMovesTest extends LIRTest {
    private LIR lir;

    /**
     * Test snippet that should not trigger the PushMovesToUsagePhase phase
     */
    public static int trivialTestSnippetWithoutBytecodeLoop(int a, int b) {
        if (a > 0) {
            return a + b;
        } else {
            return a - b;
        }
    }

    @Test
    public void testTrivialSnippet() {
        test("trivialTestSnippetWithoutBytecodeLoop", 1, 2);
        checkLIR("trivialTestSnippetWithoutBytecodeLoop", (op) -> {
            // count how many basic blocks were marked as bytecode handlers
            if (op instanceof StandardOp.LabelOp label) {
                return label.getBytecodeHandlerIndex() != -1;
            }
            return false;
            // we'd expect 0 of them to be marked for the trivial snippet
        }, 0);
    }

    @Override
    protected LIRSuites createLIRSuites(OptionValues options) {
        LIRSuites suites = super.createLIRSuites(options);
        suites.getFinalCodeAnalysisStage().appendPhase(new AArch64PushMovesTest.CheckPhase());
        return suites;
    }

    public class CheckPhase extends FinalCodeAnalysisPhase {
        @Override
        protected void run(
                TargetDescription target, LIRGenerationResult lirGenRes, FinalCodeAnalysisPhase.FinalCodeAnalysisContext context) {
            lir = lirGenRes.getLIR();
        }
    }

    protected void checkLIR(String methodName, Predicate<LIRInstruction> predicate, int expected) {
        compile(getResolvedJavaMethod(methodName), null);
        int actualOpNum = 0;
        int[] codeEmitOrder = lir.codeEmittingOrder();
        for (int blockId : codeEmitOrder) {
            if (LIR.isBlockDeleted(blockId)) {
                continue;
            }
            for (LIRInstruction ins : lir.getLIRforBlock(lir.getBlockById(blockId))) {
                if (predicate.test(ins)) {
                    actualOpNum++;
                }
            }
        }
        Assert.assertEquals(expected, actualOpNum);
    }

    public static class MethodActivation {
        @CompilationFinal(dimensions = 1) private final byte[] bytecodes;
        private Object[] arguments;

        private final long const1;
        private final long const2;
        private final long const3;

        private int fieldIndex;
        private int fieldType;

        public MethodActivation(final long const1, final long const2, final long const3,
                               final byte[] bytecodes, final int fieldIndex) {
            this.fieldIndex = fieldIndex;
            this.const1 = const1;
            this.const2 = const2;
            this.const3 = const3;
            this.bytecodes = bytecodes;
        }
    }

    private static class State {
        long top;
    }

    private static class LangObject {
        long field;
    }

    @ExplodeLoop(kind = LoopExplosionKind.MERGE_EXPLODE)
    @BytecodeInterpreterSwitch
    public Object smallBytecodeLoop(final MethodActivation frame) {
        final Object[] args = frame.arguments;
        final Object rcvr = args[0];
        final State state = new State();

        // bytecodes
        // 0: assert field is long, unsupported otherwise
        // 1: read field as long to top
        // 2: top * const1
        // 3: top + const2
        // 4: top & const3
        // 5: write field
        // 6: return top

        final byte[] bytecodes = frame.bytecodes;

        boolean isIntegerField = false;

        int i = 0;

        for (;;) {
            byte b = bytecodes[i];

            switch (b) {
                case 0:
                    if (frame.fieldType == 10) {
                        isIntegerField = true;
                    } else {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new UnsupportedOperationException();
                    }
                    i += 1;
                    break;
                case 1:
                    try {
                        if (isIntegerField) {
                            if (frame.fieldType != 10) {
                                throw new UnexpectedResultException(rcvr);
                            }
                            state.top = ((LangObject) rcvr).field;
                        } else {
                            throw new UnexpectedResultException(rcvr);
                        }
                    } catch (UnexpectedResultException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new UnsupportedOperationException();
                    }
                    i += 1;
                    break;
                case 2:
                    try {
                        state.top = Math.multiplyExact(state.top, frame.const1);
                    } catch (ArithmeticException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new UnsupportedOperationException();
                    }
                    i += 1;
                    break;
                case 3:
                    try {
                        state.top = Math.addExact(state.top, frame.const2);
                    } catch (ArithmeticException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new UnsupportedOperationException();
                    }
                    i += 1;
                    break;
                case 4:
                    state.top = state.top & frame.const3;
                    i += 1;
                    break;
                case 5:
                    ((LangObject) rcvr).field = state.top;
                    i += 1;
                    break;
                case 6:
                    return state.top;
            }
        }
    }

    @Test
    public void testBytecodeLoopHandlersGetMarked() {
        MethodActivation frame = new MethodActivation(1309, 13849, 65535, new byte[] {0, 1, 2, 3, 4, 5, 6}, 1);
        test("smallBytecodeLoop", frame);
        checkLIR("smallBytecodeLoop", (op) -> {
            // count how many basic blocks were marked as bytecode handlers
            if (op instanceof StandardOp.LabelOp label) {
                return label.getBytecodeHandlerIndex() != -1;
            }
            return false;
            // we'd expect 7 bytecode handlers for the smallBytecodeLoop
        }, 7);
    }
//
//
//    public static final SObject nilObject;
//
//    static {
//        nilObject = new SObject();
//    }
//
//
//    // Bytecodes used by the simple object machine
//    public static final byte HALT = 0;
//    public static final byte DUP  = 1;
//
//    public static final byte PUSH_LOCAL   = 2;
//    public static final byte PUSH_LOCAL_0 = 3;
//    public static final byte PUSH_LOCAL_1 = 4;
//    public static final byte PUSH_LOCAL_2 = 5;
//
//    public static final byte PUSH_ARGUMENT = 6;
//    public static final byte PUSH_SELF     = 7;
//    public static final byte PUSH_ARG1     = 8;
//    public static final byte PUSH_ARG2     = 9;
//
//    public static final byte PUSH_FIELD   = 10;
//    public static final byte PUSH_FIELD_0 = 11;
//    public static final byte PUSH_FIELD_1 = 12;
//
//    public static final byte PUSH_BLOCK        = 13;
//    public static final byte PUSH_BLOCK_NO_CTX = 14;
//
//    public static final byte PUSH_CONSTANT   = 15;
//    public static final byte PUSH_CONSTANT_0 = 16;
//    public static final byte PUSH_CONSTANT_1 = 17;
//    public static final byte PUSH_CONSTANT_2 = 18;
//
//    public static final byte PUSH_0   = 19;
//    public static final byte PUSH_1   = 20;
//    public static final byte PUSH_NIL = 21;
//
//    public static final byte PUSH_GLOBAL = 22;
//
//    public static final byte POP = 23;
//
//    public static final byte POP_LOCAL   = 24;
//    public static final byte POP_LOCAL_0 = 25;
//    public static final byte POP_LOCAL_1 = 26;
//    public static final byte POP_LOCAL_2 = 27;
//
//    public static final byte POP_ARGUMENT = 28;
//
//    public static final byte POP_FIELD   = 29;
//    public static final byte POP_FIELD_0 = 30;
//    public static final byte POP_FIELD_1 = 31;
//
//    public static final byte SEND       = 32;
//    public static final byte SUPER_SEND = 33;
//
//    public static final byte RETURN_LOCAL     = 34;
//    public static final byte RETURN_NON_LOCAL = 35;
//    public static final byte RETURN_SELF      = 36;
//
//    public static final byte RETURN_FIELD_0 = 37;
//    public static final byte RETURN_FIELD_1 = 38;
//    public static final byte RETURN_FIELD_2 = 39;
//
//    public static final byte INC = 40;
//    public static final byte DEC = 41;
//
//    public static final byte INC_FIELD      = 42;
//    public static final byte INC_FIELD_PUSH = 43;
//
//    public static final byte JUMP                  = 44;
//    public static final byte JUMP_ON_TRUE_TOP_NIL  = 45;
//    public static final byte JUMP_ON_FALSE_TOP_NIL = 46;
//    public static final byte JUMP_ON_TRUE_POP      = 47;
//    public static final byte JUMP_ON_FALSE_POP     = 48;
//    public static final byte JUMP_BACKWARDS        = 49;
//
//    public static final byte JUMP2                  = 50;
//    public static final byte JUMP2_ON_TRUE_TOP_NIL  = 51;
//    public static final byte JUMP2_ON_FALSE_TOP_NIL = 52;
//    public static final byte JUMP2_ON_TRUE_POP      = 53;
//    public static final byte JUMP2_ON_FALSE_POP     = 54;
//    public static final byte JUMP2_BACKWARDS        = 55;
//
//    public static final byte Q_PUSH_GLOBAL = 56;
//    public static final byte Q_SEND        = 57;
//    public static final byte Q_SEND_1      = 58;
//    public static final byte Q_SEND_2      = 59;
//    public static final byte Q_SEND_3      = 60;
//
//    public static final byte LEN_NO_ARG     = 1;
//    public static final byte LEN_TWO_ARGS   = 2;
//    public static final byte LEN_THREE_ARGS = 3;
//
//    private static final ValueProfile frameType = ValueProfile.createClassProfile();
//
//    public static final class BackJump implements Comparable<BackJump> {
//        final int loopBeginIdx;
//        final int backwardsJumpIdx;
//
//        public BackJump(final int loopBeginIdx, final int backwardsJumpIdx) {
//            this.loopBeginIdx = loopBeginIdx;
//            this.backwardsJumpIdx = backwardsJumpIdx;
//        }
//
//        @Override
//        public int compareTo(final BackJump o) {
//            return this.loopBeginIdx - o.loopBeginIdx;
//        }
//
//        @Override
//        public String toString() {
//            return "Loop begin at: " + loopBeginIdx + " -> " + backwardsJumpIdx;
//        }
//    }
//
//    public static class SObject {}
//    public static class SSymbol extends SObject {
//        public int getNumberOfSignatureArguments() {
//            return 1;
//        }
//    }
//    public static class SClass extends SObject {}
//
//    public static class SomMethod {
//        @CompilationFinal(dimensions = 1) private final byte[]   bytecodesField;
//        @CompilationFinal(dimensions = 1) private final Object[] literalsAndConstantsField;
//
//        @CompilationFinal(dimensions = 1) private final BackJump[] inlinedLoopsField;
//
//        @Node.Children
//        private final Node[] quickenedField;
//
//        private final int numLocals;
//        private final int maxStackDepth;
//
//        private final int frameOnStackMarkerIndex;
//
//        private final int numberOfArguments;
//
//        public SomMethod(final byte[] bytecodes, final int numLocals,
//                                final Object[] literals, final int maxStackDepth,
//                                final int numberOfArguments,
//                                final int frameOnStackMarkerIndex, final BackJump[] inlinedLoops) {
//            this.bytecodesField = bytecodes;
//            this.numLocals = numLocals;
//            this.literalsAndConstantsField = literals;
//            this.maxStackDepth = maxStackDepth;
//            this.inlinedLoopsField = inlinedLoops;
//            this.numberOfArguments = numberOfArguments;
//
//            this.frameOnStackMarkerIndex = frameOnStackMarkerIndex;
//
//            this.quickenedField = new Node[bytecodes.length];
//        }
//
//        public final int getNumberOfArguments() {
//            return numberOfArguments;
//        }
//    }
//
//    public static final class SBlock {
//        private final MaterializedFrame context;
//
//        public SBlock(final MaterializedFrame context) {
//            this.context = context;
//        }
//
//        public boolean hasContext() {
//            return context != null;
//        }
//
//        public MaterializedFrame getContext() {
//            assert context != null;
//            return context;
//        }
//
//        public Object getOuterSelf() {
//            return getContext().getArguments()[0];
//        }
//    }
//
//
//    @ExplodeLoop
//    @HostCompilerDirectives.InliningCutoff
//    private static MaterializedFrame determineContext(final VirtualFrame frame,
//                                                      final int contextLevel) {
//        SBlock self = (SBlock) frame.getArguments()[0];
//        int i = contextLevel - 1;
//
//        while (i > 0) {
//            self = (SBlock) self.getOuterSelf();
//            i--;
//        }
//
//        // Graal needs help here to see that this is always a MaterializedFrame
//        // so, we record explicitly a class profile
//        return frameType.profile(self.getContext());
//    }
//
//    public abstract static class AbstractReadFieldNode extends Node {
//        public abstract long read(SObject rcvr);
//    }
//    public abstract static class AbstractWriteFieldNode extends Node {
//        public abstract void write(SObject rcvr, long value);
//    }
//    public abstract static class UnaryExpressionNode extends Node {
//        public abstract Object executeGeneric(VirtualFrame frame, Object rcvr);
//    }
//    public abstract static class BinaryExpressionNode extends Node {
//        public abstract Object executeGeneric(VirtualFrame frame, Object rcvr, Object arg);
//    }
//    public abstract static class TernaryExpressionNode extends Node {
//        public abstract Object executeGeneric(VirtualFrame frame, Object rcvr, Object arg1, Object arg2);
//    }
//    public abstract class GlobalNode extends Node {
//        public abstract Object executeGeneric(VirtualFrame frame);
//        public static GlobalNode create(final SSymbol globalName, final Object value) {
//            return null;
//        }
//    }
//
//    public class RestartLoopException extends ControlFlowException {
//        private static final long serialVersionUID = 6219800131081448550L;
//    }
//    public class EscapedBlockException extends ControlFlowException {
//        private static final long serialVersionUID = 1124756129738412293L;
//
//        private final transient SBlock block;
//
//        public EscapedBlockException(final SBlock block) {
//            this.block = block;
//        }
//
//        public SBlock getBlock() {
//            return block;
//        }
//    }
//
//    @HostCompilerDirectives.InliningCutoff
//    private AbstractReadFieldNode createRead(final int bytecodeIndex, final int fieldIndex, SomMethod method) {
//        CompilerDirectives.transferToInterpreterAndInvalidate();
//
//        AbstractReadFieldNode result = null; // FieldAccessorNode.createRead(fieldIndex);
//        method.quickenedField[bytecodeIndex] = result;
//        return result;
//    }
//
//    @HostCompilerDirectives.InliningCutoff
//    private AbstractWriteFieldNode createWrite(final int bytecodeIndex, final int fieldIndex, SomMethod method) {
//        CompilerDirectives.transferToInterpreterAndInvalidate();
//
//        AbstractWriteFieldNode result = null; // FieldAccessorNode.createWrite(fieldIndex);
//        method.quickenedField[bytecodeIndex] = result;
//        return result;
//    }
//
//    @HostCompilerDirectives.InliningCutoff
//    private void quickenBytecode(final int bytecodeIndex, final byte quickenedBytecode,
//                                 final Node quickenedNode, SomMethod method) {
//        method.quickenedField[bytecodeIndex] = quickenedNode;
//        method.bytecodesField[bytecodeIndex] = quickenedBytecode;
//    }
//
//    @HostCompilerDirectives.InliningCutoff
//    private static Object throwIllegaleState() {
//        throw new IllegalStateException("Not all required fields initialized in bytecode loop.");
//    }
//
//    @HostCompilerDirectives.InliningCutoff
//    private static Object handleEscapedBlock(final VirtualFrame frame,
//                                             final EscapedBlockException e) {
//        CompilerDirectives.transferToInterpreter();
//        VirtualFrame outer = determineOuterContext(frame);
//        SObject sendOfBlockValueMsg = (SObject) outer.getArguments()[0];
////        return SAbstractObject.sendEscapedBlock(sendOfBlockValueMsg, e.getBlock());
//        return e.getBlock();
//    }
//
//    @ExplodeLoop
//    private static VirtualFrame determineOuterContext(final VirtualFrame frame) {
//        // TODO: change bytecode format to include the context level
//        Object object = frame.getArguments()[0];
//
//        if (!(object instanceof SBlock)) {
//            return frame;
//        }
//
//        SBlock self = (SBlock) object;
//        MaterializedFrame outer = self.getContext();
//
//        while (true) {
//            Object rcvr = outer.getArguments()[0];
//
//            if (rcvr instanceof SBlock) {
//                outer = ((SBlock) rcvr).getContext();
//            } else {
//                return outer;
//            }
//        }
//    }
//
//    @HostCompilerDirectives.InliningCutoff
//    private Object quickenAndEvaluate(final VirtualFrame frame, final int bytecodeIndex,
//                                      final RespecializeException r, final Object rcvr) {
//        CompilerDirectives.transferToInterpreterAndInvalidate();
//        quickenBytecode(bytecodeIndex, Q_SEND, r.send);
//        return r.send.doPreEvaluated(frame, new Object[] {rcvr});
//    }
//
//
//    @HostCompilerDirectives.InliningCutoff
//    private Object quickenAndExecuteGlobal(final VirtualFrame frame, final int bytecodeIndex, SomMethod method) {
//        CompilerDirectives.transferToInterpreterAndInvalidate();
//
//        byte literalIdx = method.bytecodesField[bytecodeIndex + 1];
//        SSymbol globalName = (SSymbol) method.literalsAndConstantsField[literalIdx];
//
//        GlobalNode quick = GlobalNode.create(globalName, null);
//        quickenBytecode(bytecodeIndex, Q_PUSH_GLOBAL, quick, method);
//
//        return quick.executeGeneric(frame);
//    }
//
//    public static SClass getBlockClass(final int numberOfArguments) {
//        SClass result = blockClasses[numberOfArguments];
//        assert result != null || numberOfArguments == 0;
//        return result;
//    }
//
//    @ExplodeLoop(kind = LoopExplosionKind.MERGE_EXPLODE)
//    @BytecodeInterpreterSwitch
//    public Object largeBytecodeLoop(SomMethod method, VirtualFrame frame) {
//        Object[] stack = new Object[method.maxStackDepth];
//
//        final byte[] bytecodes = method.bytecodesField;
//        final Node[] quickened = method.quickenedField;
//        final Object[] literalsAndConstants = method.literalsAndConstantsField;
//        final Object[] arguments = frame.getArguments();
//
//        if (bytecodes == null || quickened == null || literalsAndConstants == null || frame == null
//                || arguments == null) {
//            return throwIllegaleState();
//        }
//
//        int stackPointer = -1;
//        int bytecodeIndex = 0;
//
//        int backBranchesTaken = 0;
//
//        while (true) {
//            byte bytecode = bytecodes[bytecodeIndex];
//
//            CompilerAsserts.partialEvaluationConstant(bytecodeIndex);
//            CompilerAsserts.partialEvaluationConstant(bytecode);
//            CompilerDirectives.ensureVirtualized(stack);
//
//            switch (bytecode) {
//                case HALT: {
//                    return stack[stackPointer];
//                }
//
//                case DUP: {
//                    Object top = stack[stackPointer];
//                    stackPointer += 1;
//                    stack[stackPointer] = top;
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case PUSH_LOCAL: {
//                    byte localIdx = bytecodes[bytecodeIndex + 1];
//                    byte contextIdx = bytecodes[bytecodeIndex + 2];
//
//                    VirtualFrame currentOrContext = frame;
//                    if (contextIdx > 0) {
//                        currentOrContext = determineContext(currentOrContext, contextIdx);
//                    }
//
//                    Object value = currentOrContext.getObject(localIdx);
//                    stackPointer += 1;
//                    stack[stackPointer] = value;
//                    bytecodeIndex += LEN_THREE_ARGS;
//                    break;
//                }
//
//                case PUSH_LOCAL_0: {
//                    stackPointer += 1;
//                    stack[stackPointer] = frame.getObject(0);
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//                case PUSH_LOCAL_1: {
//                    stackPointer += 1;
//                    stack[stackPointer] = frame.getObject(1);
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//                case PUSH_LOCAL_2: {
//                    stackPointer += 1;
//                    stack[stackPointer] = frame.getObject(2);
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case PUSH_ARGUMENT: {
//                    byte argIdx = bytecodes[bytecodeIndex + 1];
//                    byte contextIdx = bytecodes[bytecodeIndex + 2];
//                    assert contextIdx >= 0;
//
//                    VirtualFrame currentOrContext = frame;
//                    if (contextIdx > 0) {
//                        currentOrContext = determineContext(currentOrContext, contextIdx);
//                    }
//
//                    Object value = currentOrContext.getArguments()[argIdx];
//                    stackPointer += 1;
//                    stack[stackPointer] = value;
//                    bytecodeIndex += LEN_THREE_ARGS;
//                    break;
//                }
//
//                case PUSH_SELF: {
//                    stackPointer += 1;
//                    stack[stackPointer] = arguments[0];
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//                case PUSH_ARG1: {
//                    stackPointer += 1;
//                    stack[stackPointer] = arguments[1];
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//                case PUSH_ARG2: {
//                    stackPointer += 1;
//                    stack[stackPointer] = arguments[2];
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case PUSH_FIELD: {
//                    byte fieldIdx = bytecodes[bytecodeIndex + 1];
//                    byte contextIdx = bytecodes[bytecodeIndex + 2];
//
//                    VirtualFrame currentOrContext = frame;
//                    if (contextIdx > 0) {
//                        currentOrContext = determineContext(currentOrContext, contextIdx);
//                    }
//
//                    Node node = quickened[bytecodeIndex];
//                    if (node == null) {
//                        node = createRead(bytecodeIndex, fieldIdx, method);
//                    }
//
//                    stackPointer += 1;
//                    stack[stackPointer] = ((AbstractReadFieldNode) node).read(
//                            (SObject) currentOrContext.getArguments()[0]);
//                    bytecodeIndex += LEN_THREE_ARGS;
//                    break;
//                }
//
//                case PUSH_FIELD_0: {
//                    Node node = quickened[bytecodeIndex];
//                    if (node == null) {
//                        node = createRead(bytecodeIndex, 0, method);
//                    }
//
//                    stackPointer += 1;
//                    stack[stackPointer] =
//                            ((AbstractReadFieldNode) node).read((SObject) arguments[0]);
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case PUSH_FIELD_1: {
//                    Node node = quickened[bytecodeIndex];
//                    if (node == null) {
//                        node = createRead(bytecodeIndex, 1, method);
//                    }
//
//                    stackPointer += 1;
//                    stack[stackPointer] =
//                            ((AbstractReadFieldNode) node).read((SObject) arguments[0]);
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case PUSH_BLOCK: {
//                    SomMethod blockMethod = (SomMethod) literalsAndConstants[bytecodes[bytecodeIndex + 1]];
//
//                    stackPointer += 1;
//                    stack[stackPointer] = new SBlock(blockMethod,
//                            getBlockClass(blockMethod.getNumberOfArguments()), frame.materialize());
//                    bytecodeIndex += LEN_TWO_ARGS;
//                    break;
//                }
//
//                case PUSH_BLOCK_NO_CTX: {
//                    SomMethod blockMethod = (SomMethod) literalsAndConstants[bytecodes[bytecodeIndex + 1]];
//
//                    stackPointer += 1;
//                    stack[stackPointer] = new SBlock(blockMethod,
//                            getBlockClass(blockMethod.getNumberOfArguments()), null);
//                    bytecodeIndex += LEN_TWO_ARGS;
//                    break;
//                }
//
//                case PUSH_CONSTANT: {
//                    stackPointer += 1;
//                    stack[stackPointer] = literalsAndConstants[bytecodes[bytecodeIndex + 1]];
//                    bytecodeIndex += LEN_TWO_ARGS;
//                    break;
//                }
//
//                case PUSH_CONSTANT_0: {
//                    stackPointer += 1;
//                    stack[stackPointer] = literalsAndConstants[0];
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case PUSH_CONSTANT_1: {
//                    stackPointer += 1;
//                    stack[stackPointer] = literalsAndConstants[1];
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case PUSH_CONSTANT_2: {
//                    stackPointer += 1;
//                    stack[stackPointer] = literalsAndConstants[2];
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case PUSH_0: {
//                    stackPointer += 1;
//                    stack[stackPointer] = 0L;
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case PUSH_1: {
//                    stackPointer += 1;
//                    stack[stackPointer] = 1L;
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case PUSH_NIL: {
//                    stackPointer += 1;
//                    stack[stackPointer] = nilObject;
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case PUSH_GLOBAL: {
//                    stackPointer += 1;
//                    stack[stackPointer] = quickenAndExecuteGlobal(frame, bytecodeIndex, method);
//                    bytecodeIndex += LEN_TWO_ARGS;
//                    break;
//                }
//
//                case POP: {
//                    stackPointer -= 1;
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case POP_LOCAL: {
//                    byte localIdx = bytecodes[bytecodeIndex + 1];
//                    byte contextIdx = bytecodes[bytecodeIndex + 2];
//
//                    VirtualFrame currentOrContext = frame;
//                    if (contextIdx > 0) {
//                        currentOrContext = determineContext(currentOrContext, contextIdx);
//                    }
//
//                    Object value = stack[stackPointer];
//                    stackPointer -= 1;
//
//                    currentOrContext.setObject(localIdx, value);
//                    bytecodeIndex += LEN_THREE_ARGS;
//                    break;
//                }
//
//                case POP_LOCAL_0: {
//                    frame.setObject(0, stack[stackPointer]);
//                    stackPointer -= 1;
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//                case POP_LOCAL_1: {
//                    frame.setObject(1, stack[stackPointer]);
//                    stackPointer -= 1;
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//                case POP_LOCAL_2: {
//                    frame.setObject(2, stack[stackPointer]);
//                    stackPointer -= 1;
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case POP_ARGUMENT: {
//                    byte argIdx = bytecodes[bytecodeIndex + 1];
//                    byte contextIdx = bytecodes[bytecodeIndex + 2];
//
//                    VirtualFrame currentOrContext = frame;
//                    if (contextIdx > 0) {
//                        currentOrContext = determineContext(currentOrContext, contextIdx);
//                    }
//
//                    currentOrContext.getArguments()[argIdx] = stack[stackPointer];
//                    stackPointer -= 1;
//                    bytecodeIndex += LEN_THREE_ARGS;
//                    break;
//                }
//
//                case POP_FIELD: {
//                    byte fieldIdx = bytecodes[bytecodeIndex + 1];
//                    byte contextIdx = bytecodes[bytecodeIndex + 2];
//
//                    VirtualFrame currentOrContext = frame;
//                    if (contextIdx > 0) {
//                        currentOrContext = determineContext(currentOrContext, contextIdx);
//                    }
//
//                    Node node = quickened[bytecodeIndex];
//                    if (node == null) {
//                        node = createWrite(bytecodeIndex, fieldIdx, method);
//                    }
//
//                    ((AbstractWriteFieldNode) node).write((SObject) currentOrContext.getArguments()[0],
//                            stack[stackPointer]);
//                    stackPointer -= 1;
//                    bytecodeIndex += LEN_THREE_ARGS;
//                    break;
//                }
//
//                case POP_FIELD_0: {
//                    Node node = quickened[bytecodeIndex];
//                    if (node == null) {
//                        node = createWrite(bytecodeIndex, 0, method);
//                    }
//
//                    ((AbstractWriteFieldNode) node).write((SObject) arguments[0],
//                            stack[stackPointer]);
//
//                    stackPointer -= 1;
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//                case POP_FIELD_1: {
//                    Node node = quickened[bytecodeIndex];
//                    if (node == null) {
//                        node = createWrite(bytecodeIndex, 1);
//                    }
//
//                    ((AbstractWriteFieldNode) node).write((SObject) arguments[0],
//                            stack[stackPointer]);
//
//                    stackPointer -= 1;
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case SEND: {
//                    try {
//                        CompilerDirectives.transferToInterpreterAndInvalidate();
//                        byte literalIdx = bytecodes[bytecodeIndex + 1];
//                        SSymbol signature = (SSymbol) literalsAndConstants[literalIdx];
//                        int numberOfArguments = signature.getNumberOfSignatureArguments();
//
//                        Object[] callArgs = new Object[numberOfArguments];
//                        System.arraycopy(stack, stackPointer - numberOfArguments + 1, callArgs, 0,
//                                numberOfArguments);
//                        stackPointer -= numberOfArguments;
//
//                        Object result = specializeSendBytecode(frame, bytecodeIndex, signature,
//                                numberOfArguments, callArgs);
//
//                        stackPointer += 1;
//                        stack[stackPointer] = result;
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    } catch (RestartLoopException e) {
//                        bytecodeIndex = 0;
//                        stackPointer = -1;
//                    } catch (EscapedBlockException e) {
//                        CompilerDirectives.transferToInterpreter();
//                        stackPointer += 1;
//                        stack[stackPointer] = handleEscapedBlock(frame, e);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    }
//                    break;
//                }
//
//                case SUPER_SEND: {
//                    CompilerDirectives.transferToInterpreterAndInvalidate();
//                    try {
//                        byte literalIdx = bytecodes[bytecodeIndex + 1];
//                        SSymbol signature = (SSymbol) literalsAndConstants[literalIdx];
//                        int numberOfArguments = signature.getNumberOfSignatureArguments();
//
//                        Object[] callArgs = new Object[numberOfArguments];
//                        System.arraycopy(stack, stackPointer - numberOfArguments + 1, callArgs, 0,
//                                numberOfArguments);
//                        stackPointer -= numberOfArguments;
//
//                        PreevaluatedExpression quick = MessageSendNode.createSuperSend(
//                                (SClass) getHolder().getSuperClass(), signature, null);
//                        quickenBytecode(bytecodeIndex, Q_SEND, (Node) quick);
//
//                        Object result = quick.doPreEvaluated(frame, callArgs);
//
//                        stackPointer += 1;
//                        stack[stackPointer] = result;
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    } catch (RestartLoopException e) {
//                        bytecodeIndex = 0;
//                        stackPointer = -1;
//                    } catch (EscapedBlockException e) {
//                        CompilerDirectives.transferToInterpreter();
//                        stackPointer += 1;
//                        stack[stackPointer] = handleEscapedBlock(frame, e);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    }
//                    break;
//                }
//
//                case RETURN_LOCAL: {
//                    LoopNode.reportLoopCount(this, backBranchesTaken);
//                    return stack[stackPointer];
//                }
//
//                case RETURN_NON_LOCAL: {
//                    LoopNode.reportLoopCount(this, backBranchesTaken);
//
//                    Object result = stack[stackPointer];
//                    // stackPointer -= 1;
//                    doReturnNonLocal(frame, bytecodeIndex, result);
//                    return nilObject;
//                }
//
//                case RETURN_SELF: {
//                    LoopNode.reportLoopCount(this, backBranchesTaken);
//                    return arguments[0];
//                }
//
//                case RETURN_FIELD_0: {
//                    Node node = quickened[bytecodeIndex];
//                    if (node == null) {
//                        node = createRead(bytecodeIndex, 0);
//                    }
//
//                    return ((AbstractReadFieldNode) node).read((SObject) arguments[0]);
//                }
//                case RETURN_FIELD_1: {
//                    Node node = quickened[bytecodeIndex];
//                    if (node == null) {
//                        node = createRead(bytecodeIndex, 1);
//                    }
//
//                    return ((AbstractReadFieldNode) node).read((SObject) arguments[0]);
//                }
//                case RETURN_FIELD_2: {
//                    Node node = quickened[bytecodeIndex];
//                    if (node == null) {
//                        node = createRead(bytecodeIndex, 2);
//                    }
//
//                    return ((AbstractReadFieldNode) node).read((SObject) arguments[0]);
//                }
//
//                case INC: {
//                    Object top = stack[stackPointer];
//                    if (top instanceof Long) {
//                        try {
//                            stack[stackPointer] = Math.addExact((Long) top, 1L);
//                        } catch (ArithmeticException e) {
//                            CompilerDirectives.transferToInterpreterAndInvalidate();
//                            throw new NotYetImplementedException();
//                        }
//                    } else {
//                        CompilerDirectives.transferToInterpreterAndInvalidate();
//                        if (top instanceof Double) {
//                            stack[stackPointer] = ((Double) top) + 1.0d;
//                        } else {
//                            throw new NotYetImplementedException();
//                        }
//                    }
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case DEC: {
//                    Object top = stack[stackPointer];
//                    if (top instanceof Long) {
//                        stack[stackPointer] = ((Long) top) - 1;
//                    } else {
//                        CompilerDirectives.transferToInterpreterAndInvalidate();
//                        if (top instanceof Double) {
//                            stack[stackPointer] = ((Double) top) - 1.0d;
//                        } else {
//                            throw new NotYetImplementedException();
//                        }
//                    }
//                    bytecodeIndex += LEN_NO_ARG;
//                    break;
//                }
//
//                case INC_FIELD: {
//                    byte fieldIdx = bytecodes[bytecodeIndex + 1];
//                    byte contextIdx = bytecodes[bytecodeIndex + 2];
//
//                    VirtualFrame currentOrContext = frame;
//                    if (contextIdx > 0) {
//                        currentOrContext = determineContext(currentOrContext, contextIdx);
//                    }
//
//                    SObject obj = (SObject) currentOrContext.getArguments()[0];
//
//                    Node node = quickened[bytecodeIndex];
//                    if (node == null) {
//                        createAndDoIncrement(bytecodeIndex, fieldIdx, obj);
//                        bytecodeIndex += LEN_THREE_ARGS;
//                        break;
//                    }
//
//                    ((IncrementLongFieldNode) node).increment(obj);
//                    bytecodeIndex += LEN_THREE_ARGS;
//                    break;
//                }
//
//                case INC_FIELD_PUSH: {
//                    byte fieldIdx = bytecodes[bytecodeIndex + 1];
//                    byte contextIdx = bytecodes[bytecodeIndex + 2];
//
//                    VirtualFrame currentOrContext = frame;
//                    if (contextIdx > 0) {
//                        currentOrContext = determineContext(currentOrContext, contextIdx);
//                    }
//
//                    SObject obj = (SObject) currentOrContext.getArguments()[0];
//
//                    Node node = quickened[bytecodeIndex];
//                    if (node == null) {
//                        stackPointer += 1;
//                        stack[stackPointer] = createAndDoIncrement(bytecodeIndex, fieldIdx, obj);
//                        bytecodeIndex += LEN_THREE_ARGS;
//                        break;
//                    }
//
//                    long value = ((IncrementLongFieldNode) node).increment(obj);
//                    stackPointer += 1;
//                    stack[stackPointer] = value;
//                    bytecodeIndex += LEN_THREE_ARGS;
//                    break;
//                }
//
//                case JUMP: {
//                    int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1]);
//                    bytecodeIndex += offset;
//                    break;
//                }
//
//                case JUMP_ON_TRUE_TOP_NIL: {
//                    Object val = stack[stackPointer];
//                    if (val == Boolean.TRUE) {
//                        int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1]);
//                        bytecodeIndex += offset;
//                        stack[stackPointer] = nilObject;
//                    } else {
//                        stackPointer -= 1;
//                        bytecodeIndex += LEN_THREE_ARGS;
//                    }
//                    break;
//                }
//
//                case JUMP_ON_FALSE_TOP_NIL: {
//                    Object val = stack[stackPointer];
//                    if (val == Boolean.FALSE) {
//                        int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1]);
//                        bytecodeIndex += offset;
//                        stack[stackPointer] = nilObject;
//                    } else {
//                        stackPointer -= 1;
//                        bytecodeIndex += LEN_THREE_ARGS;
//                    }
//                    break;
//                }
//
//                case JUMP_ON_TRUE_POP: {
//                    Object val = stack[stackPointer];
//                    if (val == Boolean.TRUE) {
//                        int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1]);
//                        bytecodeIndex += offset;
//                    } else {
//                        bytecodeIndex += LEN_THREE_ARGS;
//                    }
//                    stackPointer -= 1;
//                    break;
//                }
//
//                case JUMP_ON_FALSE_POP: {
//                    Object val = stack[stackPointer];
//                    if (val == Boolean.FALSE) {
//                        int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1]);
//                        bytecodeIndex += offset;
//                    } else {
//                        bytecodeIndex += LEN_THREE_ARGS;
//                    }
//                    stackPointer -= 1;
//                    break;
//                }
//
//                case JUMP_BACKWARDS: {
//                    int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1]);
//                    bytecodeIndex -= offset;
//                    break;
//                }
//
//                case JUMP2: {
//                    int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1])
//                            + (Byte.toUnsignedInt(bytecodes[bytecodeIndex + 2]) << 8);
//                    bytecodeIndex += offset;
//
//                    if (CompilerDirectives.inInterpreter()) {
//                        backBranchesTaken += 1;
//                    }
//                    break;
//                }
//
//                case JUMP2_ON_TRUE_TOP_NIL: {
//                    Object val = stack[stackPointer];
//                    if (val == Boolean.TRUE) {
//                        int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1])
//                                + (Byte.toUnsignedInt(bytecodes[bytecodeIndex + 2]) << 8);
//                        bytecodeIndex += offset;
//                        stack[stackPointer] = nilObject;
//                    } else {
//                        stackPointer -= 1;
//                        bytecodeIndex += LEN_THREE_ARGS;
//                    }
//                    break;
//                }
//
//                case JUMP2_ON_FALSE_TOP_NIL: {
//                    Object val = stack[stackPointer];
//                    if (val == Boolean.FALSE) {
//                        int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1])
//                                + (Byte.toUnsignedInt(bytecodes[bytecodeIndex + 2]) << 8);
//                        bytecodeIndex += offset;
//                        stack[stackPointer] = nilObject;
//                    } else {
//                        stackPointer -= 1;
//                        bytecodeIndex += LEN_THREE_ARGS;
//                    }
//                    break;
//                }
//
//                case JUMP2_ON_TRUE_POP: {
//                    Object val = stack[stackPointer];
//                    if (val == Boolean.TRUE) {
//                        int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1])
//                                + (Byte.toUnsignedInt(bytecodes[bytecodeIndex + 2]) << 8);
//                        bytecodeIndex += offset;
//                    } else {
//                        bytecodeIndex += LEN_THREE_ARGS;
//                    }
//                    stackPointer -= 1;
//                    break;
//                }
//
//                case JUMP2_ON_FALSE_POP: {
//                    Object val = stack[stackPointer];
//                    if (val == Boolean.FALSE) {
//                        int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1])
//                                + (Byte.toUnsignedInt(bytecodes[bytecodeIndex + 2]) << 8);
//                        bytecodeIndex += offset;
//                    } else {
//                        bytecodeIndex += LEN_THREE_ARGS;
//                    }
//                    stackPointer -= 1;
//                    break;
//                }
//
//                case JUMP2_BACKWARDS: {
//                    int offset = Byte.toUnsignedInt(bytecodes[bytecodeIndex + 1])
//                            + (Byte.toUnsignedInt(bytecodes[bytecodeIndex + 2]) << 8);
//                    bytecodeIndex -= offset;
//
//                    if (CompilerDirectives.inInterpreter()) {
//                        backBranchesTaken += 1;
//                    }
//                    break;
//                }
//
//                case Q_PUSH_GLOBAL: {
//                    stackPointer += 1;
//                    stack[stackPointer] = ((GlobalNode) quickened[bytecodeIndex]).executeGeneric(frame);
//                    bytecodeIndex += LEN_TWO_ARGS;
//                    break;
//                }
//
//                case Q_SEND: {
//                    AbstractMessageSendNode node = (AbstractMessageSendNode) quickened[bytecodeIndex];
//                    int numberOfArguments = node.getNumberOfArguments();
//
//                    Object[] callArgs = new Object[numberOfArguments];
//                    stackPointer = stackPointer - numberOfArguments + 1;
//                    System.arraycopy(stack, stackPointer, callArgs, 0, numberOfArguments);
//
//                    try {
//                        stack[stackPointer] = node.doPreEvaluated(frame, callArgs);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    } catch (RestartLoopException e) {
//                        bytecodeIndex = 0;
//                        stackPointer = -1;
//                    } catch (EscapedBlockException e) {
//                        CompilerDirectives.transferToInterpreter();
//                        stack[stackPointer] = handleEscapedBlock(frame, e);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    }
//
//                    break;
//                }
//
//                case Q_SEND_1: {
//                    Object rcvr = stack[stackPointer];
//
//                    try {
//                        UnaryExpressionNode node = (UnaryExpressionNode) quickened[bytecodeIndex];
//                        stack[stackPointer] = node.executeEvaluated(frame, rcvr);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    } catch (RestartLoopException e) {
//                        bytecodeIndex = 0;
//                        stackPointer = -1;
//                    } catch (EscapedBlockException e) {
//                        CompilerDirectives.transferToInterpreter();
//                        stack[stackPointer] = handleEscapedBlock(frame, e);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    } catch (RespecializeException r) {
//                        CompilerDirectives.transferToInterpreterAndInvalidate();
//                        stack[stackPointer] = quickenAndEvaluate(frame, bytecodeIndex, r, rcvr);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    }
//                    break;
//                }
//
//                case Q_SEND_2: {
//                    Object rcvr = stack[stackPointer - 1];
//                    Object arg = stack[stackPointer];
//
//                    stackPointer -= 1;
//
//                    try {
//                        BinaryExpressionNode node = (BinaryExpressionNode) quickened[bytecodeIndex];
//                        stack[stackPointer] = node.executeEvaluated(frame, rcvr, arg);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    } catch (RestartLoopException e) {
//                        bytecodeIndex = 0;
//                        stackPointer = -1;
//                    } catch (EscapedBlockException e) {
//                        CompilerDirectives.transferToInterpreter();
//                        stack[stackPointer] = handleEscapedBlock(frame, e);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    } catch (RespecializeException r) {
//                        CompilerDirectives.transferToInterpreterAndInvalidate();
//                        stack[stackPointer] = quickenAndEvaluate(frame, bytecodeIndex, r, rcvr, arg);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    }
//                    break;
//                }
//
//                case Q_SEND_3: {
//                    Object rcvr = stack[stackPointer - 2];
//                    Object arg1 = stack[stackPointer - 1];
//                    Object arg2 = stack[stackPointer];
//
//                    stackPointer -= 2;
//
//                    try {
//                        TernaryExpressionNode node = (TernaryExpressionNode) quickened[bytecodeIndex];
//                        stack[stackPointer] = node.executeEvaluated(frame, rcvr, arg1, arg2);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    } catch (RestartLoopException e) {
//                        bytecodeIndex = 0;
//                        stackPointer = -1;
//                    } catch (EscapedBlockException e) {
//                        CompilerDirectives.transferToInterpreter();
//                        stack[stackPointer] = handleEscapedBlock(frame, e);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    } catch (RespecializeException r) {
//                        CompilerDirectives.transferToInterpreterAndInvalidate();
//                        stack[stackPointer] =
//                                quickenAndEvaluate(frame, bytecodeIndex, r, rcvr, arg1, arg2);
//                        bytecodeIndex += LEN_TWO_ARGS;
//                    }
//                    break;
//                }
//
//                default:
//                    missingBytecode(bytecode);
//            }
//        }
//    }
}
