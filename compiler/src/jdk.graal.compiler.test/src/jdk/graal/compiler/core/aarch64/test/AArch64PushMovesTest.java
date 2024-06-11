package jdk.graal.compiler.core.aarch64.test;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.HostCompilerDirectives.InliningCutoff;
import com.oracle.truffle.api.HostCompilerDirectives.BytecodeInterpreterSwitch;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.StandardOp;
import jdk.graal.compiler.lir.dfa.PushMovesToUsagePhase.BasicBlockBytecodeDetails;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.jtt.LIRTest;
import jdk.graal.compiler.lir.phases.FinalCodeAnalysisPhase;
import jdk.graal.compiler.lir.phases.LIRSuites;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.code.TargetDescription;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.function.Predicate;

public class AArch64PushMovesTest extends LIRTest {
    private LIR lir;

    /**
     * Test snippet that should not trigger the PushMovesToUsagePhase phase
     */
    @SuppressWarnings("unused")
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

    @InliningCutoff
    private static void noop(byte[] bytecodes, int i) {
        System.out.println(bytecodes[i]);
        byte old = bytecodes[i];
        bytecodes[i] = 0;
        System.out.println(bytecodes[i]);
        bytecodes[i] = old;
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
        final Object[] quickenedNodes = new Object[bytecodes.length];

        boolean isIntegerField = false;

        int i = 0;
        int stackPointer = 0;
        long[] stack = new long[10];

        for (;;) {
            byte b = bytecodes[i];

            switch (b) {
                case 0: {
                    if (frame.fieldType == 10) {
                        isIntegerField = true;
                    } else {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new UnsupportedOperationException();
                    }
                    i += 1;
                    break;
                }
                case 1: {
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
                }
                case 2: {
                    try {
                        state.top = Math.multiplyExact(state.top, frame.const1);
                    } catch (ArithmeticException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new UnsupportedOperationException();
                    }
                    i += 1;
                    break;
                }
                case 3: {
                    try {
                        state.top = Math.addExact(state.top, frame.const2);
                    } catch (ArithmeticException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new UnsupportedOperationException();
                    }
                    i += 1;
                    break;
                }
                case 4: {
                    state.top = state.top & frame.const3;
                    i += 1;
                    break;
                }
                case 5: {
                    ((LangObject) rcvr).field = state.top;
                    i += 1;
                    break;
                }
                case 6: {
                    // return state.top;
                    i += 1;
                    break;
                }
                case 7: {
                    if (frame.fieldType == 10) {
                        isIntegerField = true;
                    } else {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new UnsupportedOperationException();
                    }
                    i += bytecodes[i + 1];
                    stack[stackPointer] = state.top;
                    stackPointer += 1;
                    break;
                }
                case 8: {
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
                    stackPointer += 1;
                    state.top = stack[stackPointer - 1];
                    break;
                }
                case 9: {
                    try {
                        state.top = Math.multiplyExact(state.top, frame.const1);
                    } catch (ArithmeticException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new UnsupportedOperationException();
                    }
                    i += bytecodes[i + 1];
                    stackPointer -= 1;
                    state.top = stack[stackPointer];
                    break;
                }
                case 10: {
                    try {
                        state.top = Math.addExact(state.top, frame.const2);
                    } catch (ArithmeticException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new UnsupportedOperationException();
                    }
                    i += 1;
                    stackPointer -= 1;
                    stack[stackPointer] = state.top;
                    break;
                }
                case 11: {
                    state.top = state.top & frame.const3;
                    i += 1;
                    quickenedNodes[i] = state;
                    break;
                }
                case 12: {
                    ((LangObject) rcvr).field = state.top;
                    i += 1;
                    quickenedNodes[i] = rcvr;
                    break;
                }
                case 13: {
                    if (quickenedNodes[i] != null) {
                        return quickenedNodes[i];
                    }
                    return state.top;
                }
            }
        }
    }

    @Test
    public void testBytecodeLoopHandlersGetMarked() {
        MethodActivation frame = new MethodActivation(1309, 13849, 65535, new byte[] {
                0, 1, 2, 3, 4, 5, 6,
                7, 2 /*len*/, 8, 9, 2/*len*/, 10, 11, 12, 13
        }, 1);
        test("smallBytecodeLoop", frame);
        checkLIR("smallBytecodeLoop", (op) -> {
            // count how many basic blocks were marked as bytecode handlers
            if (op instanceof StandardOp.LabelOp label) {
                return label.getBytecodeHandlerIndex() != -1;
            }
            return false;
            // we'd expect number of bytecode handlers for the smallBytecodeLoop
        }, 14);

        checkCFDetails(36, true, true, false,0);
        checkCFDetails(38, true, true, false,1);
        checkCFDetails(46, true, true, false,2);
        checkCFDetails(49, true, true, false,3);
        checkCFDetails(52, true, true, false,4);
        checkCFDetails(53, true, true, false,5);
        checkCFDetails(59, true, true, false,6);
    }

    protected void checkCFDetails(int blockId, boolean canHead, boolean canSlow, boolean canReturn, int bhi) {
        int[] codeEmitOrder = lir.codeEmittingOrder();

        ArrayList<LIRInstruction> insts = lir.getLIRforBlock(lir.getBlockById(blockId));
        Assert.assertThat(insts.getFirst(), CoreMatchers.instanceOf(StandardOp.LabelOp.class));
        StandardOp.LabelOp label = (StandardOp.LabelOp) insts.getFirst();
        Assert.assertEquals(bhi, label.getBytecodeHandlerIndex());
        BasicBlockBytecodeDetails details = (BasicBlockBytecodeDetails) label.hackPushMovesToUsagePhaseData;
        Assert.assertEquals(canHead, details.canLeadToHeadOfLoop);
        Assert.assertEquals(canSlow, details.canLeadToSlowPath);
        Assert.assertEquals(canReturn, details.canLeadToReturn);
    }
}
