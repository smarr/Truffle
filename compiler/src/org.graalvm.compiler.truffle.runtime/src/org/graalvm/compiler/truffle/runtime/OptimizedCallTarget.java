/*
 * Copyright (c) 2013, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.truffle.runtime;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.graalvm.compiler.truffle.common.CompilableTruffleAST;
import org.graalvm.compiler.truffle.common.TruffleCallNode;
import org.graalvm.compiler.truffle.options.PolyglotCompilerOptions;
import org.graalvm.compiler.truffle.options.PolyglotCompilerOptions.ExceptionAction;
import org.graalvm.compiler.truffle.runtime.OptimizedOSRLoopNode.OSRRootNode;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionValues;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerOptions;
import com.oracle.truffle.api.OptimizationFailedException;
import com.oracle.truffle.api.ReplaceObserver;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.impl.DefaultCompilerOptions;
import com.oracle.truffle.api.nodes.BlockNode;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.EncapsulatingNodeReference;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.NodeVisitor;
import com.oracle.truffle.api.nodes.RootNode;

import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.SpeculationLog;

/**
 * Call target that is optimized by Graal upon surpassing a specific invocation threshold. That is,
 * this is a Truffle AST that can be optimized via partial evaluation and compiled to machine code.
 *
 * Note: {@code PartialEvaluator} looks up this class and a number of its methods by name.
 *
 * The end-goal of executing a {@link OptimizedCallTarget} is executing its root node. The following
 * call-graph shows all the paths that can be taken from calling a call target (through all the
 * public <code>call*</code> methods) to the {@linkplain #executeRootNode(VirtualFrame) execution of
 * the root node} depending on the type of call.
 *
 * <pre>
 *              GraalRuntimeSupport#callProfiled                    GraalRuntimeSupport#callInlined
 *                                |                                               |
 *                                |                                               V
 *  PUBLIC   call -> callIndirect | callOSR   callDirect <================> callInlined
 *                           |  +-+    |           |             ^                |
 *                           |  |  +---+           |     substituted by the       |
 *                           V  V  V               |     compiler if inlined      |
 *  PROTECTED               doInvoke <-------------+                              |
 *                             |                                                  |
 *                             | <= Jump to installed code                        |
 *                             V                                                  |
 *  PROTECTED              callBoundary                                           |
 *                             |                                                  |
 *                             | <= Tail jump to installed code in Int.           |
 *                             V                                                  |
 *  PROTECTED           profiledPERoot                                            |
 *                             |                                                  |
 *  PRIVATE                    +----------> executeRootNode <---------------------+
 *                                                 |
 *                                                 V
 *                                         rootNode.execute()
 * </pre>
 */
@SuppressWarnings({"deprecation", "hiding"})
public abstract class OptimizedCallTarget implements CompilableTruffleAST, RootCallTarget, ReplaceObserver {

    private static final String NODE_REWRITING_ASSUMPTION_NAME = "nodeRewritingAssumption";
    static final String EXECUTE_ROOT_NODE_METHOD_NAME = "executeRootNode";
    private static final AtomicReferenceFieldUpdater<OptimizedCallTarget, SpeculationLog> SPECULATION_LOG_UPDATER = AtomicReferenceFieldUpdater.newUpdater(OptimizedCallTarget.class,
                    SpeculationLog.class, "speculationLog");
    private static final AtomicReferenceFieldUpdater<OptimizedCallTarget, Assumption> NODE_REWRITING_ASSUMPTION_UPDATER = AtomicReferenceFieldUpdater.newUpdater(OptimizedCallTarget.class,
                    Assumption.class, "nodeRewritingAssumption");
    private static final WeakReference<OptimizedDirectCallNode> NO_CALL = new WeakReference<>(null);
    private static final WeakReference<OptimizedDirectCallNode> MULTIPLE_CALLS = null;
    private static final String SPLIT_LOG_FORMAT = "[poly-event] %-70s %s";

    /** The AST to be executed when this call target is called. */
    private final RootNode rootNode;

    /** Whether this call target was cloned, compiled or called. */
    @CompilationFinal protected volatile boolean initialized;

    /**
     * Was the target already dequeued due to inlining. We keep track of this to prevent
     * continuously dequeuing the target for single caller when the single caller itself has
     * multiple callers.
     */
    private volatile boolean dequeueInlined = false;

    /**
     * Set if compilation failed or was ignored. Reset by TruffleFeature after boot image
     * generation.
     */
    private volatile boolean compilationFailed;

    /**
     * Timestamp when the call target was initialized e.g. used the first time. Reset by
     * TruffleFeature after boot image generation.
     */
    private volatile long initializedTimestamp;

    /**
     * When this field is not null, this {@link OptimizedCallTarget} is
     * {@linkplain #isSubmittedForCompilation() submited for compilation}.<br/>
     *
     * It is only set to non-null in {@link #compile(boolean)} in a synchronized block.
     *
     * It is only {@linkplain #resetCompilationTask() set to null} by the
     * {@linkplain CancellableCompileTask task} itself when: 1) The task is canceled before the
     * compilation has started, or 2) The compilation has finished (successfully or not). Canceling
     * the task after the compilation has started does not reset the task until the compilation
     * finishes.
     */
    private volatile CancellableCompileTask compilationTask;

    private volatile boolean needsSplit;

    /**
     * The engine data associated with this call target. Used to cache option lookups and to gather
     * engine specific statistics.
     */
    public final EngineData engine;

    /** Only set for a source CallTarget with a clonable RootNode. */
    private volatile RootNode uninitializedRootNode;

    /**
     * The speculation log to keep track of assumptions taken and failed for previous compialtions.
     */
    private volatile SpeculationLog speculationLog;

