/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.bytecode.test.basic_interpreter;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.GenerateBytecodeTestVariants;
import com.oracle.truffle.api.bytecode.GenerateBytecodeTestVariants.Variant;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.bytecode.Instrumentation;
import com.oracle.truffle.api.bytecode.LocalSetter;
import com.oracle.truffle.api.bytecode.LocalSetterRange;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.ShortCircuitOperation;
import com.oracle.truffle.api.bytecode.ShortCircuitOperation.Operator;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.bytecode.test.BytecodeDSLTestLanguage;
import com.oracle.truffle.api.bytecode.test.DebugBytecodeRootNode;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

/**
 * This class defines a set of interpreter variants with different configurations. Where possible,
 * prefer to use this class when testing new functionality, because
 * {@link AbstractBasicInterpreterTest} allows us to execute tests on each variant, increasing our
 * test coverage.
 */
@GenerateBytecodeTestVariants({
                @Variant(suffix = "Base", configuration = @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, enableYield = true, enableSerialization = true, enableTagInstrumentation = true, allowUnsafe = false)),
                @Variant(suffix = "Unsafe", configuration = @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, enableYield = true, enableSerialization = true, enableTagInstrumentation = true)),
                @Variant(suffix = "WithUncached", configuration = @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, enableYield = true, enableSerialization = true, enableTagInstrumentation = true, enableUncachedInterpreter = true)),
                @Variant(suffix = "WithBE", configuration = @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, enableYield = true, enableSerialization = true, enableTagInstrumentation = true, boxingEliminationTypes = {
                                boolean.class, long.class}, decisionsFile = "basic_interpreter_quickening_only.json")),
                @Variant(suffix = "WithOptimizations", configuration = @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, enableYield = true, enableSerialization = true, enableTagInstrumentation = true, //
                                decisionsFile = "basic_interpreter_decisions.json")),
                @Variant(suffix = "WithGlobalScopes", configuration = @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, enableYield = true, enableSerialization = true, enableLocalScoping = false, enableTagInstrumentation = true)),
                // A typical "production" configuration with all of the bells and whistles.
                @Variant(suffix = "ProductionLocalScopes", configuration = @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, enableYield = true, enableSerialization = true, enableLocalScoping = true, enableTagInstrumentation = true, //
                                enableUncachedInterpreter = true, boxingEliminationTypes = {boolean.class, long.class}, decisionsFile = "basic_interpreter_decisions.json")),
                @Variant(suffix = "ProductionGlobalScopes", configuration = @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, enableYield = true, enableSerialization = true, enableLocalScoping = false, //
                                enableTagInstrumentation = true, enableUncachedInterpreter = true, boxingEliminationTypes = {boolean.class,
                                                long.class}, decisionsFile = "basic_interpreter_decisions.json"))
})
@ShortCircuitOperation(booleanConverter = BasicInterpreter.ToBoolean.class, name = "ScAnd", operator = Operator.AND_RETURN_VALUE)
@ShortCircuitOperation(booleanConverter = BasicInterpreter.ToBoolean.class, name = "ScOr", operator = Operator.OR_RETURN_VALUE, javadoc = "ScOr returns the first truthy operand value.")
public abstract class BasicInterpreter extends DebugBytecodeRootNode implements BytecodeRootNode {

    protected BasicInterpreter(TruffleLanguage<?> language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    protected String name;

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return String.format("%s(%s)", this.getClass().getSimpleName(), getName());
    }

    // Expose the protected cloneUninitialized method for testing.
    public BasicInterpreter doCloneUninitialized() {
        return (BasicInterpreter) cloneUninitialized();
    }

    protected static class TestException extends AbstractTruffleException {
        private static final long serialVersionUID = -9143719084054578413L;

        public final long value;

        TestException(String string, Node node, long value) {
            super(string, node);
            this.value = value;
        }
    }

