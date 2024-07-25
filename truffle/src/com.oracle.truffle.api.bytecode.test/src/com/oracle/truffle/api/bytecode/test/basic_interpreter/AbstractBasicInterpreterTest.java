/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.Assert;
import org.junit.function.ThrowingRunnable;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLabel;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.bytecode.SourceInformationTree;
import com.oracle.truffle.api.bytecode.serialization.BytecodeDeserializer;
import com.oracle.truffle.api.bytecode.serialization.BytecodeSerializer;
import com.oracle.truffle.api.bytecode.serialization.SerializationUtils;
import com.oracle.truffle.api.bytecode.test.BytecodeDSLTestLanguage;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;

@RunWith(Parameterized.class)
public abstract class AbstractBasicInterpreterTest {

    public record TestRun(Class<? extends BasicInterpreter> interpreterClass, boolean testSerialize) {

        public boolean hasBoxingElimination() {
            return interpreterClass == BasicInterpreterWithBE.class ||
                            interpreterClass == BasicInterpreterProductionLocalScopes.class ||
                            interpreterClass == BasicInterpreterProductionGlobalScopes.class;
        }

        public boolean hasUncachedInterpreter() {
            return interpreterClass == BasicInterpreterWithUncached.class ||
                            interpreterClass == BasicInterpreterProductionLocalScopes.class ||
                            interpreterClass == BasicInterpreterProductionGlobalScopes.class;
        }

        public boolean hasGlobalScopes() {
            return interpreterClass == BasicInterpreterWithGlobalScopes.class ||
                            interpreterClass == BasicInterpreterProductionGlobalScopes.class;
        }

        public boolean hasLocalScopes() {
            return !hasGlobalScopes();
        }

        @Override
        public String toString() {
            return interpreterClass.getSimpleName() + "[serialize=" + testSerialize + "]";
        }

    }

    public static <T extends Throwable> T assertThrowsWithMessage(String message, Class<T> expectedThrowable,
                    ThrowingRunnable runnable) {
        T error = Assert.assertThrows(expectedThrowable, runnable);
        assertTrue("Invalid message: ", error.getMessage().contains(message));
        return error;
    }

    protected static final BytecodeDSLTestLanguage LANGUAGE = null;

    public static final BytecodeSerializer SERIALIZER = new BytecodeSerializer() {
        public void serialize(SerializerContext context, DataOutput buffer, Object object) throws IOException {
            if (object == null) {
                buffer.writeByte(0);
            } else if (object instanceof Long num) {
                buffer.writeByte(1);
                buffer.writeLong(num);
            } else if (object instanceof String str) {
                buffer.writeByte(2);
                buffer.writeUTF(str);
            } else if (object instanceof Boolean bool) {
                buffer.writeByte(3);
                buffer.writeBoolean(bool);
            } else if (object.getClass().isArray()) {
                buffer.writeByte(4);
                if (object instanceof long[] longs) {
                    buffer.writeByte(1);
                    buffer.writeInt(longs.length);
                    for (long num : longs) {
                        serialize(context, buffer, num);
                    }
                } else {
                    throw new AssertionError("Serializer does not handle array of type " + object.getClass());
                }
            } else if (object instanceof BasicInterpreter rootNode) {
                buffer.writeByte(5);
                context.writeBytecodeNode(buffer, rootNode);
            } else if (object instanceof Source source) {
                buffer.writeByte(6);
                buffer.writeUTF(source.getLanguage());
                buffer.writeUTF(source.getName());
                buffer.writeUTF(source.getCharacters().toString());
            } else {
                throw new AssertionError("Serializer does not handle object " + object);
            }
        }
    };

    public static final BytecodeDeserializer DESERIALIZER = new BytecodeDeserializer() {
        public Object deserialize(DeserializerContext context, DataInput buffer) throws IOException {
            byte objectCode = buffer.readByte();
            return switch (objectCode) {
                case 0 -> null;
                case 1 -> buffer.readLong();
                case 2 -> buffer.readUTF();
                case 3 -> buffer.readBoolean();
                case 4 -> {
                    byte arrayCode = buffer.readByte();
                    yield switch (arrayCode) {
                        case 1 -> {
                            int length = buffer.readInt();
                            long[] result = new long[length];
                            for (int i = 0; i < length; i++) {
                                result[i] = (long) deserialize(context, buffer);
                            }
                            yield result;
                        }
                        default -> throw new AssertionError("Deserializer does not handle array code " + arrayCode);
                    };
                }
                case 5 -> context.readBytecodeNode(buffer);
                case 6 -> {
                    String language = buffer.readUTF();
                    String name = buffer.readUTF();
                    String characters = buffer.readUTF();
                    yield Source.newBuilder(language, characters, name).build();
                }
                default -> throw new AssertionError("Deserializer does not handle code " + objectCode);
            };
        }
    };

