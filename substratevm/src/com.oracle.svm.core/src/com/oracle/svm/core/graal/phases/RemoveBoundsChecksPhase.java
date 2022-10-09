package com.oracle.svm.core.graal.phases;

import org.graalvm.collections.EconomicSet;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.BeginNode;
import org.graalvm.compiler.nodes.DeoptimizeNode;
import org.graalvm.compiler.nodes.FieldLocationIdentity;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.LogicConstantNode;
import org.graalvm.compiler.nodes.NamedLocationIdentity;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.CompareNode;
import org.graalvm.compiler.nodes.calc.IntegerBelowNode;
import org.graalvm.compiler.nodes.calc.IntegerEqualsNode;
import org.graalvm.compiler.nodes.memory.ReadNode;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.phases.tiers.HighTierContext;

import com.oracle.svm.core.graal.nodes.ThrowBytecodeExceptionNode;

import jdk.vm.ci.meta.DeoptimizationReason;

public class RemoveBoundsChecksPhase extends BasePhase<HighTierContext> {
    protected final CanonicalizerPhase canonicalizer;

    public RemoveBoundsChecksPhase(CanonicalizerPhase canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (!graph.isMyBytecodeLoop) {
            return;
        }

        for (Node n : graph.getNodes()) {
            if (n instanceof ThrowBytecodeExceptionNode) {
                if (!processBytecodeException(graph, (ThrowBytecodeExceptionNode) n, context)) {
                    System.out.println("[TBEx not removed] " + graph.method().toString() + " " + n);
                }
            }
        }

        for (Node n : graph.getNodes()) {
            if (n instanceof IntegerBelowNode) {
                if (processBounds(graph, (IntegerBelowNode) n, context)) {
                    System.out.println("Unexpected triggering of RemBounds on " + n);
                }
            } else if (n instanceof IntegerEqualsNode) {
                if (processBounds(graph, (IntegerEqualsNode) n, context)) {
                    System.out.println("Unexpected triggering of RemBounds on " + n);
                }
            }
        }
    }

    private boolean processBytecodeException(StructuredGraph graph, ThrowBytecodeExceptionNode n, HighTierContext context) {
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
        if (exceptionOnTrue) {
            ifNode.setCondition(LogicConstantNode.contradiction());
        } else {
            ifNode.setCondition(LogicConstantNode.tautology());
        }

        EconomicSet<Node> canonicalizableNodes = EconomicSet.create();
        canonicalizableNodes.add(ifNode);
        canonicalizer.applyIncremental(graph, context, canonicalizableNodes);

        return true;
    }