    @Override
    public Object interceptControlFlowException(ControlFlowException ex, VirtualFrame frame, BytecodeNode bytecodeNode, int bci) throws Throwable {
        if (ex instanceof EarlyReturnException ret) {
            return ret.result;
        }
        throw ex;
    }

    @SuppressWarnings({"serial"})
    public static class EarlyReturnException extends ControlFlowException {
        private static final long serialVersionUID = 3637685681756424058L;

        public final Object result;

        EarlyReturnException(Object result) {
            this.result = result;
        }
    }

    @Operation
    static final class EarlyReturn {
        @Specialization
        public static void perform(Object result) {
            throw new EarlyReturnException(result);
        }
    }

    @Operation(javadoc = "Adds the two operand values, which must either be longs or Strings.")
    static final class AddOperation {
        @Specialization
        public static long addLongs(long lhs, long rhs) {
            return lhs + rhs;
        }

        @Specialization
        @TruffleBoundary
        public static String addStrings(String lhs, String rhs) {
            return lhs + rhs;
        }
    }

    @Operation
    @ConstantOperand(type = long.class)
    static final class AddConstantOperation {
        @Specialization
        public static long addLongs(long constantLhs, long rhs) {
            return constantLhs + rhs;
        }

        @Specialization
        public static String addStrings(long constantLhs, String rhs) {
            return constantLhs + rhs;
        }
    }

    @Operation
    @ConstantOperand(type = long.class, specifyAtEnd = true)
    static final class AddConstantOperationAtEnd {
        @Specialization
        public static long addLongs(long lhs, long constantRhs) {
            return lhs + constantRhs;
        }

        @Specialization
        public static String addStrings(String lhs, long constantRhs) {
            return lhs + constantRhs;
        }
    }

    @Operation
    static final class LessThanOperation {
        @Specialization
        public static boolean lessThan(long lhs, long rhs) {
            return lhs < rhs;
        }
    }

    @Operation
    static final class VeryComplexOperation {
        @Specialization
        public static long bla(long a1, @Variadic Object[] a2) {
            return a1 + a2.length;
        }
    }

    @Operation
    static final class ThrowOperation {
        @Specialization
        public static Object perform(long value,
                        @Bind Node node) {
            throw new TestException("fail", node, value);
        }
    }

    @Operation
    static final class ReadExceptionOperation {
        @Specialization
        public static long perform(TestException ex) {
            return ex.value;
        }
    }

    @Operation
    static final class AlwaysBoxOperation {
        @Specialization
        public static Object perform(Object value) {
            return value;
        }
    }

    @Operation
    static final class AppenderOperation {
        @SuppressWarnings("unchecked")
        @Specialization
        @TruffleBoundary
        public static void perform(List<?> list, Object value) {
            ((List<Object>) list).add(value);
        }
    }

    @Operation
    @ConstantOperand(type = LocalSetter.class)
    static final class TeeLocal {
        @Specialization
        public static long doInt(VirtualFrame frame,
                        LocalSetter setter,
                        long value,
                        @Bind BytecodeNode bytecode,
                        @Bind("$bytecodeIndex") int bci) {
            setter.setLong(bytecode, bci, frame, value);
            return value;
        }

        @Specialization
        public static Object doGeneric(VirtualFrame frame,
                        LocalSetter setter,
                        Object value,
                        @Bind BytecodeNode bytecode,
                        @Bind("$bytecodeIndex") int bci) {
            setter.setObject(bytecode, bci, frame, value);
            return value;
        }
    }

    @Operation
    @ConstantOperand(type = LocalSetterRange.class)
    static final class TeeLocalRange {
        @Specialization
        @ExplodeLoop
        public static Object doLong(VirtualFrame frame,
                        LocalSetterRange setter,
                        long[] value,
                        @Bind BytecodeNode bytecode,
                        @Bind("$bytecodeIndex") int bci) {
            for (int i = 0; i < value.length; i++) {
                setter.setLong(bytecode, bci, frame, i, value[i]);
            }
            return value;
        }

