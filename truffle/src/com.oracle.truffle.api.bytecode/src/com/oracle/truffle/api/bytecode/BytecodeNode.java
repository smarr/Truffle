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
package com.oracle.truffle.api.bytecode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.Instruction.InstructionIterable;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Represents the bytecode for an interpreter. The bytecode can change over time; this class
 * encapsulates the current state.
 * <p>
 * Since an interpreter's bytecode can change over time, a bytecode index (bound using
 * <code>@Bind("$bci")</code>) is only meaningful when accompanied by a {@link BytecodeNode}. The
 * current bytecode node can be bound using <code>@Bind("$bytecode") BytecodeNode bytecode</code>
 * with {@link Operation operations}.
 *
 * @since 24.1
 */
public abstract class BytecodeNode extends Node {

    protected BytecodeNode(Object token) {
        BytecodeRootNodes.checkToken(token);
    }

    /**
     * Returns the current bytecode location using the current frame and location.
     *
     * @param frame the current frame
     * @param location the current location
     * @return the bytecode location, or null if a location could not be found
     * @since 24.1
     */
    public final BytecodeLocation getBytecodeLocation(Frame frame, Node location) {
        int bci = findBytecodeIndexImpl(frame, location);
        if (bci == -1) {
            return null;
        }
        return new BytecodeLocation(this, bci);
    }

    /**
     * Gets the bytecode location associated with a particular
     * {@link com.oracle.truffle.api.frame.FrameInstance frameInstance}, obtained from a stack walk.
     *
     * @param frameInstance the frame instance
     * @return the bytecode location, or null if a location could not be found
     * @since 24.1
     */
    public final BytecodeLocation getBytecodeLocation(FrameInstance frameInstance) {
        int bci = findBytecodeIndex(frameInstance);
        if (bci == -1) {
            return null;
        }
        return new BytecodeLocation(this, bci);
    }

    /**
     * Gets the bytecode location associated with a {@code bci}. This method must only be used if
     * the {@code bci} was obtained while executing this bytecode node.
     *
     * @param bci the bytecode index
     * @return the bytecode location, or null if the bytecode index is invalid
     * @since 24.1
     */
    public final BytecodeLocation getBytecodeLocation(int bci) {
        if (bci < 0) {
            return null;
        }
        return findLocation(bci);
    }

    /**
     * Gets the most concrete {@link SourceSection source location} associated with a particular
     * location. Returns {@code null} if the node was not parsed {@link BytecodeConfig#WITH_SOURCE
     * with sources} or if there is no associated source section for the given location. A location
     * must always be provided to get a source location otherwise <code>null</code> will be
     * returned.
     *
     * @param frame the current frame
     * @param location the current location
     * @return a source section corresponding to the bci, or {@code null} if no source section is
     *         available
     */
    public final SourceSection getSourceLocation(Frame frame, Node location) {
        int bci = findBytecodeIndexImpl(frame, location);
        if (bci == -1) {
            return null;
        }
        return findSourceLocation(bci);
    }

    /**
     * Gets all {@link SourceSection source locations} associated with a particular location.
     * Returns {@code null} if the node was not parsed {@link BytecodeConfig#WITH_SOURCE with
     * sources} or if there is no associated source section for the given location. A location must
     * always be provided to get a source location otherwise <code>null</code> will be returned.
     *
     * @param frame the current frame
     * @param location the current location
     * @return a source section corresponding to the bci, or {@code null} if no source section is
     *         available
     */
    public final SourceSection[] getSourceLocations(Frame frame, Node location) {
        int bci = findBytecodeIndexImpl(frame, location);
        if (bci == -1) {
            return null;
        }
        return findSourceLocations(bci);
    }

    private int findBytecodeIndexImpl(Frame frame, Node location) {
        Objects.requireNonNull(frame, "Provided frame must not be null.");
        Objects.requireNonNull(location, "Provided location must not be null.");
        Node operationNode = findOperationNode(location);
        return findBytecodeIndex(frame, operationNode);
    }

