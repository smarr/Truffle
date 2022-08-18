package com.oracle.svm.core.graal.phases;

import org.graalvm.collections.EconomicSet;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.BeginNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FieldLocationIdentity;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.LogicConstantNode;
import org.graalvm.compiler.nodes.NamedLocationIdentity;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.IntegerBelowNode;
import org.graalvm.compiler.nodes.memory.ReadNode;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.phases.tiers.HighTierContext;

import com.oracle.svm.core.graal.nodes.ThrowBytecodeExceptionNode;

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
            if (n instanceof IntegerBelowNode) {
                processBounds(graph, (IntegerBelowNode) n, context);
            }
        }
    }

    private boolean processBounds(StructuredGraph graph, IntegerBelowNode intBelow, HighTierContext context) {
        ValueNode x = intBelow.getX();
        ValueNode y = intBelow.getY();

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

        if (arrayLengthRead != null && intBelow.hasExactlyOneUsage()) {
            if (intBelow.singleUsage() instanceof IfNode) {
                IfNode ifNode = (IfNode) intBelow.singleUsage();

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
                                    if (u == maybeBytecodeException || u == intBelow || u instanceof FrameState || u instanceof PiNode) {
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
                                arrayLengthRead.removeUsage(intBelow);
                                arrayLengthRead.removeUsage(maybeBytecodeException);

                                // now it should be safe to drop these
                                intBelow.safeDelete();
                                return true;
                            }
                        }
                    }
                } else {
                    // handle the normal array bounds case
                    if (ifNode.trueSuccessor() instanceof BeginNode) {
                        BeginNode begin = (BeginNode) ifNode.trueSuccessor();
                        FixedNode maybeBytecodeException = begin.next();

                        if (maybeBytecodeException instanceof ThrowBytecodeExceptionNode) {
                            // now check that the arrayLengthRead is used as we expect it
                            if (arrayLengthRead.getUsageCount() == 2) {
                                for (Node u : arrayLengthRead.usages()) {
                                    if (u != maybeBytecodeException && u != intBelow) {
                                        // we expect the arrayLengthRead to be used by intBelow and
                                        // maybeBytecodeException
                                        return false;
                                    }
                                }

                                // now we can start messing with things
                                // we start replacing things, by dropping the if
                                ifNode.setCondition(LogicConstantNode.contradiction());

                                EconomicSet<Node> canonicalizableNodes = EconomicSet.create();
                                canonicalizableNodes.add(ifNode);

                                canonicalizer.applyIncremental(graph, context, canonicalizableNodes);

                                // normally, I'd expect these to already have been removed, but who
                                // knows
                                arrayLengthRead.removeUsage(intBelow);
                                arrayLengthRead.removeUsage(maybeBytecodeException);

                                // now it should be safe to drop these
                                intBelow.safeDelete();
                                graph.removeFixed(arrayLengthRead);
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}