    @Parameters(name = "{0}")
    public static List<TestRun> getParameters() {
        List<TestRun> result = new ArrayList<>();
        for (Class<? extends BasicInterpreter> interpreterClass : allInterpreters()) {
            result.add(new TestRun(interpreterClass, false));
            result.add(new TestRun(interpreterClass, true));
        }
        return result;
    }

    @Parameter(0) public TestRun run;

    public <T extends BasicInterpreterBuilder> RootCallTarget parse(String rootName, BytecodeParser<T> builder) {
        BytecodeRootNode rootNode = parseNode(run.interpreterClass, run.testSerialize, rootName, builder);
        return ((RootNode) rootNode).getCallTarget();
    }

    public <T extends BasicInterpreterBuilder> BasicInterpreter parseNode(String rootName, BytecodeParser<T> builder) {
        return parseNode(run.interpreterClass, run.testSerialize, rootName, builder);
    }

    public <T extends BasicInterpreterBuilder> BasicInterpreter parseNodeWithSource(String rootName, BytecodeParser<T> builder) {
        return parseNodeWithSource(run.interpreterClass, run.testSerialize, rootName, builder);
    }

    public <T extends BasicInterpreterBuilder> BytecodeRootNodes<BasicInterpreter> createNodes(BytecodeConfig config, BytecodeParser<T> builder) {
        return createNodes(run.interpreterClass, run.testSerialize, config, builder);
    }

    public BytecodeConfig.Builder createBytecodeConfigBuilder() {
        return invokeNewConfigBuilder(run.interpreterClass);
    }

    /**
     * Creates a root node using the given parameters.
     *
     * In order to parameterize tests over multiple different interpreter configurations
     * ("variants"), we take the specific interpreterClass as input. Since interpreters are
     * instantiated using a static {@code create} method, we must invoke this method using
     * reflection.
     */
    @SuppressWarnings("unchecked")
    public static <T extends BasicInterpreterBuilder> BytecodeRootNodes<BasicInterpreter> createNodes(Class<? extends BasicInterpreter> interpreterClass, boolean testSerialize,
                    BytecodeConfig config,
                    BytecodeParser<T> builder) {

        BytecodeRootNodes<BasicInterpreter> result = invokeCreate(interpreterClass, config, builder);
        if (testSerialize) {
            assertBytecodeNodesEqual(result, doRoundTrip(interpreterClass, config, result));
        }
        return result;
    }

    public static <T extends BasicInterpreter> BytecodeRootNodes<T> doRoundTrip(Class<? extends BasicInterpreter> interpreterClass, BytecodeConfig config, BytecodeRootNodes<BasicInterpreter> nodes) {
        // Perform a serialize-deserialize round trip.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            nodes.serialize(new DataOutputStream(output), SERIALIZER);
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        Supplier<DataInput> input = () -> SerializationUtils.createDataInput(ByteBuffer.wrap(output.toByteArray()));
        return invokeDeserialize(interpreterClass, LANGUAGE, config, input, DESERIALIZER);
    }

    public BytecodeRootNodes<BasicInterpreter> doRoundTrip(BytecodeRootNodes<BasicInterpreter> nodes) {
        return AbstractBasicInterpreterTest.doRoundTrip(run.interpreterClass, BytecodeConfig.DEFAULT, nodes);
    }

    public static <T extends BasicInterpreterBuilder> RootCallTarget parse(Class<? extends BasicInterpreter> interpreterClass, boolean testSerialize, String rootName, BytecodeParser<T> builder) {
        BytecodeRootNode rootNode = parseNode(interpreterClass, testSerialize, rootName, builder);
        return ((RootNode) rootNode).getCallTarget();
    }

