/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.truffle.test;

import static com.oracle.truffle.api.bytecode.test.basic_interpreter.AbstractBasicInterpreterTest.parseNode;
import static com.oracle.truffle.api.bytecode.test.basic_interpreter.AbstractBasicInterpreterTest.invokeNewConfigBuilder;
import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.test.BytecodeDSLTestLanguage;
import com.oracle.truffle.api.bytecode.test.basic_interpreter.AbstractBasicInterpreterTest;
import com.oracle.truffle.api.bytecode.test.basic_interpreter.BasicInterpreter;
import com.oracle.truffle.api.bytecode.test.basic_interpreter.BasicInterpreterBuilder;
import com.oracle.truffle.runtime.OptimizedCallTarget;

@RunWith(Parameterized.class)
public class BytecodeDSLCompilationTest extends TestWithSynchronousCompiling {
    protected static final BytecodeDSLTestLanguage LANGUAGE = null;

    @Parameters(name = "{0}")
    public static List<Class<? extends BasicInterpreter>> getInterpreterClasses() {
        return AbstractBasicInterpreterTest.allInterpreters();
    }

    @Parameter(0) public Class<? extends BasicInterpreter> interpreterClass;

    @Before
    @Override
    public void before() {
        super.before();
        // TODO without eager loading, the first compilation on some tests fails because this type
        // is not loaded
        try {
            Class.forName(BasicInterpreter.EarlyReturnException.class.getName());
        } catch (ClassNotFoundException ex) {
        }
    }

    @Test
    public void testCompiles() {
        BasicInterpreter root = parseNodeForCompilation(interpreterClass, "addTwoConstants", b -> {
            b.beginRoot(LANGUAGE);

            b.beginReturn();
            b.beginAddOperation();
            b.emitLoadConstant(20L);
            b.emitLoadConstant(22L);
            b.endAddOperation();
            b.endReturn();

            b.endRoot();
        });

        OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
        assertEquals(42L, target.call());
        target.compile(true);
        assertCompiled(target);
        assertEquals(42L, target.call());
        assertCompiled(target);
    }

    @Test
    public void testInstrumentation() {
        BasicInterpreter root = parseNodeForCompilation(interpreterClass, "addTwoConstantsInstrumented", b -> {
            b.beginRoot(LANGUAGE);

            b.beginReturn();
            b.beginIncrementValue();
            b.beginAddOperation();
            b.emitLoadConstant(20L);
            b.emitLoadConstant(22L);
            b.endAddOperation();
            b.endIncrementValue();
            b.endReturn();

            b.endRoot();
        });

        OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
        assertEquals(42L, target.call());
        target.compile(true);
        assertCompiled(target);

        // Instrumentation should invalidate the compiled code.
        root.getRootNodes().update(invokeNewConfigBuilder(interpreterClass).addInstrumentation(BasicInterpreter.IncrementValue.class).build());
        assertNotCompiled(target);

        // The instrumented interpreter should be recompiled.
        assertEquals(43L, target.call());
        target.compile(true);
        assertCompiled(target);
        assertEquals(43L, target.call());
        assertCompiled(target);
    }

    @Test
    public void testYield() {
        BasicInterpreter root = parseNodeForCompilation(interpreterClass, "addYield", b -> {
            b.beginRoot(LANGUAGE);

            b.beginReturn();
            b.beginAddOperation();
            b.emitLoadConstant(20L);
            b.beginYield();
            b.emitLoadConstant(123L);
            b.endYield();
            b.endAddOperation();
            b.endReturn();

            b.endRoot();
        });

        OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
        ContinuationResult cont = (ContinuationResult) target.call();
        assertEquals(123L, cont.getResult());
        OptimizedCallTarget continuationCallTarget = (OptimizedCallTarget) cont.getContinuationCallTarget();
        assertEquals(42L, cont.continueWith(22L));
        assertNotCompiled(target);
        assertNotCompiled(continuationCallTarget);

        target.compile(true);
        cont = (ContinuationResult) target.call();
        continuationCallTarget = (OptimizedCallTarget) cont.getContinuationCallTarget();
        assertEquals(40L, cont.continueWith(20L));
        assertCompiled(target);
        assertNotCompiled(continuationCallTarget);

        continuationCallTarget.compile(true);
        cont = (ContinuationResult) target.call();
        continuationCallTarget = (OptimizedCallTarget) cont.getContinuationCallTarget();
        assertEquals(44L, cont.continueWith(24L));
        assertCompiled(target);
        assertCompiled(continuationCallTarget);
    }

    @Test
    public void testYieldInstrumentation() {
        BasicInterpreter root = parseNodeForCompilation(interpreterClass, "addYieldInstrumented", b -> {
            b.beginRoot(LANGUAGE);

            b.beginReturn();
            b.beginIncrementValue();
            b.beginAddOperation();
            b.emitLoadConstant(20L);
            b.beginYield();
            b.emitLoadConstant(123L);
            b.endYield();
            b.endAddOperation();
            b.endIncrementValue();
            b.endReturn();

            b.endRoot();
        });

        OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
        OptimizedCallTarget continuationCallTarget = null;

        ContinuationResult cont = (ContinuationResult) target.call();
        assertEquals(123L, cont.getResult());
        continuationCallTarget = (OptimizedCallTarget) cont.getContinuationCallTarget();
        assertEquals(42L, cont.continueWith(22L));
        assertNotCompiled(target);
        assertNotCompiled(continuationCallTarget);

        target.compile(true);
        continuationCallTarget.compile(true);
        assertCompiled(target);
        assertCompiled(continuationCallTarget);

        // Instrumentation should invalidate the compiled code.
        root.getRootNodes().update(invokeNewConfigBuilder(interpreterClass).addInstrumentation(BasicInterpreter.IncrementValue.class).build());
        assertNotCompiled(target);
        assertNotCompiled(continuationCallTarget);

        // The instrumented interpreter should be recompiled.
        assertEquals(43L, ((ContinuationResult) target.call()).continueWith(22L));
        target.compile(true);
        continuationCallTarget.compile(true);
        assertCompiled(target);
        assertCompiled(continuationCallTarget);
        assertEquals(43L, ((ContinuationResult) target.call()).continueWith(22L));
        assertCompiled(target);
        assertCompiled(continuationCallTarget);
    }

    private static <T extends BasicInterpreterBuilder> BasicInterpreter parseNodeForCompilation(Class<? extends BasicInterpreter> interpreterClass, String rootName, BytecodeParser<T> builder) {
        BasicInterpreter result = parseNode(interpreterClass, false, rootName, builder);
        result.getBytecodeNode().setUncachedThreshold(0); // force interpreter to skip tier 0
        return result;
    }
}