        @Specialization
        @ExplodeLoop
        public static Object doGeneric(VirtualFrame frame,
                        LocalSetterRange setter,
                        Object[] value,
                        @Bind BytecodeNode bytecode,
                        @Bind("$bytecodeIndex") int bci) {
            for (int i = 0; i < value.length; i++) {
                setter.setObject(bytecode, bci, frame, i, value[i]);
            }
            return value;
        }
    }

    @SuppressWarnings("unused")
    @Operation
    public static final class Invoke {
        @Specialization(guards = {"callTargetMatches(root.getCallTarget(), callNode.getCallTarget())"}, limit = "1")
        public static Object doRootNode(BasicInterpreter root, @Variadic Object[] args, @Cached("create(root.getCallTarget())") DirectCallNode callNode) {
            return callNode.call(args);
        }

        @Specialization(replaces = {"doRootNode"})
        public static Object doRootNodeUncached(BasicInterpreter root, @Variadic Object[] args, @Shared @Cached IndirectCallNode callNode) {
            return callNode.call(root.getCallTarget(), args);
        }

        @Specialization(guards = {"callTargetMatches(root.getCallTarget(), callNode.getCallTarget())"}, limit = "1")
        public static Object doClosure(TestClosure root, @Variadic Object[] args, @Cached("create(root.getCallTarget())") DirectCallNode callNode) {
            assert args.length == 0 : "not implemented";
            return callNode.call(root.getFrame());
        }

        @Specialization(replaces = {"doClosure"})
        public static Object doClosureUncached(TestClosure root, @Variadic Object[] args, @Shared @Cached IndirectCallNode callNode) {
            assert args.length == 0 : "not implemented";
            return callNode.call(root.getCallTarget(), root.getFrame());
        }

        @Specialization(guards = {"callTargetMatches(callTarget, callNode.getCallTarget())"}, limit = "1")
        public static Object doCallTarget(CallTarget callTarget, @Variadic Object[] args, @Cached("create(callTarget)") DirectCallNode callNode) {
            return callNode.call(args);
        }

        @Specialization(replaces = {"doCallTarget"})
        public static Object doCallTargetUncached(CallTarget callTarget, @Variadic Object[] args, @Shared @Cached IndirectCallNode callNode) {
            return callNode.call(callTarget, args);
        }

        protected static boolean callTargetMatches(CallTarget left, CallTarget right) {
            return left == right;
        }
    }

    @Operation
    public static final class MaterializeFrame {
        @Specialization
        public static MaterializedFrame materialize(VirtualFrame frame) {
            return frame.materialize();
        }
    }

    @Operation
    public static final class CreateClosure {
        @Specialization
        public static TestClosure materialize(VirtualFrame frame, BasicInterpreter root) {
            return new TestClosure(frame.materialize(), root);
        }
    }

    @Operation(javadoc = "Does nothing.")
    public static final class VoidOperation {
        @Specialization
        public static void doNothing() {
        }
    }

    @Operation
    public static final class ToBoolean {
        @Specialization
        public static boolean doLong(long l) {
            return l != 0;
        }

        @Specialization
        public static boolean doBoolean(boolean b) {
            return b;
        }

        @Specialization
        public static boolean doString(String s) {
            return s != null;
        }
    }

    @Operation
    public static final class NonNull {
        @Specialization
        public static boolean doObject(Object o) {
            return o != null;
        }
    }

    @Operation
    public static final class GetSourcePositions {
        @Specialization
        public static Object doOperation(VirtualFrame frame,
                        @Bind Node node,
                        @Bind BytecodeNode bytecode) {
            return bytecode.getSourceLocations(frame, node);
        }
    }

    @Operation
    public static final class CopyLocalsToFrame {
        @Specialization
        public static Frame doSomeLocals(VirtualFrame frame, long length,
                        @Bind BytecodeNode bytecodeNode,
                        @Bind("$bytecodeIndex") int bci) {
            Frame newFrame = Truffle.getRuntime().createMaterializedFrame(frame.getArguments(), frame.getFrameDescriptor());
            bytecodeNode.copyLocalValues(bci, frame, newFrame, 0, (int) length);
            return newFrame;
        }