    public static <T extends BasicInterpreterBuilder> BasicInterpreter parseNode(Class<? extends BasicInterpreter> interpreterClass, boolean testSerialize, String rootName,
                    BytecodeParser<T> builder) {
        BytecodeRootNodes<BasicInterpreter> nodes = createNodes(interpreterClass, testSerialize, BytecodeConfig.DEFAULT, builder);
        BasicInterpreter op = nodes.getNode(0);
        op.setName(rootName);
        return op;
    }

    public static <T extends BasicInterpreterBuilder> BasicInterpreter parseNodeWithSource(Class<? extends BasicInterpreter> interpreterClass, boolean testSerialize, String rootName,
                    BytecodeParser<T> builder) {
        BytecodeRootNodes<BasicInterpreter> nodes = createNodes(interpreterClass, testSerialize, BytecodeConfig.WITH_SOURCE, builder);
        BasicInterpreter op = nodes.getNode(0);
        op.setName(rootName);
        return op;
    }

    @SuppressWarnings("unchecked")
    public static <T extends BasicInterpreter> BytecodeRootNodes<T> invokeCreate(Class<? extends BasicInterpreter> interpreterClass, BytecodeConfig config,
                    BytecodeParser<? extends BasicInterpreterBuilder> builder) {
        try {
            Method create = interpreterClass.getMethod("create", BytecodeConfig.class, BytecodeParser.class);
            return (BytecodeRootNodes<T>) create.invoke(null, config, builder);
        } catch (InvocationTargetException e) {
            // Exceptions thrown by the invoked method can be rethrown as runtime exceptions that
            // get caught by the test harness.
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            } else if (e.getCause() instanceof Error) {
                throw (Error) e.getCause();
            } else {
                throw new AssertionError(e);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends BasicInterpreter> BytecodeRootNodes<T> invokeDeserialize(Class<? extends BasicInterpreter> interpreterClass, TruffleLanguage<?> language, BytecodeConfig config,
                    Supplier<DataInput> input, BytecodeDeserializer callback) {
        try {
            Method deserialize = interpreterClass.getMethod("deserialize", TruffleLanguage.class, BytecodeConfig.class, Supplier.class, BytecodeDeserializer.class);
            return (BytecodeRootNodes<T>) deserialize.invoke(null, language, config, input, callback);
        } catch (InvocationTargetException e) {
            // Exceptions thrown by the invoked method can be rethrown as runtime exceptions that
            // get caught by the test harness.
            throw new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static BytecodeConfig.Builder invokeNewConfigBuilder(Class<? extends BasicInterpreter> interpreterClass) {
        try {
            Method newConfigBuilder = interpreterClass.getMethod("newConfigBuilder");
            return (BytecodeConfig.Builder) newConfigBuilder.invoke(null);
        } catch (InvocationTargetException e) {
            // Exceptions thrown by the invoked method can be rethrown as runtime exceptions that
            // get caught by the test harness.
            throw new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void assertBytecodeNodesEqual(BytecodeRootNodes<BasicInterpreter> expectedBytecodeNodes, BytecodeRootNodes<BasicInterpreter> actualBytecodeNodes) {
        List<BasicInterpreter> expectedNodes = expectedBytecodeNodes.getNodes();
        List<BasicInterpreter> actualNodes = actualBytecodeNodes.getNodes();
        assertEquals(expectedNodes.size(), actualNodes.size());
        for (int i = 0; i < expectedNodes.size(); i++) {
            BasicInterpreter expectedNode = expectedNodes.get(i);
            BasicInterpreter actualNode = actualNodes.get(i);
            BytecodeNode expectedBytecode = expectedNodes.get(i).getBytecodeNode();
            BytecodeNode actualBytecode = actualNodes.get(i).getBytecodeNode();

            try {
                assertEquals(expectedNode.name, actualNode.name);
                assertArrayEquals((byte[]) readField(expectedBytecode, "bytecodes"), (byte[]) readField(actualBytecode, "bytecodes"));
                assertConstantsEqual((Object[]) readField(expectedBytecode, "constants"), (Object[]) readField(actualBytecode, "constants"));
                assertArrayEquals((int[]) readField(expectedBytecode, "handlers"), (int[]) readField(actualBytecode, "handlers"));
                assertArrayEquals((int[]) readField(expectedBytecode, "locals"), (int[]) readField(actualBytecode, "locals"));
            } catch (AssertionError e) {
                System.err.println("Expected node: " + expectedBytecode.dump());
                System.err.println("Actual node: " + actualBytecode.dump());
                throw e;
            }

        }
    }

    private static void assertConstantsEqual(Object[] expectedConstants, Object[] actualConstants) {
        assertEquals(expectedConstants.length, actualConstants.length);
        for (int i = 0; i < expectedConstants.length; i++) {
            Object expected = expectedConstants[i];
            Object actual = actualConstants[i];

            if (expected instanceof BasicInterpreter expectedRoot && actual instanceof BasicInterpreter actualRoot) {
                // We don't implement equals for root nodes (that's what we're trying to test). Make
                // sure it's at least the same name.
                assertEquals(expectedRoot.name, actualRoot.name);
            } else if (expected instanceof long[] expectedLongs && actual instanceof long[] actualLongs) {
                assertArrayEquals(expectedLongs, actualLongs);
            } else if (expected instanceof ContinuationRootNode expectedContinuation && actual instanceof ContinuationRootNode actualContinuation) {
                // The fields of a ContinuationRootNode are not exposed. At least validate they have
                // the same source root node.
                assertConstantsEqual(new Object[]{expectedContinuation.getSourceRootNode()}, new Object[]{actualContinuation.getSourceRootNode()});
            } else {
                assertEquals(expected, actual);
            }
        }
    }

    private static Object readField(BytecodeNode node, String name) {
        try {
            Field field = node.getClass().getSuperclass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(node);
        } catch (ReflectiveOperationException ex) {
            fail("Failed to access interpreter field " + name + " with introspection.");
        }
        throw new AssertionError("unreachable");
    }

    /**
     * Helper class for validating SourceInformationTrees.
     */
    record ExpectedSourceTree(boolean available, String contents, ExpectedSourceTree... children) {
        public void assertTreeEquals(SourceInformationTree actual) {
            if (!available) {
                assertTrue(!actual.getSourceSection().isAvailable());
            } else if (contents == null) {
                assertNull(actual.getSourceSection());
            } else {
                assertEquals(contents, actual.getSourceSection().getCharacters().toString());
            }
            assertEquals(children.length, actual.getChildren().size());
            for (int i = 0; i < children.length; i++) {
                children[i].assertTreeEquals(actual.getChildren().get(i));
            }
        }

        public static ExpectedSourceTree expectedSourceTree(String contents, ExpectedSourceTree... children) {
            return new ExpectedSourceTree(true, contents, children);
        }

        public static ExpectedSourceTree expectedSourceTreeUnavailable(ExpectedSourceTree... children) {
            return new ExpectedSourceTree(false, null, children);
        }
    }

    public static List<Class<? extends BasicInterpreter>> allInterpreters() {
        return List.of(BasicInterpreterBase.class, BasicInterpreterUnsafe.class, BasicInterpreterWithUncached.class, BasicInterpreterWithBE.class, BasicInterpreterWithOptimizations.class,
                        BasicInterpreterWithGlobalScopes.class, BasicInterpreterProductionGlobalScopes.class, BasicInterpreterProductionLocalScopes.class);
    }

    /// Code gen helpers

    protected static void emitReturn(BasicInterpreterBuilder b, long value) {
        b.beginReturn();
        b.emitLoadConstant(value);
        b.endReturn();
    }

    protected static void emitReturnIf(BasicInterpreterBuilder b, int arg, long value) {
        b.beginIfThen();
        b.emitLoadArgument(arg);
        emitReturn(b, value);
        b.endIfThen();
    }

    protected static void emitBranchIf(BasicInterpreterBuilder b, int arg, BytecodeLabel lbl) {
        b.beginIfThen();
        b.emitLoadArgument(arg);
        b.emitBranch(lbl);
        b.endIfThen();
    }

    protected static void emitAppend(BasicInterpreterBuilder b, long value) {
        b.beginAppenderOperation();
        b.emitLoadArgument(0);
        b.emitLoadConstant(value);
        b.endAppenderOperation();
    }

    protected static void emitThrow(BasicInterpreterBuilder b, long value) {
        b.beginThrowOperation();
        b.emitLoadConstant(value);
        b.endThrowOperation();
    }

    protected static void emitThrowIf(BasicInterpreterBuilder b, int arg, long value) {
        b.beginIfThen();
        b.emitLoadArgument(arg);
        emitThrow(b, value);
        b.endIfThen();
    }
}