    @TruffleBoundary
    private Node findOperationNode(Node location) {
        Node prev = null;
        BytecodeNode bytecode = null;
        // Validate that location is this or a child of this.
        for (Node current = location; current != null; current = current.getParent()) {
            if (current == this) {
                bytecode = this;
                break;
            }
            prev = current;
        }
        if (bytecode == null) {
            return null;
        }
        return prev;
    }

    /**
     * Gets the source location associated with a particular
     * {@link com.oracle.truffle.api.frame.FrameInstance frameInstance}, obtained from a stack walk.
     *
     * @param frameInstance the frame instance
     * @return the source location, or null if a location could not be found
     * @since 24.1
     */
    // TODO add tests
    public final SourceSection getSourceLocation(FrameInstance frameInstance) {
        int bci = findBytecodeIndex(frameInstance);
        if (bci == -1) {
            return null;
        }
        return findSourceLocation(bci);
    }

    public final SourceSection[] getSourceLocations(FrameInstance frameInstance) {
        int bci = findBytecodeIndex(frameInstance);
        if (bci == -1) {
            return null;
        }
        return findSourceLocations(bci);
    }

    public BytecodeRootNode getBytecodeRootNode() {
        return (BytecodeRootNode) getParent();
    }

    /**
     * Computes the introspection data for this bytecode node.
     *
     * @since 24.1
     */
    public abstract BytecodeIntrospection getIntrospectionData();

    public final Iterable<Instruction> getInstructions() {
        return new InstructionIterable(this);
    }

    public final List<Instruction> getInstructionsAsList() {
        List<Instruction> instructions = new ArrayList<>();
        for (Instruction instruction : getInstructions()) {
            instructions.add(instruction);
        }
        return instructions;
    }

    /**
     * Sets a threshold that must be reached before the uncached interpreter switches to a cached
     * interpreter. The interpreter can switch to cached when the number of times it returns,
     * yields, and branches backwards exceeds the threshold.
     * <p>
     * This method has no effect if an uncached interpreter is not
     * {@link GenerateBytecode#enableUncachedInterpreter enabled} or the root node has already
     * switched to a specializing interpreter.
     *
     * @since 24.1
     */
    public abstract void setUncachedThreshold(int threshold);

    /**
     * Returns the tier of this bytecode node.
     *
     * @since 24.1
     */
    public abstract BytecodeTier getTier();

    /**
     * Dumps the bytecode with no highlighted location.
     *
     * @see #dump(BytecodeLocation)
     * @since 24.1
     */
    public final String dump() {
        return dump(null);
    }

    /**
     * Convert this bytecode node to a string representation for debugging purposes.
     *
     * @param highlightedLocation an optional location to highlight in the dump.
     * @since 24.1
     */
    @TruffleBoundary
    public final String dump(BytecodeLocation highlighedLocation) {
        if (highlighedLocation != null && highlighedLocation.getBytecodeNode() != this) {
            throw new IllegalArgumentException("Invalid highlighted location. Belongs to a different BytecodeNode.");
        }
        BytecodeIntrospection id = getIntrospectionData();
        List<Instruction> instructions = getInstructionsAsList();
        List<ExceptionHandler> exceptions = id.getExceptionHandlers();
        List<SourceInformation> sourceInformation = id.getSourceInformation();
        int highlightedBci = highlighedLocation == null ? -1 : highlighedLocation.getBytecodeIndex();
        return String.format("""
                        %s(name=%s)[
                            instructions(%s) = %s
                            exceptionHandlers(%s) = %s
                            sourceInformation(%s) = %s
                            tagTree%s
                        ]""",
                        getClass().getSimpleName(),
                        ((RootNode) getParent()).getQualifiedName(),
                        instructions.size(),
                        formatList(instructions, (i) -> i.getBytecodeIndex() == highlightedBci),
                        exceptions.size(),
                        formatList(exceptions, (e) -> highlightedBci >= e.getStartIndex() && highlightedBci < e.getEndIndex()),
                        sourceInformation != null ? sourceInformation.size() : "-",
                        formatList(sourceInformation, (s) -> highlightedBci >= s.getBeginBci() && highlightedBci < s.getEndBci()),
                        formatTagTree(id.getTagTree(), (s) -> highlightedBci >= s.getStartBci() && highlightedBci <= s.getEndBci()));
    }