    /** Source target if this target was duplicated. */
    private final OptimizedCallTarget sourceCallTarget;

    /**
     * When this call target is inlined, the inlining {@link InstalledCode} registers this
     * assumption. It gets invalidated when a node rewrite in this call target is performed. This
     * ensures that all compiled methods that inline this call target are properly invalidated.
     */
    private volatile Assumption nodeRewritingAssumption;

    /**
     * Traversing the AST to cache non trivial nodes is expensive so we don't want to repeat it only
     * if the AST changes.
     */
    private volatile int cachedNonTrivialNodeCount = -1;

    /**
     * Number of known direct call sites of this call target. Used in splitting and inlinig
     * heuristics.
     */
    private volatile int callSitesKnown;

    private volatile String nameCache;
    private final int uninitializedNodeCount;

    private volatile WeakReference<OptimizedDirectCallNode> singleCallNode = NO_CALL;
    volatile List<OptimizedCallTarget> blockCompilations;

    protected OptimizedCallTarget(OptimizedCallTarget sourceCallTarget, RootNode rootNode) {
        assert sourceCallTarget == null || sourceCallTarget.sourceCallTarget == null : "Cannot create a clone of a cloned CallTarget";
        this.sourceCallTarget = sourceCallTarget;
        this.speculationLog = sourceCallTarget != null ? sourceCallTarget.getSpeculationLog() : null;
        this.rootNode = rootNode;
        this.engine = GraalTVMCI.getEngineData(rootNode);
        // Do not adopt children of OSRRootNodes; we want to preserve the parent of the LoopNode.
        this.uninitializedNodeCount = !(rootNode instanceof OSRRootNode) ? GraalRuntimeAccessor.NODES.adoptChildrenAndCount(rootNode) : -1;
        GraalRuntimeAccessor.NODES.setCallTarget(rootNode, this);
    }

    final Assumption getNodeRewritingAssumption() {
        Assumption assumption = nodeRewritingAssumption;
        if (assumption == null) {
            assumption = initializeNodeRewritingAssumption();
        }
        return assumption;
    }

    @Override
    public JavaConstant getNodeRewritingAssumptionConstant() {
        return runtime().forObject(getNodeRewritingAssumption());
    }

    @Override
    public boolean isTrivial() {
        return GraalRuntimeAccessor.NODES.isTrivial(rootNode);
    }

    @Override
    public int getNumberOfArguments() {
        throw new IllegalAccessError();
    }

    /**
     * We intentionally do not synchronize here since as it's not worth the sync costs.
     */
    @Override
    public void dequeueInlined() {
        if (!dequeueInlined) {
            dequeueInlined = true;
            cancelCompilation("Target inlined into only caller");
        }
    }

    /**
     * @return an existing or the newly initialized node rewriting assumption.
     */
    private Assumption initializeNodeRewritingAssumption() {
        Assumption newAssumption = runtime().createAssumption(
                        !getOptionValue(PolyglotCompilerOptions.TraceAssumptions) ? NODE_REWRITING_ASSUMPTION_NAME : NODE_REWRITING_ASSUMPTION_NAME + " of " + rootNode);
        if (NODE_REWRITING_ASSUMPTION_UPDATER.compareAndSet(this, null, newAssumption)) {
            return newAssumption;
        } else {
            // if CAS failed, assumption is already initialized; cannot be null after that.
            return Objects.requireNonNull(nodeRewritingAssumption);
        }
    }

    /**
     * Invalidate node rewriting assumption iff it has been initialized.
     */
    private void invalidateNodeRewritingAssumption() {
        Assumption oldAssumption = NODE_REWRITING_ASSUMPTION_UPDATER.getAndUpdate(this, new UnaryOperator<Assumption>() {
            @Override
            public Assumption apply(Assumption prev) {
                return prev == null ? null : runtime().createAssumption(prev.getName());
            }
        });
        if (oldAssumption != null) {
            oldAssumption.invalidate();
        }
    }

    @Override
    public final RootNode getRootNode() {
        return rootNode;
    }

    @Override
    @TruffleBoundary
    public final Object call(Object... args) {
        // Use the encapsulating node as call site and clear it inside as we cross the call boundary
        EncapsulatingNodeReference encapsulating = EncapsulatingNodeReference.getCurrent();
        Node prev = encapsulating.set(null);
        try {
            return callIndirect(prev, args);
        } catch (Throwable t) {
            GraalRuntimeAccessor.LANGUAGE.onThrowable(prev, null, t, null);
            throw rethrow(t);
        } finally {
            encapsulating.set(prev);
        }
    }

    @Override
    @TruffleBoundary
    public final Object call1(Object arg1) {
        // Use the encapsulating node as call site and clear it inside as we cross the call boundary
        EncapsulatingNodeReference encapsulating = EncapsulatingNodeReference.getCurrent();
        Node prev = encapsulating.set(null);
        try {
            return call1Indirect(prev, arg1);
        } catch (Throwable t) {
            GraalRuntimeAccessor.LANGUAGE.onThrowable(prev, null, t, null);
            throw rethrow(t);
        } finally {
            encapsulating.set(prev);
        }
    }

    @Override
    @TruffleBoundary
    public final Object call2(Object arg1, Object arg2) {
        // Use the encapsulating node as call site and clear it inside as we cross the call boundary
        EncapsulatingNodeReference encapsulating = EncapsulatingNodeReference.getCurrent();
        Node prev = encapsulating.set(null);
        try {
            return call2Indirect(prev, arg1, arg2);
        } catch (Throwable t) {
            GraalRuntimeAccessor.LANGUAGE.onThrowable(prev, null, t, null);
            throw rethrow(t);
        } finally {
            encapsulating.set(prev);
        }
    }

