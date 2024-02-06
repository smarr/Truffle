package com.oracle.svm.core.graal.phases;

import org.graalvm.collections.EconomicSet;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.ValueProxyNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.extended.BytecodeExceptionNode;
import jdk.graal.compiler.nodes.java.ExceptionObjectNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.LoadIndexedNode;
import jdk.graal.compiler.nodes.java.NewArrayNode;
import jdk.graal.compiler.nodes.java.NewInstanceNode;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;

public class RemoveSafetyPhase extends BasePhase<HighTierContext> {

    protected final CanonicalizerPhase canonicalizer;

    public RemoveSafetyPhase(CanonicalizerPhase canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (!graph.isMyBytecodeLoop) {
            return;
        }

        for (Node n : graph.getNodes()) {
            if (n instanceof BytecodeExceptionNode) {
                if (!processBytecodeException(graph, (BytecodeExceptionNode) n, context)) {
                    System.out.println("[BcEx not removed] " + graph.method().toString() + " " + n);
                }
            }
        }

        for (Node n : graph.getNodes()) {
            if (n.isDeleted()) {
                continue;
            }

            if (n instanceof IsNullNode) {
                if (processIsNull(graph, (IsNullNode) n, context)) {
                    System.out.println("Unexpected triggering of RemIsNull on " + n);
                }
            } else if (n instanceof InstanceOfNode) {
                if (processInstanceOf(graph, (InstanceOfNode) n, context)) {
                    System.out.println("Unexpected triggering of RemInstanceOf on " + n);
                }
            }
        }
    }

    private boolean processBytecodeException(StructuredGraph graph, BytecodeExceptionNode n, HighTierContext context) {
        Node possibleBegin = n.predecessor();
        if (!(possibleBegin instanceof BeginNode)) {
            return false;
        }

        Node possibleIf = possibleBegin.predecessor();
        if (!(possibleIf instanceof IfNode)) {
            return false;
        }

        IfNode ifNode = (IfNode) possibleIf;

        boolean exceptionOnTrue = ifNode.trueSuccessor() == possibleBegin;
        ifNode.setCondition(LogicConstantNode.forBoolean(!exceptionOnTrue));

        EconomicSet<Node> canonicalizableNodes = EconomicSet.create();
        canonicalizableNodes.add(ifNode);
        canonicalizer.applyIncremental(graph, context, canonicalizableNodes);

        return true;
    }

    private boolean processInstanceOf(StructuredGraph graph, InstanceOfNode node, HighTierContext context) {
        if (!node.hasExactlyOneUsage()) {
            return false;
        }

        if (!(node.singleUsage() instanceof IfNode)) {
            return false;
        }

        IfNode ifNode = (IfNode) node.singleUsage();

        if (!(ifNode.falseSuccessor() instanceof BeginNode)) {
            return false;
        }

        BeginNode falseBranch = (BeginNode) ifNode.falseSuccessor();
        if (!(falseBranch.next() instanceof BytecodeExceptionNode)) {
// String typeName = node.getCheckedStamp().javaType(context.getMetaAccess()).getName();
// if (typeName.equals("Ljava/lang/ArithmeticException;")) {
// return processPossibleArithmeticExceptionAsOverflowCheck(graph, node, context, ifNode);
// }
            return false;
        }

        ifNode.setCondition(LogicConstantNode.tautology());

        EconomicSet<Node> canonicalizableNodes = EconomicSet.create();
        canonicalizableNodes.add(ifNode);

        canonicalizer.applyIncremental(graph, context, canonicalizableNodes);
        node.safeDelete();
        return true;
    }

    private boolean processPossibleArithmeticExceptionAsOverflowCheck(StructuredGraph graph, InstanceOfNode n, HighTierContext context, IfNode ifNode) {
        ValueNode val = n.getValue();
        if (!(val instanceof ValueProxyNode)) {
            return false;
        }

        ValueProxyNode valProx = (ValueProxyNode) val;
        ValueNode maybePhi = valProx.value();
        if (!(maybePhi instanceof ValuePhiNode)) {
            return false;
        }

        ValuePhiNode phi = (ValuePhiNode) maybePhi;
        if (phi.valueCount() != 2) {
            return false;
        }

        ExceptionObjectNode exO;
        NewInstanceNode newArithExceptionInstance;
        if (phi.valueAt(0) instanceof ExceptionObjectNode && phi.valueAt(1) instanceof NewInstanceNode) {
            exO = (ExceptionObjectNode) phi.valueAt(0);
            newArithExceptionInstance = (NewInstanceNode) phi.valueAt(1);
        } else {
            newArithExceptionInstance = (NewInstanceNode) phi.valueAt(0);
            exO = (ExceptionObjectNode) phi.valueAt(1);
        }

        if (n.getCheckedStamp().javaType(context.getMetaAccess()) != newArithExceptionInstance.instanceClass()) {
            return false;
        }

        BeginNode onArithExceptionBegin = (BeginNode) ifNode.trueSuccessor();
        Node onArithExceptionNode = onArithExceptionBegin.successors().first();

        Node newOPre = newArithExceptionInstance.predecessor();
        if (!(newOPre instanceof BeginNode)) {
            return false;
        }

        // ifNode.clearSuccessors();
        // graph.replaceFixedWithFixed(newArithExceptionBranch, onArithExceptionBegin);
        // GraphUtil.killCFG(newArithExceptionBranch);

        // first, get rid of the if node so that we don't have to deal with both branches
        ifNode.setCondition(LogicConstantNode.tautology());

        EconomicSet<Node> canonicalizableNodes = EconomicSet.create();
        canonicalizableNodes.add(ifNode);

        canonicalizer.applyIncremental(graph, context, canonicalizableNodes);
        n.safeDelete();

        // now unlink the onArithExceptionNode
        ((FixedWithNextNode) onArithExceptionNode.predecessor()).setNext(null);

        newOPre.replaceFirstSuccessor(newArithExceptionInstance, onArithExceptionNode);
        GraphUtil.killCFG(newArithExceptionInstance);

        // arithExceptionBranch.replaceFirstSuccessor(newO, whenArtihException);
        // newArithExceptionInstance.safeDelete();

        return true;

    }