    private boolean processBounds(StructuredGraph graph, CompareNode compare, HighTierContext context) {
        ValueNode x = compare.getX();
        ValueNode y = compare.getY();

        ReadNode arrayLengthRead = null;
        boolean numArgsCase = false;

        if (x instanceof ReadNode) {
            ReadNode xRead = (ReadNode) x;
            if (xRead.getLocationIdentity().equals(NamedLocationIdentity.ARRAY_LENGTH_LOCATION)) {
                arrayLengthRead = xRead;
            } else if (xRead.getLocationIdentity() instanceof FieldLocationIdentity) {
                FieldLocationIdentity loc = (FieldLocationIdentity) xRead.getLocationIdentity();
                if (loc.getField().getName().equals("numArguments")) {
                    arrayLengthRead = xRead;
                    numArgsCase = true;
                }
            }
        }

        if (arrayLengthRead == null && y instanceof ReadNode) {
            ReadNode yRead = (ReadNode) y;
            if (yRead.getLocationIdentity().equals(NamedLocationIdentity.ARRAY_LENGTH_LOCATION)) {
                arrayLengthRead = yRead;
            } else if (yRead.getLocationIdentity() instanceof FieldLocationIdentity) {
                FieldLocationIdentity loc = (FieldLocationIdentity) yRead.getLocationIdentity();
                if (loc.getField().getName().equals("numArguments")) {
                    arrayLengthRead = yRead;
                    numArgsCase = true;
                }
            }
        }

        if (arrayLengthRead != null && compare.hasExactlyOneUsage()) {
            if (compare.singleUsage() instanceof IfNode) {
                IfNode ifNode = (IfNode) compare.singleUsage();

                if (numArgsCase) {
                    if (ifNode.falseSuccessor() instanceof BeginNode) {
                        // handle the numArgs case
                        BeginNode begin = (BeginNode) ifNode.falseSuccessor();
                        FixedNode maybeBytecodeException = begin.next();

                        if (maybeBytecodeException instanceof ThrowBytecodeExceptionNode) {
                            // now check that the arrayLengthRead is used as we expect it
                            if (arrayLengthRead.getUsageCount() == 4) {
                                int foundNodes = 0;
                                for (Node u : arrayLengthRead.usages()) {
                                    if (u == maybeBytecodeException || u == compare || u instanceof FrameState || u instanceof PiNode) {
                                        foundNodes += 1;
                                    }
                                }

                                if (foundNodes != 4) {
                                    return false;
                                }

                                // now we can start messing with things
                                // we start replacing things, by dropping the if
                                ifNode.setCondition(LogicConstantNode.tautology());

                                EconomicSet<Node> canonicalizableNodes = EconomicSet.create();
                                canonicalizableNodes.add(ifNode);

                                canonicalizer.applyIncremental(graph, context, canonicalizableNodes);

                                // normally, I'd expect these to already have been removed, but who
                                // knows
                                arrayLengthRead.removeUsage(compare);
                                arrayLengthRead.removeUsage(maybeBytecodeException);

                                // now it should be safe to drop these
                                compare.safeDelete();
                                return true;
                            }
                        }
                    }
                } else {
                    // handle the normal array bounds case, where the length is smaller than the
                    // index
                    if (ifNode.trueSuccessor() instanceof BeginNode && isLikelyAKindOfBoundsCheckException(ifNode.trueSuccessor().next())) {
                        BeginNode begin = (BeginNode) ifNode.trueSuccessor();
                        FixedNode bytecodeException = begin.next();
                        return removeExceptionBranch(true, arrayLengthRead, compare, bytecodeException, ifNode, graph, context);
                    } else if (ifNode.falseSuccessor() instanceof BeginNode && isLikelyAKindOfBoundsCheckException(ifNode.falseSuccessor().next())) {
                        // handle the case where the index is smaller than the length
                        BeginNode begin = (BeginNode) ifNode.falseSuccessor();
                        FixedNode bytecodeException = begin.next();
                        return removeExceptionBranch(false, arrayLengthRead, compare, bytecodeException, ifNode, graph, context);
                    }
                }
            }
        }
        return false;
    }

    private static boolean isLikelyAKindOfBoundsCheckException(FixedNode node) {
        if (node instanceof ThrowBytecodeExceptionNode) {
            return true;
        }

        if (node instanceof DeoptimizeNode) {
            DeoptimizeNode deopt = (DeoptimizeNode) node;
            if (deopt.getReason() == DeoptimizationReason.BoundsCheckException) {
                return true;
            }
        }
        return false;
    }

    private boolean removeExceptionBranch(boolean exceptionBranch, ReadNode arrayLengthRead, CompareNode compare, FixedNode bytecodeException, IfNode ifNode, StructuredGraph graph,
                    HighTierContext context) {
        boolean isDeopt = bytecodeException instanceof DeoptimizeNode;

        // now check that the arrayLengthRead is used as we expect it
        if (isDeopt) {
            if (arrayLengthRead.getUsageCount() != 1 || arrayLengthRead.singleUsage() != compare) {
                return false;
            }
        } else {
            if (arrayLengthRead.getUsageCount() != 2) {
                return false;
            }
            for (Node u : arrayLengthRead.usages()) {
                if (u != bytecodeException && u != compare) {
                    // we expect the arrayLengthRead to be used by intBelow and
                    // maybeBytecodeException
                    return false;
                }
            }
        }

        // now we can start messing with things
        // we start replacing things, by dropping the if
        boolean branchToKeep = !exceptionBranch;
        ifNode.setCondition(LogicConstantNode.forBoolean(branchToKeep));

        EconomicSet<Node> canonicalizableNodes = EconomicSet.create();
        canonicalizableNodes.add(ifNode);

        canonicalizer.applyIncremental(graph, context, canonicalizableNodes);

        // normally, I'd expect these to already have been removed, but who
        // knows
        arrayLengthRead.removeUsage(compare);
        arrayLengthRead.removeUsage(bytecodeException);

        // now it should be safe to drop these
        compare.safeDelete();
        graph.removeFixed(arrayLengthRead);
        return true;

    }
}