    @Override
    @TruffleBoundary
    public final Object call3(Object arg1, Object arg2, Object arg3) {
        // Use the encapsulating node as call site and clear it inside as we cross the call boundary
        EncapsulatingNodeReference encapsulating = EncapsulatingNodeReference.getCurrent();
        Node prev = encapsulating.set(null);
        try {
            return call3Indirect(prev, arg1, arg2, arg3);
        } catch (Throwable t) {
            GraalRuntimeAccessor.LANGUAGE.onThrowable(prev, null, t, null);
            throw rethrow(t);
        } finally {
            encapsulating.set(prev);
        }
    }

    @Override
    @TruffleBoundary
    public final Object call4(Object arg1, Object arg2, Object arg3, Object arg4) {
        // Use the encapsulating node as call site and clear it inside as we cross the call boundary
        EncapsulatingNodeReference encapsulating = EncapsulatingNodeReference.getCurrent();
        Node prev = encapsulating.set(null);
        try {
            return call4Indirect(prev, arg1, arg2, arg3, arg4);
        } catch (Throwable t) {
            GraalRuntimeAccessor.LANGUAGE.onThrowable(prev, null, t, null);
            throw rethrow(t);
        } finally {
            encapsulating.set(prev);
        }
    }

    // Note: {@code PartialEvaluator} looks up this method by name and signature.
    public Object callIndirect(Node location, Object... args) {
        try {
            return doInvoke(args);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public Object call1Indirect(Node location, Object arg1) {
        try {
            return doInvoke1(arg1);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public Object call2Indirect(Node location, Object arg1, Object arg2) {
        try {
            return doInvoke2(arg1, arg2);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public Object call3Indirect(Node location, Object arg1, Object arg2, Object arg3) {
        try {
            return doInvoke3(arg1, arg2, arg3);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public Object call4Indirect(Node location, Object arg1, Object arg2, Object arg3, Object arg4) {
        try {
            return doInvoke4(arg1, arg2, arg3, arg4);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    // Note: {@code PartialEvaluator} looks up this method by name and signature.
    public final Object callDirect(Node location, Object... args) {
        try {
            return doInvoke(args);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public final Object call1Direct(Node location, Object arg1) {
        try {
            return doInvoke1(arg1);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public final Object call2Direct(Node location, Object arg1, Object arg2) {
        try {
            return doInvoke2(arg1, arg2);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public final Object call3Direct(Node location, Object arg1, Object arg2, Object arg3) {
        try {
            return doInvoke3(arg1, arg2, arg3);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public final Object call4Direct(Node location, Object arg1, Object arg2, Object arg3, Object arg4) {
        try {
            return doInvoke4(arg1, arg2, arg3, arg4);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    private static boolean keepAlive(@SuppressWarnings("unused") Object o) {
        return true;
    }

    public final Object callOSR(Object... args) {
        return doInvoke(args);
    }

    // Note: {@code PartialEvaluator} looks up this method by name and signature.
    public final Object callInlined(Node location, Object... arguments) {
        try {
            ensureInitialized();
            return executeRootNode(createFrame(getRootNode().getFrameDescriptor(), arguments));
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public final Object call1Inlined(Node location, Object arg1) {
        try {
            ensureInitialized();
            return executeRootNode(createFrame(getRootNode().getFrameDescriptor(), arg1));
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public final Object call2Inlined(Node location, Object arg1, Object arg2) {
        try {
            ensureInitialized();
            return executeRootNode(createFrame(getRootNode().getFrameDescriptor(), arg1, arg2));
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public final Object call3Inlined(Node location, Object arg1, Object arg2, Object arg3) {
        try {
            ensureInitialized();
            return executeRootNode(createFrame(getRootNode().getFrameDescriptor(), arg1, arg2, arg3));
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    public final Object call4Inlined(Node location, Object arg1, Object arg2, Object arg3, Object arg4) {
        try {
            ensureInitialized();
            return executeRootNode(createFrame(getRootNode().getFrameDescriptor(), arg1, arg2, arg3, arg4));
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert keepAlive(location);
        }
    }

    /**
     * Overridden by SVM.
     */
    protected Object doInvoke(Object[] args) {
        return callBoundary(args);
    }

    protected Object doInvoke1(Object arg1) {
        return callBoundary1(arg1);
    }

    protected Object doInvoke2(Object arg1, Object arg2) {
        return callBoundary2(arg1, arg2);
    }

    protected Object doInvoke3(Object arg1, Object arg2, Object arg3) {
        return callBoundary3(arg1, arg2, arg3);
    }

    protected Object doInvoke4(Object arg1, Object arg2, Object arg3, Object arg4) {
        return callBoundary4(arg1, arg2, arg3, arg4);
    }

    @TruffleCallBoundary
    protected final Object callBoundary(Object[] args) {
        /*
         * Note this method compiles without any inlining or other optimizations. It is therefore
         * important that this method stays small. It is compiled as a special stub that calls into
         * the optimized code or if the call target is not yet optimized calls into profiledPERoot
         * directly. In order to avoid deoptimizations in this method it has optimizations disabled.
         * Any additional code here will likely have significant impact on the intepreter call
         * performance.
         */
        if (interpreterCall()) {
            return doInvoke(args);
        }
        return profiledPERoot(args);
    }

    @TruffleCallBoundary
    protected final Object callBoundary1(Object arg1) {
        /*
         * Note this method compiles without any inlining or other optimizations. It is therefore
         * important that this method stays small. It is compiled as a special stub that calls into
         * the optimized code or if the call target is not yet optimized calls into profiledPERoot
         * directly. In order to avoid deoptimizations in this method it has optimizations disabled.
         * Any additional code here will likely have significant impact on the intepreter call
         * performance.
         */
        if (interpreterCall()) {
            return doInvoke1(arg1);
        }
        return profiledPERoot1(arg1);
    }

    @TruffleCallBoundary
    protected final Object callBoundary2(Object arg1, Object arg2) {
        /*
         * Note this method compiles without any inlining or other optimizations. It is therefore
         * important that this method stays small. It is compiled as a special stub that calls into
         * the optimized code or if the call target is not yet optimized calls into profiledPERoot
         * directly. In order to avoid deoptimizations in this method it has optimizations disabled.
         * Any additional code here will likely have significant impact on the intepreter call
         * performance.
         */
        if (interpreterCall()) {
            return doInvoke2(arg1, arg2);
        }
        return profiledPERoot2(arg1, arg2);
    }

    @TruffleCallBoundary
    protected final Object callBoundary3(Object arg1, Object arg2, Object arg3) {
        /*
         * Note this method compiles without any inlining or other optimizations. It is therefore
         * important that this method stays small. It is compiled as a special stub that calls into
         * the optimized code or if the call target is not yet optimized calls into profiledPERoot
         * directly. In order to avoid deoptimizations in this method it has optimizations disabled.
         * Any additional code here will likely have significant impact on the intepreter call
         * performance.
         */
        if (interpreterCall()) {
            return doInvoke3(arg1, arg2, arg3);
        }
        return profiledPERoot3(arg1, arg2, arg3);
    }

    @TruffleCallBoundary
    protected final Object callBoundary4(Object arg1, Object arg2, Object arg3, Object arg4) {
        /*
         * Note this method compiles without any inlining or other optimizations. It is therefore
         * important that this method stays small. It is compiled as a special stub that calls into
         * the optimized code or if the call target is not yet optimized calls into profiledPERoot
         * directly. In order to avoid deoptimizations in this method it has optimizations disabled.
         * Any additional code here will likely have significant impact on the intepreter call
         * performance.
         */
        if (interpreterCall()) {
            return doInvoke4(arg1, arg2, arg3, arg4);
        }
        return profiledPERoot4(arg1, arg2, arg3, arg4);
    }

    private boolean interpreterCall() {
        ensureInitialized();
        return false;
    }

    // Note: {@code PartialEvaluator} looks up this method by name and signature.
    protected final Object profiledPERoot(Object[] originalArguments) {
        return executeRootNode(createFrame(getRootNode().getFrameDescriptor(), originalArguments));
    }

    // Note: {@code PartialEvaluator} looks up this method by name and signature.
    protected final Object profiledPERoot1(Object originalArg1) {
        return executeRootNode(createFrame(getRootNode().getFrameDescriptor(), originalArg1));
    }

    // Note: {@code PartialEvaluator} looks up this method by name and signature.
    protected final Object profiledPERoot2(Object originalArg1, Object originalArg2) {
        return executeRootNode(createFrame(getRootNode().getFrameDescriptor(), originalArg1, originalArg2));
    }

    // Note: {@code PartialEvaluator} looks up this method by name and signature.
    protected final Object profiledPERoot3(Object originalArg1, Object originalArg2, Object originalArg3) {
        return executeRootNode(createFrame(getRootNode().getFrameDescriptor(), originalArg1, originalArg2, originalArg3));
    }

    // Note: {@code PartialEvaluator} looks up this method by name and signature.
    protected final Object profiledPERoot4(Object originalArg1, Object originalArg2, Object originalArg3, Object originalArg4) {
        return executeRootNode(createFrame(getRootNode().getFrameDescriptor(), originalArg1, originalArg2, originalArg3, originalArg4));
    }

    private Object executeRootNode(VirtualFrame frame) {
        final boolean inCompiled = CompilerDirectives.inCompilationRoot();
        try {
            return rootNode.execute(frame);
        } catch (ControlFlowException t) {
            throw rethrow(t);
        } catch (Throwable t) {
            GraalRuntimeAccessor.LANGUAGE.onThrowable(null, this, t, frame);
            throw rethrow(t);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert frame != null && this != null;
            if (CompilerDirectives.inInterpreter() && inCompiled) {
                notifyDeoptimized(frame);
            }
        }
    }

    private void notifyDeoptimized(VirtualFrame frame) {
        runtime().getListener().onCompilationDeoptimized(this, frame);
    }

    static GraalTruffleRuntime runtime() {
        return (GraalTruffleRuntime) Truffle.getRuntime();
    }

    // This should be private but can't be due to SVM bug.
    public final void ensureInitialized() {
        if (!initialized) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            initialize(true);
        }
    }

    public final boolean isInitialized() {
        return initialized;
    }

    private synchronized void initialize(boolean validate) {
        if (!initialized) {
            if (sourceCallTarget == null && rootNode.isCloningAllowed() && !GraalRuntimeAccessor.NODES.isCloneUninitializedSupported(rootNode)) {
                // We are the source CallTarget, so make a copy.
                this.uninitializedRootNode = NodeUtil.cloneNode(rootNode);
            }
            GraalRuntimeAccessor.INSTRUMENT.onFirstExecution(getRootNode(), validate);
            if (engine.callTargetStatistics) {
                this.initializedTimestamp = System.nanoTime();
            } else {
                this.initializedTimestamp = 0L;
            }
            initialized = true;
        }
    }

    public final OptionValues getOptionValues() {
        return engine.engineOptions;
    }

    public final <T> T getOptionValue(OptionKey<T> key) {
        return getOptionValues().get(key);
    }

    /**
     * Returns <code>true</code> if this target can be compiled in principle, else
     * <code>false</code>.
     */
    final boolean acceptForCompilation() {
        return engine.acceptForCompilation(getRootNode());
    }

    final boolean isCompilationFailed() {
        return compilationFailed;
    }

    /**
     * Returns <code>true</code> if the call target was already compiled or was compiled
     * synchronously. Returns <code>false</code> if compilation was not scheduled or is happening in
     * the background. Use {@link #isSubmittedForCompilation()} to find out whether it is submitted
     * for compilation.
     */
    public final boolean compile(boolean lastTierCompilation) {
        if (!needsCompile(lastTierCompilation)) {
            return true;
        }
        if (!isSubmittedForCompilation()) {
            if (!engine.acceptForCompilation(getRootNode())) {
                // do not try to compile again
                compilationFailed = true;
                return false;
            }

            CancellableCompileTask task = null;
            // Do not try to compile this target concurrently,
            // but do not block other threads if compilation is not asynchronous.
            synchronized (this) {
                if (!needsCompile(lastTierCompilation)) {
                    return true;
                }
                ensureInitialized();
                if (!isSubmittedForCompilation()) {
                    try {
                        assert compilationTask == null;
                        this.compilationTask = task = runtime().submitForCompilation(this, lastTierCompilation);
                    } catch (RejectedExecutionException e) {
                        return false;
                    }
                }
            }
            if (task != null) {
                runtime().getListener().onCompilationQueued(this);
                return maybeWaitForTask(task);
            }
        }
        return false;
    }

    public final boolean maybeWaitForTask(CancellableCompileTask task) {
        boolean mayBeAsynchronous = engine.backgroundCompilation;
        runtime().finishCompilation(this, task, mayBeAsynchronous);
        // not async compile and compilation successful
        return !mayBeAsynchronous && isValid();
    }

    public final boolean needsCompile(boolean isLastTierCompilation) {
        return !isValid() || (engine.multiTier && isLastTierCompilation && !isValidLastTier());
    }

    public final boolean isSubmittedForCompilation() {
        return compilationTask != null;
    }

    public final void waitForCompilation() {
        CancellableCompileTask task = compilationTask;
        if (task != null) {
            runtime().finishCompilation(this, task, false);
        }
    }

    boolean isCompiling() {
        return getCompilationTask() != null;
    }

    /**
     * Gets the address of the machine code for this call target. A non-zero return value denotes
     * the contiguous memory block containing the machine code but does not necessarily represent an
     * entry point for the machine code or even the address of executable instructions. This value
     * is only for informational purposes (e.g., use in a log message).
     */
    public abstract long getCodeAddress();

    /**
     * Determines if this call target has valid machine code that can be entered attached to it.
     */
    public abstract boolean isValid();

    /**
     * Determines if this call target has machine code that might still have live activations
     * attached to it.
     */
    public abstract boolean isAlive();

    /**
     * Determines if this call target has valid machine code attached to it, and that this code was
     * compiled in the last tier.
     */
    public abstract boolean isValidLastTier();

    /**
     * Invalidates this call target by invalidating any machine code attached to it.
     *
     * @param source the source object that caused the machine code to be invalidated. For example
     *            the source {@link Node} object. May be {@code null}.
     * @param reason a textual description of the reason why the machine code was invalidated. May
     *            be {@code null}.
     */
    public final boolean invalidate(Object source, CharSequence reason) {
        cachedNonTrivialNodeCount = -1;
        boolean invalidated = false;
        if (isAlive()) {
            invalidateCode();
            runtime().getListener().onCompilationInvalidated(this, source, reason);
            invalidated = true;
        }
        return cancelCompilation(reason) || invalidated;
    }

    final OptimizedCallTarget cloneUninitialized() {
        assert sourceCallTarget == null;
        ensureInitialized();
        RootNode clonedRoot;
        if (GraalRuntimeAccessor.NODES.isCloneUninitializedSupported(rootNode)) {
            assert uninitializedRootNode == null;
            clonedRoot = GraalRuntimeAccessor.NODES.cloneUninitialized(rootNode);
        } else {
            clonedRoot = NodeUtil.cloneNode(uninitializedRootNode);
        }
        return runtime().createClonedCallTarget(clonedRoot, this);
    }

    /**
     * Gets the speculation log used to collect all failed speculations in the compiled code for
     * this call target. Note that this may differ from the speculation log
     * {@linkplain CompilableTruffleAST#getCompilationSpeculationLog() used for compilation}.
     */
    public SpeculationLog getSpeculationLog() {
        if (speculationLog == null) {
            SPECULATION_LOG_UPDATER.compareAndSet(this, null, ((GraalTruffleRuntime) Truffle.getRuntime()).createSpeculationLog());
        }
        return speculationLog;
    }

    final void setSpeculationLog(SpeculationLog speculationLog) {
        this.speculationLog = speculationLog;
    }

    @Override
    public final JavaConstant asJavaConstant() {
        return GraalTruffleRuntime.getRuntime().forObject(this);
    }

    static <E extends Throwable> RuntimeException rethrow(Throwable ex) throws E {
        throw (E) ex;
    }

    @Override
    public final boolean isSameOrSplit(CompilableTruffleAST ast) {
        if (!(ast instanceof OptimizedCallTarget)) {
            return false;
        }
        OptimizedCallTarget other = (OptimizedCallTarget) ast;
        return this == other || this == other.sourceCallTarget || other == this.sourceCallTarget ||
                        (this.sourceCallTarget != null && other.sourceCallTarget != null && this.sourceCallTarget == other.sourceCallTarget);
    }

    @Override
    public boolean cancelCompilation(CharSequence reason) {
        if (!initialized) {
            /* no cancellation necessary if the call target was initialized */
            return false;
        }
        if (cancelAndResetCompilationTask()) {
            runtime().getListener().onCompilationDequeued(this, null, reason);
            return true;
        }
        return false;
    }

    private boolean cancelAndResetCompilationTask() {
        CancellableCompileTask task = this.compilationTask;
        if (task != null) {
            synchronized (this) {
                task = this.compilationTask;
                if (task != null) {
                    return task.cancel();
                }
            }
        }
        return false;
    }

    /**
     * Computes block compilation using {@link BlockNode} APIs. If no block node is used in the AST
     * or block node compilation is disabled then this method always returns <code>false</code>.
     */
    public final boolean computeBlockCompilations() {
        if (blockCompilations == null) {
            this.blockCompilations = OptimizedBlockNode.preparePartialBlockCompilations(this);
            if (!blockCompilations.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public final void onCompilationFailed(Supplier<String> serializedException, boolean silent, boolean bailout, boolean permanentBailout, boolean graphTooBig) {
        if (graphTooBig) {
            if (computeBlockCompilations()) {
                // retry compilation
                return;
            }
        }

        ExceptionAction action;
        if (bailout && !permanentBailout) {
            /*
             * Non-permanent bailouts are expected cases. A non-permanent bailout would be for
             * example class redefinition during code installation. As opposed to permanent
             * bailouts, non-permanent bailouts will trigger recompilation and are not considered a
             * failure state.
             */
            action = ExceptionAction.Silent;
        } else {
            compilationFailed = true;
            action = silent ? ExceptionAction.Silent : engine.compilationFailureAction;
        }
        if (action == ExceptionAction.Throw) {
            final InternalError error = new InternalError(serializedException.get());
            throw new OptimizationFailedException(error, this);
        }
        if (action.ordinal() >= ExceptionAction.Print.ordinal()) {
            GraalTruffleRuntime rt = runtime();
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("AST", getNonTrivialNodeCount());
            rt.logEvent(this, 0, "opt fail", toString(), properties, serializedException.get());
            if (action == ExceptionAction.ExitVM) {
                String reason;
                if (getOptionValue(PolyglotCompilerOptions.CompilationFailureAction) == ExceptionAction.ExitVM) {
                    reason = "engine.CompilationFailureAction=ExitVM";
                } else if (getOptionValue(PolyglotCompilerOptions.CompilationExceptionsAreFatal)) {
                    reason = "engine.CompilationExceptionsAreFatal=true";
                } else {
                    reason = "engine.PerformanceWarningsAreFatal=true";
                }
                log(String.format("Exiting VM due to %s", reason));
                System.exit(-1);
            }
        }
    }

    public final void log(String message) {
        runtime().log(this, message);
    }

    @Override
    public final int getKnownCallSiteCount() {
        return callSitesKnown;
    }

    public final OptimizedCallTarget getSourceCallTarget() {
        return sourceCallTarget;
    }

    @Override
    public final String getName() {
        CompilerAsserts.neverPartOfCompilation();
        String result = nameCache;
        if (result == null) {
            result = rootNode.toString();
            nameCache = result;
        }
        return result;
    }

    @Override
    public final String toString() {
        CompilerAsserts.neverPartOfCompilation();
        String superString = rootNode.toString();
        if (sourceCallTarget != null) {
            superString += " <split-" + Integer.toHexString(hashCode()) + ">";
        }
        return superString;
    }

    /**
     * Intrinsifiable compiler directive to tighten the type information for {@code args}.
     *
     * @param length the length of {@code args} that is guaranteed to be final at compile time
     */
    static final Object[] castArrayFixedLength(Object[] args, int length) {
        return args;
    }

    /**
     * Intrinsifiable compiler directive to tighten the type information for {@code value}.
     *
     * @param type the type the compiler should assume for {@code value}
     * @param condition the condition that guards the assumptions expressed by this directive
     * @param nonNull the nullness info the compiler should assume for {@code value}
     * @param exact if {@code true}, the compiler should assume exact type info
     */
    @SuppressWarnings({"unchecked"})
    static <T> T unsafeCast(Object value, Class<T> type, boolean condition, boolean nonNull, boolean exact) {
        return (T) value;
    }

    // TODO: find whether this is an issue for us
    /**
     * Intrinsifiable compiler directive for creating a frame.
     */
    public static VirtualFrame createFrame(FrameDescriptor descriptor, Object[] args) {
        return new FrameWithoutBoxing(descriptor, args);
    }

    public static VirtualFrame createFrame(FrameDescriptor descriptor, Object arg1) {
        return new FrameWithoutBoxing(descriptor, arg1);
    }

    public static VirtualFrame createFrame(FrameDescriptor descriptor, Object arg1, Object arg2) {
        return new FrameWithoutBoxing(descriptor, arg1, arg2);
    }

    public static VirtualFrame createFrame(FrameDescriptor descriptor, Object arg1, Object arg2, Object arg3) {
        return new FrameWithoutBoxing(descriptor, arg1, arg2, arg3);
    }

    public static VirtualFrame createFrame(FrameDescriptor descriptor, Object arg1, Object arg2, Object arg3, Object arg4) {
        return new FrameWithoutBoxing(descriptor, arg1, arg2, arg3, arg4);
    }

    @Override
    public final boolean nodeReplaced(Node oldNode, Node newNode, CharSequence reason) {
        CompilerAsserts.neverPartOfCompilation();
        invalidate(newNode, reason);
        /*
         * Notify compiled method that have inlined this call target that the tree changed. It also
         * ensures that compiled code that might be installed by currently running compilation task
         * that can no longer be cancelled is invalidated.
         */
        invalidateNodeRewritingAssumption();
        return false;
    }

    public final void accept(NodeVisitor visitor, TruffleInlining inlingDecision) {
        if (inlingDecision != null) {
            inlingDecision.accept(this, visitor);
        } else {
            getRootNode().accept(visitor);
        }
    }

    public final Iterable<Node> nodeIterable(TruffleInlining inliningDecision) {
        Iterator<Node> iterator = nodeIterator(inliningDecision);
        return () -> iterator;
    }

    public final Iterator<Node> nodeIterator(TruffleInlining inliningDecision) {
        Iterator<Node> iterator;
        if (inliningDecision != null) {
            iterator = inliningDecision.makeNodeIterator(this);
        } else {
            iterator = NodeUtil.makeRecursiveIterator(this.getRootNode());
        }
        return iterator;
    }

    @Override
    public final int getNonTrivialNodeCount() {
        if (cachedNonTrivialNodeCount == -1) {
            cachedNonTrivialNodeCount = calculateNonTrivialNodes(getRootNode());
        }
        return cachedNonTrivialNodeCount;
    }

    @Override
    public final int getCallCount() {
        return 0;
    }

    public final int getCallAndLoopCount() {
        return 0;
    }

    public final long getInitializedTimestamp() {
        return initializedTimestamp;
    }

    public static int calculateNonTrivialNodes(Node node) {
        NonTrivialNodeCountVisitor visitor = new NonTrivialNodeCountVisitor();
        node.accept(visitor);
        return visitor.nodeCount;
    }

    public final Map<String, Object> getDebugProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        GraalTruffleRuntimeListener.addASTSizeProperty(this, properties);
        String callsThresholdInInterpreter = String.format("%7d/%5d", getCallCount(), engine.callThresholdInInterpreter);
        String loopsThresholdInInterpreter = String.format("%7d/%5d", getCallAndLoopCount(), engine.callAndLoopThresholdInInterpreter);
        if (engine.multiTier) {
            if (isValidLastTier()) {
                String callsThresholdInFirstTier = String.format("%7d/%5d", getCallCount(), engine.callThresholdInFirstTier);
                String loopsThresholdInFirstTier = String.format("%7d/%5d", getCallCount(), engine.callAndLoopThresholdInFirstTier);
                properties.put("Tier", "Last");
                properties.put("Calls/Thres", callsThresholdInFirstTier);
                properties.put("CallsAndLoop/Thres", loopsThresholdInFirstTier);
            } else {
                properties.put("Tier", "First");
                properties.put("Calls/Thres", callsThresholdInInterpreter);
                properties.put("CallsAndLoop/Thres", loopsThresholdInInterpreter);
            }
        } else {
            properties.put("Calls/Thres", callsThresholdInInterpreter);
            properties.put("CallsAndLoop/Thres", loopsThresholdInInterpreter);
        }
        return properties;
    }

    @Override
    public final TruffleCallNode[] getCallNodes() {
        final List<OptimizedDirectCallNode> callNodes = new ArrayList<>();
        getRootNode().accept(new NodeVisitor() {
            @Override
            public boolean visit(Node node) {
                if (node instanceof OptimizedDirectCallNode) {
                    callNodes.add((OptimizedDirectCallNode) node);
                }
                return true;
            }
        });
        return callNodes.toArray(new TruffleCallNode[0]);
    }

    public final CompilerOptions getCompilerOptions() {
        final CompilerOptions options = rootNode.getCompilerOptions();
        if (options != null) {
            return options;
        }
        return DefaultCompilerOptions.INSTANCE;
    }

    /*
     * Profiling related code.
     */

    // region Arguments profiling

    // region Manual Arguments profiling

    // endregion

    // This should be private but can't be. GR-19397

    // endregion
    // region Return value profiling

    // endregion
    // region Exception profiling

    // endregion

    private static OptimizedAssumption createInvalidAssumption(String name) {
        OptimizedAssumption result = createValidAssumption(name);
        result.invalidate();
        return result;
    }

    private static OptimizedAssumption createValidAssumption(String name) {
        return (OptimizedAssumption) Truffle.getRuntime().createAssumption(name);
    }

    /*
     * Splitting related code.
     */

    public final boolean isSplit() {
        return sourceCallTarget != null;
    }

    public final OptimizedDirectCallNode getCallSiteForSplit() {
        if (isSplit()) {
            OptimizedDirectCallNode callNode = getSingleCallNode();
            assert callNode != null;
            return callNode;
        } else {
            return null;
        }
    }

    final int getUninitializedNodeCount() {
        assert uninitializedNodeCount >= 0;
        return uninitializedNodeCount;
    }

    private static final class NonTrivialNodeCountVisitor implements NodeVisitor {
        public int nodeCount;

        @Override
        public boolean visit(Node node) {
            if (!node.getCost().isTrivial()) {
                nodeCount++;
            }
            return true;
        }
    }

    @Override
    public final boolean equals(Object obj) {
        return obj == this;
    }

    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }

    final CancellableCompileTask getCompilationTask() {
        return compilationTask;
    }

    /**
     * This marks the end or cancellation of the compilation.
     *
     * Once the compilation has started it may only ever be called by the thread performing the
     * compilation, and after the compilation is completely done (either successfully or not
     * successfully).
     */
    final synchronized void resetCompilationTask() {
        /*
         * We synchronize because this is called from the compilation threads so we want to make
         * sure we have finished setting the compilationTask in #compile. Otherwise
         * `this.compilationTask = null` might run before then the field is set in #compile and this
         * will get stuck in a "compiling" state.
         */
        assert this.compilationTask != null;
        this.compilationTask = null;
    }

    @SuppressFBWarnings(value = "VO_VOLATILE_INCREMENT", justification = "All increments and decrements are synchronized.")
    final synchronized void addDirectCallNode(OptimizedDirectCallNode directCallNode) {
        Objects.requireNonNull(directCallNode);
        WeakReference<OptimizedDirectCallNode> nodeRef = singleCallNode;
        if (nodeRef != MULTIPLE_CALLS) {
            // we only remember at most one call site
            if (nodeRef == NO_CALL) {
                singleCallNode = new WeakReference<>(directCallNode);
            } else if (nodeRef.get() == directCallNode) {
                // nothing to do same call site
                return;
            } else {
                singleCallNode = MULTIPLE_CALLS;
            }
        }
        callSitesKnown++;
    }

    @SuppressFBWarnings(value = "VO_VOLATILE_INCREMENT", justification = "All increments and decrements are synchronized.")
    final synchronized void removeDirectCallNode(OptimizedDirectCallNode directCallNode) {
        Objects.requireNonNull(directCallNode);
        WeakReference<OptimizedDirectCallNode> nodeRef = singleCallNode;
        if (nodeRef != MULTIPLE_CALLS) {
            // we only remember at most one call site
            if (nodeRef == NO_CALL) {
                // nothing to do
                return;
            } else if (nodeRef.get() == directCallNode) {
                // reset if its the only call site
                singleCallNode = NO_CALL;
            } else {
                singleCallNode = MULTIPLE_CALLS;
            }
        }
        callSitesKnown--;
    }

    public final boolean isSingleCaller() {
        WeakReference<OptimizedDirectCallNode> nodeRef = singleCallNode;
        if (nodeRef != null) {
            return nodeRef.get() != null;
        }
        return false;
    }

    public final OptimizedDirectCallNode getSingleCallNode() {
        WeakReference<OptimizedDirectCallNode> nodeRef = singleCallNode;
        if (nodeRef != null) {
            return nodeRef.get();
        }
        return null;
    }

    final boolean isNeedsSplit() {
        return needsSplit;
    }

    final void polymorphicSpecialize(Node source) {
        List<Node> toDump = null;
        if (engine.splittingDumpDecisions) {
            toDump = new ArrayList<>();
            pullOutParentChain(source, toDump);
        }
        logPolymorphicEvent(0, "Polymorphic event! Source:", source);
        this.maybeSetNeedsSplit(0, toDump);
    }

    public final void resetNeedsSplit() {
        needsSplit = false;
    }

    private boolean maybeSetNeedsSplit(int depth, List<Node> toDump) {
        final OptimizedDirectCallNode onlyCaller = getSingleCallNode();
        if (depth > engine.splittingMaxPropagationDepth || needsSplit || callSitesKnown == 0 || getCallCount() == 1) {
            logEarlyReturn(depth, callSitesKnown);
            return needsSplit;
        }
        if (onlyCaller != null) {
            final RootNode callerRootNode = onlyCaller.getRootNode();
            if (callerRootNode != null && callerRootNode.getCallTarget() != null) {
                final OptimizedCallTarget callerTarget = (OptimizedCallTarget) callerRootNode.getCallTarget();
                if (engine.splittingDumpDecisions) {
                    pullOutParentChain(onlyCaller, toDump);
                }
                logPolymorphicEvent(depth, "One caller! Analysing parent.");
                if (callerTarget.maybeSetNeedsSplit(depth + 1, toDump)) {
                    logPolymorphicEvent(depth, "Set needs split to true via parent");
                    needsSplit = true;
                }
            }
        } else {
            logPolymorphicEvent(depth, "Set needs split to true");
            needsSplit = true;
            maybeDump(toDump);
        }

        logPolymorphicEvent(depth, "Return:", needsSplit);
        return needsSplit;
    }

    private void logEarlyReturn(int depth, int numberOfKnownCallNodes) {
        if (engine.splittingTraceEvents) {
            logPolymorphicEvent(depth, "Early return: " + needsSplit + " callCount: " + getCallCount() + ", numberOfKnownCallNodes: " + numberOfKnownCallNodes);
        }
    }

    private void logPolymorphicEvent(int depth, String message) {
        logPolymorphicEvent(depth, message, null);
    }

    private void logPolymorphicEvent(int depth, String message, Object arg) {
        if (engine.splittingTraceEvents) {
            final String indent = new String(new char[depth]).replace("\0", "  ");
            final String argString = (arg == null) ? "" : " " + arg;
            log(String.format(SPLIT_LOG_FORMAT, indent + message + argString, this.toString()));
        }
    }

    private void maybeDump(List<Node> toDump) {
        if (engine.splittingDumpDecisions) {
            final List<OptimizedDirectCallNode> callers = new ArrayList<>();
            OptimizedDirectCallNode callNode = getSingleCallNode();
            if (callNode != null) {
                callers.add(callNode);
            }
            PolymorphicSpecializeDump.dumpPolymorphicSpecialize(this, toDump);
        }
    }

    private static void pullOutParentChain(Node node, List<Node> toDump) {
        Node rootNode = node;
        while (rootNode.getParent() != null) {
            toDump.add(rootNode);
            rootNode = rootNode.getParent();
        }
        toDump.add(rootNode);
    }

    final void setNonTrivialNodeCount(int nonTrivialNodeCount) {
        this.cachedNonTrivialNodeCount = nonTrivialNodeCount;
    }

    public final boolean prepareForAOT() {
        if (isInitialized()) {
            throw new IllegalStateException("Cannot prepare for AOT if call target is already initialized.");
        }
        return true;
    }
}