        @Specialization(guards = {"length == null"})
        public static Frame doAllLocals(VirtualFrame frame, @SuppressWarnings("unused") Object length,
                        @Bind BytecodeNode bytecodeNode,
                        @Bind("$bytecodeIndex") int bci) {
            Frame newFrame = Truffle.getRuntime().createMaterializedFrame(frame.getArguments(), frame.getFrameDescriptor());
            bytecodeNode.copyLocalValues(bci, frame, newFrame);
            return newFrame;
        }
    }

    @Operation
    public static final class CollectBytecodeLocations {
        @Specialization
        public static List<BytecodeLocation> perform() {
            List<BytecodeLocation> bytecodeIndices = new ArrayList<>();
            Truffle.getRuntime().iterateFrames(f -> {
                bytecodeIndices.add(BytecodeLocation.get(f));
                return null;
            });
            return bytecodeIndices;
        }
    }

    @Operation
    public static final class CollectSourceLocations {
        @Specialization
        public static List<SourceSection> perform(
                        @Bind BytecodeLocation location,
                        @Bind BasicInterpreter currentRootNode) {
            List<SourceSection> sourceLocations = new ArrayList<>();
            Truffle.getRuntime().iterateFrames(f -> {
                if (f.getCallTarget() instanceof RootCallTarget rct && rct.getRootNode() instanceof BasicInterpreter frameRootNode) {
                    if (currentRootNode == frameRootNode) {
                        // The top-most stack trace element doesn't have a call node.
                        sourceLocations.add(location.getSourceLocation());
                    } else {
                        sourceLocations.add(frameRootNode.getBytecodeNode().getSourceLocation(f));
                    }
                } else {
                    sourceLocations.add(null);
                }
                return null;
            });
            return sourceLocations;
        }
    }

    @Operation
    public static final class CollectAllSourceLocations {
        @Specialization
        public static List<SourceSection[]> perform(
                        @Bind BytecodeLocation location,
                        @Bind BasicInterpreter currentRootNode) {
            List<SourceSection[]> allSourceLocations = new ArrayList<>();
            Truffle.getRuntime().iterateFrames(f -> {
                if (f.getCallTarget() instanceof RootCallTarget rct && rct.getRootNode() instanceof BasicInterpreter frameRootNode) {
                    if (currentRootNode == frameRootNode) {
                        // The top-most stack trace element doesn't have a call node.
                        allSourceLocations.add(location.getSourceLocations());
                    } else {
                        allSourceLocations.add(frameRootNode.getBytecodeNode().getSourceLocations(f));
                    }
                } else {
                    allSourceLocations.add(null);
                }
                return null;
            });
            return allSourceLocations;
        }
    }

    @Operation
    public static final class ContinueNode {
        public static final int LIMIT = 3;

        @SuppressWarnings("unused")
        @Specialization(guards = {"result.getContinuationRootNode() == rootNode"}, limit = "LIMIT")
        public static Object invokeDirect(ContinuationResult result, Object value,
                        @Cached("result.getContinuationRootNode()") RootNode rootNode,
                        @Cached("create(rootNode.getCallTarget())") DirectCallNode callNode) {
            return callNode.call(result.getFrame(), value);
        }

        @Specialization(replaces = "invokeDirect")
        public static Object invokeIndirect(ContinuationResult result, Object value,
                        @Cached IndirectCallNode callNode) {
            return callNode.call(result.getContinuationCallTarget(), result.getFrame(), value);
        }
    }

    @Operation
    public static final class CurrentLocation {
        @Specialization
        public static BytecodeLocation perform(@Bind BytecodeLocation location) {
            return location;
        }
    }

    @Instrumentation
    public static final class PrintHere {
        @Specialization
        public static void perform() {
            System.out.println("here!");
        }
    }

