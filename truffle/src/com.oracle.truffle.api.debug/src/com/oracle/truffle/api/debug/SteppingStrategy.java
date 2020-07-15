/*
 * Copyright (c) 2015, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.debug;

import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.InstrumentableNode.WrapperNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

import java.util.ArrayList;

/**
 * Implementation of a strategy for a debugger <em>action</em> that allows execution to continue
 * until it reaches another location e.g "step in" vs. "step over".
 */
abstract class SteppingStrategy {

    /*
     * Indicates that a stepping strategy was consumed by an suspended event.
     */
    private boolean consumed;
    private SteppingStrategy next;

    void consume() {
        consumed = true;
    }

    boolean isConsumed() {
        return consumed;
    }

    void notifyCallEntry() {
    }

    void notifyCallExit() {
    }

    @SuppressWarnings("unused")
    void notifyNodeEntry(EventContext context) {
    }

    @SuppressWarnings("unused")
    void notifyNodeExit(EventContext context) {
    }

    Object notifyOnUnwind() {
        return null;
    }

    boolean isStopAfterCall() {
        return true;
    }

    boolean isCollectingInputValues() {
        return false;
    }

    /**
     * Like {@link #isActive(EventContext, SuspendAnchor)}, but is called on a node entry/return
     * only. It allows to include the node entry/return events to call entry/exit events for cases
     * when the step over/out is not determined by pushed frames only, but pushed nodes also.
     */
    final boolean isActiveOnStepTo(EventContext context, SuspendAnchor suspendAnchor) {
        if (SuspendAnchor.BEFORE == suspendAnchor) {
            notifyNodeEntry(context);
        } else {
            notifyNodeExit(context);
        }
        return isActive(context, suspendAnchor);
    }

    /**
     * Test if the strategy is active at this context. If yes,
     * {@link #step(DebuggerSession, EventContext, SuspendAnchor)} will be called.
     */
    @SuppressWarnings("unused")
    boolean isActive(EventContext context, SuspendAnchor suspendAnchor) {
        return true;
    }

