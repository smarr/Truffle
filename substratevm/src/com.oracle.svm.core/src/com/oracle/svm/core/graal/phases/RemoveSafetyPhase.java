package com.oracle.svm.core.graal.phases;

import org.graalvm.collections.EconomicSet;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.BeginNode;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.LogicConstantNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.calc.IsNullNode;
import org.graalvm.compiler.nodes.extended.BytecodeExceptionNode;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.LoadIndexedNode;
import org.graalvm.compiler.nodes.java.NewArrayNode;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.phases.tiers.HighTierContext;

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
            if (n instanceof IsNullNode) {
                processIsNull(graph, (IsNullNode) n, context);
            } else if (n instanceof InstanceOfNode) {
                processInstanceOf(graph, (InstanceOfNode) n, context);
            } else if (n instanceof BytecodeExceptionNode) {
                processBytecodeException(graph, (BytecodeExceptionNode) n, context);
            }
        }
    }

    private void processBytecodeException(StructuredGraph graph, BytecodeExceptionNode n, HighTierContext context) {
        Node possibleBegin = n.predecessor();
        if (!(possibleBegin instanceof BeginNode)) {
            return;
        }

        Node possibleIf = possibleBegin.predecessor();
        if (!(possibleIf instanceof IfNode)) {
            return;
        }

        IfNode ifNode = (IfNode) possibleIf;
        if (!(ifNode.condition() instanceof IsNullNode)) {
            return;
        }

        boolean exceptionOnTrue = ifNode.trueSuccessor() == possibleBegin;
        if (!exceptionOnTrue) {
            return;
        }

        ifNode.setCondition(LogicConstantNode.contradiction());

        EconomicSet<Node> canonicalizableNodes = EconomicSet.create();
        canonicalizableNodes.add(ifNode);
        canonicalizer.applyIncremental(graph, context, canonicalizableNodes);
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
            return false;
        }

        ifNode.setCondition(LogicConstantNode.tautology());

        EconomicSet<Node> canonicalizableNodes = EconomicSet.create();
        canonicalizableNodes.add(ifNode);

        canonicalizer.applyIncremental(graph, context, canonicalizableNodes);
        node.safeDelete();
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

    private void processIsNull(StructuredGraph graph, IsNullNode node, HighTierContext context) {
        if (processSingleUseIsNullWithNPE(graph, node, context)) {
            return;
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
                            return;
                        }
                    } else if (load.array() instanceof LoadFieldNode) {
                        LoadFieldNode field = (LoadFieldNode) load.array();
                        if (field.field().getName().equals("literalsAndConstantsField")) {
                            if (removeTestOfIsNull(graph, node, context)) {
                                return;
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
                            return;
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
                            return;
                        }
                }
            }

            int i = 0;
        }
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
