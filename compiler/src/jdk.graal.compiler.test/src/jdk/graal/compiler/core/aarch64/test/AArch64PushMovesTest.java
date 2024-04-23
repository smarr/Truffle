package jdk.graal.compiler.core.aarch64.test;

import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.StandardOp;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.jtt.LIRTest;
import jdk.graal.compiler.lir.phases.FinalCodeAnalysisPhase;
import jdk.graal.compiler.lir.phases.LIRPhase;
import jdk.graal.compiler.lir.phases.LIRSuites;
import jdk.graal.compiler.lir.phases.PreAllocationOptimizationPhase;
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
        for (LIRInstruction ins : lir.getLIRforBlock(lir.getControlFlowGraph().getBlocks()[lir.codeEmittingOrder()[0]])) {
            if (predicate.test(ins)) {
                actualOpNum++;
            }
        }
        Assert.assertEquals(expected, actualOpNum);
    }
}
