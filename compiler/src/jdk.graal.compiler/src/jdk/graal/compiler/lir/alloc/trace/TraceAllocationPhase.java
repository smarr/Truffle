/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.lir.alloc.trace;

import jdk.graal.compiler.core.common.alloc.RegisterAllocationConfig;
import jdk.graal.compiler.core.common.alloc.Trace;
import jdk.graal.compiler.core.common.alloc.TraceBuilderResult;
import jdk.graal.compiler.debug.CounterKey;
import jdk.graal.compiler.debug.DebugCloseable;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.MemUseTrackerKey;
import jdk.graal.compiler.debug.TimerKey;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.gen.MoveFactory;
import jdk.graal.compiler.lir.phases.LIRPhase;
import jdk.graal.compiler.lir.phases.LIRPhase.LIRPhaseStatistics;

import jdk.vm.ci.code.TargetDescription;

public abstract class TraceAllocationPhase<C extends TraceAllocationPhase.TraceAllocationContext> {

    public static class TraceAllocationContext {
        public final MoveFactory spillMoveFactory;
        public final RegisterAllocationConfig registerAllocationConfig;
        public final TraceBuilderResult resultTraces;
        public final GlobalLivenessInfo livenessInfo;

        public TraceAllocationContext(MoveFactory spillMoveFactory, RegisterAllocationConfig registerAllocationConfig, TraceBuilderResult resultTraces, GlobalLivenessInfo livenessInfo) {
            this.spillMoveFactory = spillMoveFactory;
            this.registerAllocationConfig = registerAllocationConfig;
            this.resultTraces = resultTraces;
            this.livenessInfo = livenessInfo;
        }
    }

    /**
     * Records time spent within {@link #apply}.
     */
    private final TimerKey timer;

    /**
     * Records memory usage within {@link #apply}.
     */
    private final MemUseTrackerKey memUseTracker;

    /**
     * Records the number of traces allocated with this phase.
     */
    private final CounterKey allocatedTraces;

    public static final class AllocationStatistics {
        private final CounterKey allocatedTraces;

        public AllocationStatistics(Class<?> clazz) {
            allocatedTraces = DebugContext.counter("TraceRA[%s]", clazz);
        }
    }

    private static final ClassValue<AllocationStatistics> counterClassValue = new ClassValue<>() {
        @Override
        protected AllocationStatistics computeValue(Class<?> c) {
            return new AllocationStatistics(c);
        }
    };

    private static AllocationStatistics getAllocationStatistics(Class<?> c) {
        return counterClassValue.get(c);
    }

    public TraceAllocationPhase() {
        LIRPhaseStatistics statistics = LIRPhase.getLIRPhaseStatistics(getClass());
        timer = statistics.timer;
        memUseTracker = statistics.memUseTracker;
        allocatedTraces = getAllocationStatistics(getClass()).allocatedTraces;
    }

    public final CharSequence getName() {
        return LIRPhase.createName(getClass());
    }

    @Override
    public final String toString() {
        return getName().toString();
    }

    public final void apply(TargetDescription target, LIRGenerationResult lirGenRes, Trace trace, C context) {
        apply(target, lirGenRes, trace, context, true);
    }

    @SuppressWarnings("try")
    public final void apply(TargetDescription target, LIRGenerationResult lirGenRes, Trace trace, C context, boolean dumpTrace) {
        DebugContext debug = lirGenRes.getLIR().getDebug();
        try (DebugContext.Scope s = debug.scope(getName(), this)) {
            try (DebugCloseable a = timer.start(debug); DebugCloseable c = memUseTracker.start(debug)) {
                if (dumpTrace) {
                    if (debug.isDumpEnabled(DebugContext.DETAILED_LEVEL)) {
                        debug.dump(DebugContext.DETAILED_LEVEL, trace, "Before %s (Trace%s: %s)", getName(), trace.getId(), trace);
                    }
                }
                run(target, lirGenRes, trace, context);
                allocatedTraces.increment(debug);
                if (dumpTrace) {
                    if (debug.isDumpEnabled(DebugContext.VERBOSE_LEVEL)) {
                        debug.dump(DebugContext.VERBOSE_LEVEL, trace, "After %s (Trace%s: %s)", getName(), trace.getId(), trace);
                    }
                }
            }
        } catch (Throwable e) {
            throw debug.handle(e);
        }
    }

    protected abstract void run(TargetDescription target, LIRGenerationResult lirGenRes, Trace trace, C context);
}
