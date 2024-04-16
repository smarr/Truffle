package jdk.graal.compiler.lir.dfa;

import jdk.graal.compiler.core.common.cfg.AbstractControlFlowGraph;
import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.phases.FinalCodeAnalysisPhase;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.vm.ci.code.TargetDescription;

public final class PushMovesToUsagePhase extends FinalCodeAnalysisPhase {
    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, FinalCodeAnalysisContext context) {
        LIRGenerationResult result = context.lirGen.getResult();
        ControlFlowGraph cfg = (ControlFlowGraph) result.getLIR().getControlFlowGraph();

        AbstractBeginNode dupBytecodeStart = null;
        for (AbstractBeginNode n : cfg.graph.getNodes(AbstractBeginNode.TYPE)) {
            if (n.getBytecodeHandlerIndex() == 1) {
                // the DUP bytecode should be a good start
                dupBytecodeStart = n;
                break;
            }
        }

        int[] blockIds = result.getLIR().linearScanOrder();
        cfg.getNodeToBlock().get(dupBytecodeStart);

        // find the bytecode handler starting blocks

        for (int blockId : blockIds) {
            BasicBlock<?> bb = result.getLIR().getBlockById(blockId);
            bb.getId();
        }


        String name = result.getCompilationUnitName();
        if (name.contains("BytecodeLoopNode.executeGeneric")) {
            int i = 0;
            i += 1;
        }
    }
}