    private static <T> String formatList(List<T> list, Predicate<T> highlight) {
        if (list == null) {
            return "Not Available";
        } else if (list.isEmpty()) {
            return "Empty";
        }
        StringBuilder b = new StringBuilder();
        for (T o : list) {
            if (highlight.test(o)) {
                b.append("\n    ==> ");
            } else {
                b.append("\n        ");
            }
            b.append(o.toString());
        }
        return b.toString();
    }

    private static String formatTagTree(TagTree tree, Predicate<TagTree> highlight) {
        if (tree == null) {
            return " = Not Available";
        }
        StringBuilder b = new StringBuilder();
        int count = appendTagTree(b, 0, tree, highlight);
        b.insert(0, "(" + count + ") = ");
        return b.toString();
    }

    private static int appendTagTree(StringBuilder b, int spaces, TagTree tree, Predicate<TagTree> highlight) {
        b.append("\n");
        if (highlight.test(tree)) {
            b.append("    ==> ");
        } else {
            b.append("        ");
        }
        for (int i = 0; i < spaces; i++) {
            b.append(" ");
        }

        b.append(tree.toString());

        int count = 1;
        for (TagTree child : tree.getTreeChildren()) {
            count += appendTagTree(b, spaces + 2, child, highlight);
        }
        return count;
    }

    /**
     * Finds the most concrete source location associated with the given bytecode index. The method
     * returns <code>null</code> if no source section could be found. Calling this method also
     * {@link BytecodeRootNodes#ensureSources() ensures source sections} are materialized.
     *
     * @since 24.1
     */
    public abstract SourceSection findSourceLocation(int bci);

    public abstract SourceSection findSourceLocation(int beginBci, int endBCi);

    /**
     * Finds all source locations associated with the given bytecode index. The array returns more
     * concrete source sections first. Typically later source sections are contained in the previous
     * source section, but there is no guarantee that this the case. Calling this method also
     * {@link BytecodeRootNodes#ensureSources() ensures source sections} are materialized.
     *
     * @since 24.1
     */
    public abstract SourceSection[] findSourceLocations(int bci);

    public abstract SourceSection[] findSourceLocations(int beginBci, int endBci);

    /**
     * Finds the instruction associated with the given bytecode index.
     *
     * @since 24.1
     */
    protected abstract Instruction findInstruction(int bci);

    protected abstract int findBytecodeIndex(Frame frame, Node operationNode);

    protected abstract int findBytecodeIndex(FrameInstance frameInstance);

    protected final BytecodeLocation findLocation(int bci) {
        return new BytecodeLocation(this, bci);
    }

    /**
     * Gets the bytecode node for a given FrameInstance. Frame instances are invalid as soon as the
     * execution of a frame is continued. A bytecode node can be used to materialize a
     * {@link BytecodeLocation}, which can be used after the {@link FrameInstance} is no longer
     * valid.
     *
     * @param frameInstance the frame instance
     * @return the corresponding bytecode node or null if no node can be found.
     * @since 24.1
     */
    @TruffleBoundary
    public static BytecodeNode get(FrameInstance frameInstance) {
        BytecodeNode location = get(frameInstance.getCallNode());
        if (location != null) {
            return location;
        }
        CallTarget target = frameInstance.getCallTarget();
        if (target instanceof RootCallTarget rootCallTarget) {
            RootNode rootNode = rootCallTarget.getRootNode();
            if (rootNode instanceof BytecodeRootNode bytecodeRoot) {
                // should only ever happen for a top-frame.
                return bytecodeRoot.getBytecodeNode();
            }
        }
        return null;
    }

    /**
     * Gets the bytecode location for a given Node, if available.
     *
     * @param node the node
     * @return the corresponding bytecode location or null if no location can be found.
     * @since 24.1
     */
    @TruffleBoundary
    public static BytecodeNode get(Node node) {
        Node location = node;
        for (Node currentNode = location; currentNode != null; currentNode = currentNode.getParent()) {
            if (currentNode instanceof BytecodeNode bytecodeNode) {
                return bytecodeNode;
            }
        }
        return null;
    }

}