    @Instrumentation(javadoc = "Increments the instrumented value by 1.")
    public static final class IncrementValue {
        @Specialization
        public static long doIncrement(long value) {
            return value + 1;
        }
    }

    @Instrumentation
    public static final class DoubleValue {
        @Specialization
        public static long doDouble(long value) {
            return value << 1;
        }
    }

    @Operation
    public static final class EnableIncrementValueInstrumentation {
        @Specialization
        public static void doEnable(
                        @Bind BasicInterpreter root,
                        @Cached(value = "getConfig(root)", allowUncached = true, neverDefault = true) BytecodeConfig config) {
            root.getRootNodes().update(config);
        }

        @TruffleBoundary
        protected static BytecodeConfig getConfig(BasicInterpreter root) {
            BytecodeConfig.Builder configBuilder = AbstractBasicInterpreterTest.invokeNewConfigBuilder(root.getClass());
            configBuilder.addInstrumentation(IncrementValue.class);
            return configBuilder.build();
        }
    }

    @Operation
    public static final class EnableDoubleValueInstrumentation {
        @Specialization
        public static void doEnable(
                        @Bind BasicInterpreter root,
                        @Cached(value = "getConfig(root)", allowUncached = true, neverDefault = true) BytecodeConfig config) {
            root.getRootNodes().update(config);
        }

        @TruffleBoundary
        protected static BytecodeConfig getConfig(BasicInterpreter root) {
            BytecodeConfig.Builder configBuilder = AbstractBasicInterpreterTest.invokeNewConfigBuilder(root.getClass());
            configBuilder.addInstrumentation(DoubleValue.class);
            return configBuilder.build();
        }

    }

    record Bindings(
                    BytecodeNode bytecode,
                    RootNode root,
                    BytecodeLocation location,
                    Instruction instruction,
                    Node node,
                    int bytecodeIndex) {
    }

    @Operation
    static final class ExplicitBindingsTest {
        @Specialization
        @SuppressWarnings("truffle")
        public static Bindings doDefault(
                        @Bind("$bytecodeNode") BytecodeNode bytecode,
                        @Bind("$rootNode") BasicInterpreter root1,
                        @Bind("$rootNode") BytecodeRootNode root2,
                        @Bind("$rootNode") RootNode root3,
                        @Bind("$bytecodeNode.getBytecodeLocation($bytecodeIndex)") BytecodeLocation location,
                        @Bind("$bytecodeNode.getInstruction($bytecodeIndex)") Instruction instruction,
                        @Bind("this") Node node1,
                        @Bind("$node") Node node2,
                        @Bind("$bytecodeIndex") int bytecodeIndex) {
            if (root1 != root2 || root2 != root3) {
                throw CompilerDirectives.shouldNotReachHere();
            }
            if (node1 != node2) {
                throw CompilerDirectives.shouldNotReachHere();
            }
            return new Bindings(bytecode, root1, location, instruction, node1, bytecodeIndex);
        }
    }

    @Operation
    static final class ImplicitBindingsTest {
        @Specialization
        public static Bindings doDefault(
                        @Bind BytecodeNode bytecode,
                        @Bind BasicInterpreter root1,
                        @Bind BytecodeRootNode root2,
                        @Bind RootNode root3,
                        @Bind BytecodeLocation location,
                        @Bind Instruction instruction,
                        @Bind Node node,
                        @Bind("$bytecodeIndex") int bytecodeIndex) {

            if (root1 != root2 || root2 != root3) {
                throw CompilerDirectives.shouldNotReachHere();
            }

            return new Bindings(bytecode, root1, location, instruction, node, bytecodeIndex);
        }
    }
}

class TestClosure {
    private final MaterializedFrame frame;
    private final RootCallTarget root;

    TestClosure(MaterializedFrame frame, BasicInterpreter root) {
        this.frame = frame;
        this.root = root.getCallTarget();
    }

    public RootCallTarget getCallTarget() {
        return root;
    }

    public MaterializedFrame getFrame() {
        return frame;
    }

    public Object call() {
        return root.call(frame);
    }
}