    private boolean processSingleUseIsNullWithNPE(StructuredGraph graph, IsNullNode node, HighTierContext context) {
        if (!node.hasExactlyOneUsage()) {
            return false;
        }

        if (!(node.singleUsage() instanceof IfNode)) {
            return false;
        }

        IfNode ifNode = (IfNode) node.singleUsage();
        if (!(ifNode.trueSuccessor() instanceof BeginNode)) {
            return false;
        }

        BeginNode falseBranch = (BeginNode) ifNode.trueSuccessor();
        if (!(falseBranch.next() instanceof BytecodeExceptionNode)) {
            return false;
        }

        ifNode.setCondition(LogicConstantNode.contradiction());

        EconomicSet<Node> canonicalizableNodes = EconomicSet.create();
        canonicalizableNodes.add(ifNode);

        canonicalizer.applyIncremental(graph, context, canonicalizableNodes);
        node.safeDelete();
        return true;
    }

    private boolean processIsNull(StructuredGraph graph, IsNullNode node, HighTierContext context) {
        if (processSingleUseIsNullWithNPE(graph, node, context)) {
            return false;
        }

        boolean notRelevant = false;
        if (node.getValue() instanceof PiNode) {
            PiNode pi = (PiNode) node.getValue();
            if (pi.object() instanceof LoadIndexedNode) {
                LoadIndexedNode load = (LoadIndexedNode) pi.object();
                if (load.array() instanceof LoadFieldNode) {
                    LoadFieldNode field = (LoadFieldNode) load.array();
                    if (field.field().getName().equals("quickenedField")) {
                        notRelevant = true;
                    }
                }
            }
        } else if (node.getValue() instanceof LoadIndexedNode) {
            LoadIndexedNode load = (LoadIndexedNode) node.getValue();
            if (load.array() instanceof LoadFieldNode) {
                LoadFieldNode field = (LoadFieldNode) load.array();
                if (field.field().getName().equals("quickenedField")) {
                    notRelevant = true;
                }
            }
        }

        if (!notRelevant) {
            if (node.getValue() instanceof PiNode) {
                PiNode pi = (PiNode) node.getValue();
                if (pi.object() instanceof LoadIndexedNode) {
                    LoadIndexedNode load = (LoadIndexedNode) pi.object();
                    if (load.array() instanceof NewArrayNode) {
                        if (removeIsNullFromStackReads(graph, node, context, pi)) {
                            return false;
                        }
                    } else if (load.array() instanceof LoadFieldNode) {
                        LoadFieldNode field = (LoadFieldNode) load.array();
                        if (field.field().getName().equals("literalsAndConstantsField")) {
                            if (removeTestOfIsNull(graph, node, context)) {
                                return false;
                            }
                        }
                    }
                }
            } else if (node.getValue() instanceof LoadFieldNode) {
                LoadFieldNode field = (LoadFieldNode) node.getValue();
                switch (field.field().getName()) {
                    case "indexedTags":
                    case "indexedPrimitiveLocals":
                    case "indexedLocals":
                    case "descriptor":
                    case "dispatchNode":
                    case "storageLocations":
                    case "objectLayout":
                    case "storage":
                    case "arguments":
                    case "send":
                    case "layout":
                    case "latestLayoutForClass":
                        if (removeTestOfIsNull(graph, node, context)) {
                            return true;
                        }
                }
            } else if (node.getValue() instanceof Invoke) {
                Invoke i = (Invoke) node.getValue();
                String name = i.callTarget().targetName();
                switch (name) {
                    case "BytecodeLoopNode.determineContext":
                    case "BytecodeLoopNode.determineOuterContext":
                    case "BytecodeLoopNode.getHolder":
                    case "BytecodeLoopNode.createRead":
                    case "BytecodeLoopNode.createWrite":
                    case "MessageSendNode.createSuperSend":
                        if (removeTestOfIsNull(graph, node, context)) {
                            return true;
                        }
                }
            }

            int i = 0;
        }

        return false;
    }

    private boolean removeIsNullFromStackReads(StructuredGraph graph, IsNullNode node, HighTierContext context, PiNode pi) {
        // this is a load from the stack, and we don't need null checks here

        // first, let's try to indicate it's not null
        pi.strengthenPiStamp(StampFactory.objectNonNull());

        return removeTestOfIsNull(graph, node, context);
    }

    private boolean removeTestOfIsNull(StructuredGraph graph, IsNullNode node, HighTierContext context) {
        // and now, let's remove the branch using the IsNullNode
        if (node.getUsageCount() == 1) {
            if (node.singleUsage() instanceof IfNode) {
                IfNode ifNode = (IfNode) node.singleUsage();
                ifNode.setCondition(LogicConstantNode.contradiction());

                EconomicSet<Node> canonicalizableNodes = EconomicSet.create();
                canonicalizableNodes.add(ifNode);

                canonicalizer.applyIncremental(graph, context, canonicalizableNodes);
                node.safeDelete();
                return true;
            }
        }
        return false;
    }

}
