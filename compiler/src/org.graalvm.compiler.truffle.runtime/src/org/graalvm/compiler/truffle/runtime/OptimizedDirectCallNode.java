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

import org.graalvm.compiler.truffle.common.TruffleCallNode;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerOptions;
import com.oracle.truffle.api.impl.DefaultCompilerOptions;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * A call node with a constant {@link CallTarget} that can be optimized by Graal.
 *
 * Note: {@code PartialEvaluator} looks up this class and a number of its methods by name.
 */
@NodeInfo
public final class OptimizedDirectCallNode extends DirectCallNode implements TruffleCallNode {

    private boolean inliningForced;
    @CompilationFinal private Class<? extends Throwable> exceptionProfile;
    @CompilationFinal private OptimizedCallTarget splitCallTarget;

    /*
     * Should be instantiated with the runtime.
     */
    OptimizedDirectCallNode(OptimizedCallTarget target) {
        super(target);
        assert target.getSourceCallTarget() == null;
    }

    @Override
    public Object call(Object... arguments) {
        OptimizedCallTarget target = (OptimizedCallTarget) callTarget;
        try {
            return target.callDirect(this, arguments);
        } catch (Throwable t) {
            GraalRuntimeAccessor.LANGUAGE.onThrowable(this, null, t, null);
            throw OptimizedCallTarget.rethrow(t);
        }
    }

    @Override
    public Object call1(Object arg1) {
        OptimizedCallTarget target = (OptimizedCallTarget) callTarget;
        try {
            return target.call1Direct(this, arg1);
        } catch (Throwable t) {
            GraalRuntimeAccessor.LANGUAGE.onThrowable(this, null, t, null);
            throw OptimizedCallTarget.rethrow(t);
        }
    }

    @Override
    public Object call2(Object arg1, Object arg2) {
        OptimizedCallTarget target = (OptimizedCallTarget) callTarget;
        try {
            return target.call2Direct(this, arg1, arg2);
        } catch (Throwable t) {
            GraalRuntimeAccessor.LANGUAGE.onThrowable(this, null, t, null);
            throw OptimizedCallTarget.rethrow(t);
        }
    }

    @Override
    public Object call3(Object arg1, Object arg2, Object arg3) {
        OptimizedCallTarget target = (OptimizedCallTarget) callTarget;
        try {
            return target.call3Direct(this, arg1, arg2, arg3);
        } catch (Throwable t) {
            GraalRuntimeAccessor.LANGUAGE.onThrowable(this, null, t, null);
            throw OptimizedCallTarget.rethrow(t);
        }
    }

    @Override
    public Object call4(Object arg1, Object arg2, Object arg3, Object arg4) {
        OptimizedCallTarget target = (OptimizedCallTarget) callTarget;
        try {
            return target.call4Direct(this, arg1, arg2, arg3, arg4);
        } catch (Throwable t) {
            GraalRuntimeAccessor.LANGUAGE.onThrowable(this, null, t, null);
            throw OptimizedCallTarget.rethrow(t);
        }
    }

    @Override
    public boolean isInlinable() {
        CompilerAsserts.neverPartOfCompilation();
        return true;
    }

    @Override
    public void forceInlining() {
        CompilerAsserts.neverPartOfCompilation();
        inliningForced = true;
    }

    @Override
    public boolean isInliningForced() {
        CompilerAsserts.neverPartOfCompilation();
        return inliningForced;
    }

    @Override
    public boolean isCallTargetCloningAllowed() {
        return getCallTarget().getRootNode().isCloningAllowed();
    }

    @Override
    public OptimizedCallTarget getCallTarget() {
        return (OptimizedCallTarget) super.getCallTarget();
    }

    @Override
    public int getCallCount() {
        return 0;
    }

    public CompilerOptions getCompilerOptions() {
        RootNode rootNode = getRootNode();
        return rootNode != null ? rootNode.getCompilerOptions() : DefaultCompilerOptions.INSTANCE;
    }

    @Override
    public OptimizedCallTarget getCurrentCallTarget() {
        return (OptimizedCallTarget) super.getCurrentCallTarget();
    }

    public int getKnownCallSiteCount() {
        return getCurrentCallTarget().getKnownCallSiteCount();
    }

    @Override
    public OptimizedCallTarget getClonedCallTarget() {
        return splitCallTarget;
    }

    /** Used by the splitting strategy to install new targets. */
    void split() {
        CompilerAsserts.neverPartOfCompilation();

        // Synchronize with atomic() as replace() also takes the same lock
        // and we only want to take one lock to avoid deadlocks.
        atomic(() -> {
            if (splitCallTarget != null) {
                return;
            }

            assert isCallTargetCloningAllowed();
            OptimizedCallTarget currentTarget = getCallTarget();

            OptimizedCallTarget splitTarget = getCallTarget().cloneUninitialized();
            currentTarget.removeDirectCallNode(this);
            splitTarget.addDirectCallNode(this);
            assert splitTarget.getCallSiteForSplit() == this;

            if (getParent() != null) {
                // dummy replace to report the split, irrelevant if this node is not adopted
                replace(this, "Split call node");
            }
            splitCallTarget = splitTarget;
            OptimizedCallTarget.runtime().getListener().onCompilationSplit(this);
        });
    }

    @Override
    public boolean cloneCallTarget() {
        TruffleSplittingStrategy.forceSplitting(this);
        return true;
    }
}