    abstract boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor);

    @SuppressWarnings("unused")
    void initialize(SuspendedContext context, SuspendAnchor suspendAnchor) {
    }

    boolean isDone() {
        return false;
    }

    boolean isUnwind() {
        return false;
    }

    boolean isKill() {
        return false;
    }

    boolean isComposable() {
        return false;
    }

    @SuppressWarnings("unused")
    void add(SteppingStrategy nextStrategy) {
        throw new UnsupportedOperationException("Not composable.");
    }

    static SteppingStrategy createKill() {
        return new Kill();
    }

    static SteppingStrategy createAlwaysHalt() {
        return new AlwaysHalt();
    }

    static SteppingStrategy createContinue() {
        return new Continue();
    }

    static SteppingStrategy createStepInto(DebuggerSession session, StepConfig config) {
        return new StepInto(session, config);
    }

    static SteppingStrategy createStepOut(DebuggerSession session, StepConfig config) {
        return new StepOut(session, config);
    }

    static SteppingStrategy createStepOver(DebuggerSession session, StepConfig config) {
        return new StepOver(session, config);
    }

    static SteppingStrategy createStepNext(DebuggerSession session, StepConfig config) {
        return new StepNext(session, config);
    }

    static SteppingStrategy createStepEndTurn(DebuggerSession session, StepConfig config, final Node node, String messageSelector, ArrayList<RootNode> rootNodeFrames) {
        return new StepEndTurn(session, config, node, messageSelector, rootNodeFrames);
    }

    static SteppingStrategy createUnwind(int depth, DebugValue returnValue) {
        return new Unwind(depth, returnValue);
    }

    static SteppingStrategy createComposed(SteppingStrategy strategy1, SteppingStrategy strategy2) {
        return new ComposedStrategy(strategy1, strategy2);
    }

    private static final class Kill extends SteppingStrategy {

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            return true;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public boolean isKill() {
            return true;
        }

        @Override
        public String toString() {
            return "KILL";
        }

    }

    private static final class AlwaysHalt extends SteppingStrategy {

        @Override
        boolean isActive(EventContext context, SuspendAnchor suspendAnchor) {
            return SuspendAnchor.BEFORE == suspendAnchor;
        }

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            return SuspendAnchor.BEFORE == suspendAnchor;
        }

        @Override
        public String toString() {
            return "HALT";
        }

    }

    /**
     * Strategy: the null stepping strategy.
     * <ul>
     * <li>User breakpoints are enabled.</li>
     * <li>Execution continues until either:
     * <ol>
     * <li>execution arrives at a node with attached user breakpoint, <strong>or:</strong></li>
     * <li>execution completes.</li>
     * </ol>
     * </ul>
     */
    private static final class Continue extends SteppingStrategy {

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public String toString() {
            return "CONTINUE";
        }

    }

    /**
     * Strategy: per-{@link #HALT_TAG} stepping.
     * <ul>
     * <li>User breakpoints are enabled.</li>
     * <li>Execution continues until either:
     * <ol>
     * <li>execution <em>arrives</em> at a {@link #HALT_TAG} node, <strong>or:</strong></li>
     * <li>execution <em>returns</em> to a {@link #CALL_TAG} node and the call stack is smaller then
     * when execution started, <strong>or:</strong></li>
     * <li>execution completes.</li>
     * </ol>
     * </ul>
     *
     * @see Debugger#prepareStepInto(int)
     */
    private static final class StepInto extends SteppingStrategy {

        private final DebuggerSession session;
        private final StepConfig stepConfig;
        private int stackCounter;
        private int unfinishedStepCount;

        StepInto(DebuggerSession session, StepConfig stepConfig) {
            this.session = session;
            this.stepConfig = stepConfig;
            this.unfinishedStepCount = stepConfig.getCount();
        }

        @Override
        void initialize(SuspendedContext context, SuspendAnchor suspendAnchor) {
            this.stackCounter = 0;
        }

        @Override
        void notifyCallEntry() {
            stackCounter++;
        }

        @Override
        void notifyCallExit() {
            stackCounter--;
        }

        @Override
        boolean isStopAfterCall() {
            return stackCounter < 0;
        }

        @Override
        boolean isCollectingInputValues() {
            return stepConfig.containsSourceElement(session, SourceElement.EXPRESSION);
        }

        @Override
        boolean isActive(EventContext context, SuspendAnchor suspendAnchor) {
            return stepConfig.matches(session, context, suspendAnchor);
        }

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            if (stepConfig.matches(session, context, suspendAnchor) ||
                            SuspendAnchor.AFTER == suspendAnchor && stackCounter < 0) {
                stackCounter = 0;
                if (--unfinishedStepCount <= 0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return String.format("STEP_INTO(stackCounter=%s, stepCount=%s)", stackCounter, unfinishedStepCount);
        }

    }

    /**
     * Strategy: execution to nearest enclosing call site.
     * <ul>
     * <li>User breakpoints are enabled.</li>
     * <li>Execution continues until either:
     * <ol>
     * <li>execution arrives at a node with attached user breakpoint, <strong>or:</strong></li>
     * <li>execution <em>returns</em> to a CALL node and the call stack is smaller than when
     * execution started, <strong>or:</strong></li>
     * <li>execution completes.</li>
     * </ol>
     * </ul>
     *
     * @see Debugger#prepareStepOut()
     */
    private static final class StepOut extends SteppingStrategy {

        private final DebuggerSession session;
        private final StepConfig stepConfig;
        private final boolean exprStepping;
        private int stackCounter;
        private int exprCounter;
        private int unfinishedStepCount;
        private boolean activeFrame = false;
        private boolean activeExpression = false;

        StepOut(DebuggerSession session, StepConfig stepConfig) {
            this.session = session;
            this.stepConfig = stepConfig;
            this.exprStepping = stepConfig.containsSourceElement(session, SourceElement.EXPRESSION);
            this.unfinishedStepCount = stepConfig.getCount();
        }

        @Override
        void initialize(SuspendedContext context, SuspendAnchor suspendAnchor) {
            this.stackCounter = 0;
            this.exprCounter = 0;
        }

        @Override
        void notifyCallEntry() {
            stackCounter++;
            activeFrame = false;
        }

        @Override
        void notifyCallExit() {
            boolean isOn = (--stackCounter) < 0;
            if (isOn) {
                activeFrame = true;
            }
        }

        @Override
        void notifyNodeEntry(EventContext context) {
            if (exprStepping && context.hasTag(SourceElement.EXPRESSION.getTag())) {
                exprCounter++;
                activeExpression = false;
            }
        }

        @Override
        void notifyNodeExit(EventContext context) {
            if (exprStepping && context.hasTag(SourceElement.EXPRESSION.getTag())) {
                boolean isOn = (--exprCounter) < 0;
                if (isOn) {
                    activeExpression = true;
                }
            }
        }

        @Override
        boolean isStopAfterCall() {
            return activeFrame;
        }

        @Override
        boolean isCollectingInputValues() {
            return stepConfig.containsSourceElement(session, SourceElement.EXPRESSION);
        }

        @Override
        boolean isActive(EventContext context, SuspendAnchor suspendAnchor) {
            return (activeFrame || activeExpression) && stepConfig.matches(session, context, suspendAnchor);
        }

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            stackCounter = 0;
            exprCounter = 0;
            if (--unfinishedStepCount <= 0) {
                return true;
            }
            activeFrame = false; // waiting for next call exit
            return false;
        }

        @Override
        public String toString() {
            return String.format("STEP_OUT(stackCounter=%s, stepCount=%s)", stackCounter, unfinishedStepCount);
        }

    }

    /**
     * Strategy: per-{@link #HALT_TAG} stepping, so long as not nested in method calls (i.e. at
     * original stack depth).
     * <ul>
     * <li>User breakpoints are enabled.</li>
     * <li>Execution continues until either:
     * <ol>
     * <li>execution arrives at a node holding {@link #HALT_TAG}, with stack depth no more than when
     * started <strong>or:</strong></li>
     * <li>the program completes.</li>
     * </ol>
     * </ul>
     */
    private static final class StepOver extends SteppingStrategy {

        private final DebuggerSession session;
        private final StepConfig stepConfig;
        private final boolean exprStepping;
        private int stackCounter;
        private int exprCounter;
        private int unfinishedStepCount;
        private boolean activeFrame = true;
        private boolean activeExpression = true;

        StepOver(DebuggerSession session, StepConfig stepConfig) {
            this.session = session;
            this.stepConfig = stepConfig;
            this.exprStepping = stepConfig.containsSourceElement(session, SourceElement.EXPRESSION);
            this.unfinishedStepCount = stepConfig.getCount();
        }

        @Override
        void initialize(SuspendedContext context, SuspendAnchor suspendAnchor) {
            this.stackCounter = 0;
            this.exprCounter = context.hasTag(SourceElement.EXPRESSION.getTag()) && SuspendAnchor.BEFORE == suspendAnchor ? 0 : -1;
        }

        @Override
        void notifyCallEntry() {
            stackCounter++;
            activeFrame = stackCounter <= 0;
        }

        @Override
        void notifyCallExit() {
            boolean isOn = (--stackCounter) <= 0;
            if (isOn) {
                activeFrame = true;
            }
        }

        @Override
        void notifyNodeEntry(EventContext context) {
            if (exprStepping && context.hasTag(SourceElement.EXPRESSION.getTag())) {
                exprCounter++;
                activeExpression = exprCounter <= 0;
            }
        }

        @Override
        void notifyNodeExit(EventContext context) {
            if (exprStepping && context.hasTag(SourceElement.EXPRESSION.getTag())) {
                boolean isOn = (--exprCounter) < 0;
                if (isOn) {
                    activeExpression = true;
                }
            }
        }

        @Override
        boolean isStopAfterCall() {
            return stackCounter < 0;
        }

        @Override
        boolean isCollectingInputValues() {
            return stepConfig.containsSourceElement(session, SourceElement.EXPRESSION);
        }

        @Override
        boolean isActive(EventContext context, SuspendAnchor suspendAnchor) {
            return activeFrame && activeExpression && stepConfig.matches(session, context, suspendAnchor);
        }

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            if (stepConfig.matches(session, context, suspendAnchor) ||
                            SuspendAnchor.AFTER == suspendAnchor && (stackCounter < 0 || exprCounter < 0)) {
                stackCounter = 0;
                exprCounter = context.hasTag(SourceElement.EXPRESSION.getTag()) && SuspendAnchor.BEFORE == suspendAnchor ? 0 : -1;
                return --unfinishedStepCount <= 0;
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format("STEP_OVER(stackCounter=%s, stepCount=%s)", stackCounter, unfinishedStepCount);
        }

    }

    private static final class StepNext extends SteppingStrategy {
        private final DebuggerSession session;
        private final StepConfig stepConfig;

        private int stackCounter;
        private boolean activeFrame;
        private Node targetNode;

        StepNext(DebuggerSession session, StepConfig stepConfig) {
            this.session = session;
            this.stepConfig = stepConfig;
        }

        @Override
        void initialize(SuspendedContext context, SuspendAnchor suspendAnchor) {
            this.stackCounter = 0;
        }

        @Override
        void notifyCallEntry() {
            stackCounter += 1;
            activeFrame = stackCounter == 0;
        }

        @Override
        void notifyCallExit() {
            stackCounter -= 1;
            if (stackCounter == 0) {
                activeFrame = true;
            }
        }

        @Override
        boolean isActive(EventContext context, SuspendAnchor suspendAnchor) {
            if (targetNode == null && context.hasTag(stepConfig.getTag())) {
                // record the target node
                targetNode = context.getInstrumentedNode();
                assert targetNode.getParent() instanceof WrapperNode;

                // adjust the stackCounter to reflect that this is where we want to be
                stackCounter = 0;
                activeFrame = true;
            }

            return activeFrame &&
                            // the target node may specialize/replace itself, though, the wrapper,
                            // which we expect to be there, will remain the same and has a stable
                            // identity
                            targetNode.getParent() == context.getInstrumentedNode().getParent() &&
                            stepConfig.matches(session, context, suspendAnchor);
        }

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            return isActive(context, suspendAnchor);
        }
    }

    private static final class StepEndTurn extends SteppingStrategy {
        private final DebuggerSession session;
        private final StepConfig stepConfig;

        private int stackCounter;
        private boolean activeFrame;
        private Node targetNode;
        private SourceSection turnSuspendedSourceSection;
        private String messageSelector;
        private ArrayList<RootNode> rootNodeFrames;

        StepEndTurn(DebuggerSession session, StepConfig stepConfig, Node suspendedNode, String messageSelector, ArrayList<RootNode> rootNodeFrames) {
            this.session = session;
            this.stepConfig = stepConfig;
            this.messageSelector = messageSelector;
            this.rootNodeFrames = rootNodeFrames;
            this.turnSuspendedSourceSection = getTurnSourceSectionForNode(suspendedNode);
        }

        @Override
        void initialize(SuspendedContext context, SuspendAnchor suspendAnchor) {
            this.stackCounter = 0;
        }

        @Override
        void notifyNodeEntry(EventContext context) {
            super.notifyNodeEntry(context);
        }

        @Override
        void notifyNodeExit(EventContext context) {
            super.notifyNodeExit(context);
        }

        @Override
        void notifyCallEntry() {
            stackCounter += 1;
            activeFrame = stackCounter == 0;
        }

        @Override
        void notifyCallExit() {
            stackCounter -= 1;
            if (stackCounter == 0) {
                activeFrame = true;
            }
        }

        @Override
        boolean isActive(EventContext context, SuspendAnchor suspendAnchor) {
            boolean currentTurn = false;

            // step to the next root node and check if the targetNode belongs to the suspended
            // method/turn
            if (context.hasTag(stepConfig.getTag())) {
                // record the target node
                targetNode = context.getInstrumentedNode();
                assert targetNode.getParent() instanceof WrapperNode;

                SourceSection targetSourceSection = getMethodSourceSectionForNode(targetNode);

                if (turnSuspendedSourceSection != null && turnSuspendedSourceSection.equals(targetSourceSection)) {
                    currentTurn = true;
                    // adjust the stackCounter to reflect that this is where we want to be
                    stackCounter = 0;
                    activeFrame = true;
                }
            }

            boolean active = activeFrame &&
                            targetNode.getParent() == context.getInstrumentedNode().getParent() &&
                            stepConfig.matches(session, context, suspendAnchor) &&
                            currentTurn;

            return active;
        }

        /**
         * Return the source section of the turn where this node belongs. A turn is represented by
         * the processing of an asynchronous message by an actor. This method checks if the given
         * node is located in a turn based on the selector of the message that was sent. Note:
         * comparison using the source section is not possible at the moment.
         *
         * @param node
         * @return
         */
        private SourceSection getTurnSourceSectionForNode(Node node) {
            if (node == null) {
                return null;
            }

            if (node instanceof RootNode) { // refers to the method
                String rootNodeName = ((RootNode) node).getName();

                // compare by the selector of the message
                if (rootNodeName.equals(this.messageSelector)) {
                    // node method corresponds to the turn executed
                    return node.getSourceSection();
                } else {
                    // node method belongs to synchronous call
                    // return source section of the corresponding turn for this node
                    Node callingNode = getCallingMethodForRootNode(node);
                    return getTurnSourceSectionForNode(callingNode);
                }

            } else {
                if (node.getParent() instanceof WrapperNode) {
                    return getTurnSourceSectionForNode(node.getParent().getParent()); // refers to
                                                                                      // the child
                                                                                      // of the
                                                                                      // proxy node
                } else {
                    return getTurnSourceSectionForNode(node.getParent());
                }
            }
        }

        /**
         * Return the source section of the method where this node is declared.
         *
         * @param node
         * @return
         */
        private SourceSection getMethodSourceSectionForNode(Node node) {
            if (node instanceof RootNode) { // refers to the method
                return node.getSourceSection();

            } else {
                if (node.getParent() instanceof WrapperNode) {
                    return getMethodSourceSectionForNode(node.getParent().getParent()); // refers to
                                                                                        // the child
                                                                                        // of the
                                                                                        // wrapper
                                                                                        // node
                } else {
                    return getMethodSourceSectionForNode(node.getParent());
                }
            }
        }

        /**
         * Get the node corresponding to the parent method call where the node is declared.
         *
         * @param node
         * @return
         */
        private Node getCallingMethodForRootNode(Node node) {
            assert node instanceof RootNode;
            boolean isNonMethodCall = false;
            SourceSection sourceSection = node.getSourceSection();

            if (sourceSection == null) { // Block on: do: primitive
                return rootNodeFrames.get(rootNodeFrames.size() - 2); // return Platform>>start node
            }

            int index = 0;
            for (RootNode frame : rootNodeFrames) {
                index++;
                if (frame.getSourceSection() == null) {
                    return null;
                }

                if (frame.getSourceSection().equals(sourceSection) || isNonMethodCall) {
                    // if the frame does not corresponds to a method call we need to check for the
                    // next frame
                    RootNode nextFrame = rootNodeFrames.get(index);
                    if (nextFrame != null && nextFrame.getName() != null) { // frame corresponding
                                                                            // to executor root node
                        if (nextFrame.getName().contains(">>#λ")) { // TODO check if this condition
                                                                    // is sufficient?
                            isNonMethodCall = true;
                        } else {
                            break;
                        }
                    }
                }
            }

            if (index > 0) {
                return rootNodeFrames.get(index);
            } else {
                return null;
            }
        }

        @Override
        boolean isDone() {
            return super.isDone();
        }

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            return isActive(context, suspendAnchor);
        }
    }

    static final class Unwind extends SteppingStrategy {

        private final int depth; // Negative depth
        private final DebugValue returnValue;
        private int stackCounter;
        ThreadDeath unwind;

        Unwind(int depth, DebugValue returnValue) {
            this.depth = -depth;
            this.returnValue = returnValue;
        }

        @Override
        void initialize(SuspendedContext context, SuspendAnchor suspendAnchor) {
            // We're entered already, we'll be called on exit once before unwind.
            this.stackCounter = 1;
        }

        @Override
        void notifyCallEntry() {
            stackCounter++;
        }

        @Override
        void notifyCallExit() {
            stackCounter--;
        }

        @Override
        Object notifyOnUnwind() {
            if (depth == stackCounter) {
                return returnValue != null ? returnValue.get() : ProbeNode.UNWIND_ACTION_REENTER;
            } else {
                return null;
            }
        }

        @Override
        boolean isActive(EventContext context, SuspendAnchor suspendAnchor) {
            return SuspendAnchor.BEFORE == suspendAnchor;
        }

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            return true;
        }

        @Override
        boolean isUnwind() {
            return true;
        }

        @Override
        boolean isStopAfterCall() {
            return false;
        }

        @Override
        public String toString() {
            return String.format("REENTER(stackCounter=%s, depth=%s)", stackCounter, depth);
        }

    }

    static final class ComposedStrategy extends SteppingStrategy {

        private final SteppingStrategy first;
        private SteppingStrategy last;
        private SteppingStrategy current;

        private ComposedStrategy(SteppingStrategy strategy1, SteppingStrategy strategy2) {
            strategy1.next = strategy2;
            first = strategy1;
            current = first;
            last = strategy2;
        }

        @Override
        void initialize(SuspendedContext contex, SuspendAnchor suspendAnchor) {
            assert current == first;
            current.initialize(contex, suspendAnchor);
        }

        @Override
        void notifyCallEntry() {
            current.notifyCallEntry();
        }

        @Override
        void notifyCallExit() {
            current.notifyCallExit();
        }

        @Override
        void notifyNodeEntry(EventContext context) {
            current.notifyNodeEntry(context);
        }

        @Override
        void notifyNodeExit(EventContext context) {
            current.notifyNodeExit(context);
        }

        @Override
        boolean isStopAfterCall() {
            return current.isStopAfterCall();
        }

        @Override
        boolean isActive(EventContext context, SuspendAnchor suspendAnchor) {
            return current.isActive(context, suspendAnchor);
        }

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            boolean hit = current.step(steppingSession, context, suspendAnchor);
            if (hit) {
                if (current == last) {
                    return true;
                } else {
                    current = current.next;
                    current.initialize(SuspendedContext.create(context, steppingSession.getDebugger().getEnv()), suspendAnchor);
                }
            }
            return false;
        }

        @Override
        void consume() {
            assert current == last;
            last.consume();
        }

        @Override
        boolean isConsumed() {
            assert current == last;
            return last.isConsumed();
        }

        @Override
        boolean isDone() {
            if (current == last) {
                return last.isDone();
            }
            return false;
        }

        @Override
        boolean isKill() {
            if (current == last) {
                return last.isKill();
            }
            return false;
        }

        @Override
        boolean isComposable() {
            return true;
        }

        @Override
        synchronized void add(SteppingStrategy nextStrategy) {
            last.next = nextStrategy;
            last = nextStrategy;
        }

        @Override
        public String toString() {
            StringBuilder all = new StringBuilder();
            for (SteppingStrategy s = first; s.next != null; s = s.next) {
                if (all.length() > 0) {
                    all.append(", ");
                }
                all.append(s.toString());
            }

            return "COMPOSED(" + all + ")";
        }
    }
}
