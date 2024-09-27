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
package jdk.graal.compiler.lir.alloc.trace.lsra;

import jdk.graal.compiler.core.common.alloc.RegisterAllocationConfig;
import jdk.graal.compiler.core.common.alloc.Trace;
import jdk.graal.compiler.core.common.alloc.TraceBuilderResult;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.lir.alloc.trace.lsra.TraceLinearScanPhase.TraceLinearScan;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.gen.MoveFactory;
import jdk.graal.compiler.lir.phases.LIRPhase;

import jdk.vm.ci.code.TargetDescription;

abstract class TraceLinearScanAllocationPhase {

    final CharSequence getName() {
        return LIRPhase.createName(getClass());
    }

    @Override
    public final String toString() {
        return getName().toString();
    }

    final void apply(TargetDescription target, LIRGenerationResult lirGenRes, Trace trace, MoveFactory spillMoveFactory, RegisterAllocationConfig registerAllocationConfig,
                    TraceBuilderResult traceBuilderResult, TraceLinearScan allocator) {
        apply(target, lirGenRes, trace, spillMoveFactory, registerAllocationConfig, traceBuilderResult, allocator, true);
    }

    final void apply(TargetDescription target, LIRGenerationResult lirGenRes, Trace trace, MoveFactory spillMoveFactory, RegisterAllocationConfig registerAllocationConfig,
                    TraceBuilderResult traceBuilderResult, TraceLinearScan allocator, boolean dumpLIR) {
        run(target, lirGenRes, trace, spillMoveFactory, registerAllocationConfig, traceBuilderResult, allocator);
        if (dumpLIR) {
            DebugContext debug = lirGenRes.getLIR().getDebug();
            if (debug.isDumpEnabled(DebugContext.DETAILED_LEVEL)) {
                debug.dump(DebugContext.DETAILED_LEVEL, trace, "After %s (Trace%s)", getName(), trace.getId());
            }
        }
    }

    abstract void run(TargetDescription target, LIRGenerationResult lirGenRes, Trace trace, MoveFactory spillMoveFactory, RegisterAllocationConfig registerAllocationConfig,
                    TraceBuilderResult traceBuilderResult, TraceLinearScan allocator);

}
