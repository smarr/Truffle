/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.Tag;

/**
 * Represents a debugger step configuration. A debugger step is defined by it's depth and a set of
 * additional properties. The step depth is specified by a usage of one of the appropriate prepare
 * methods on {@link SuspendedEvent}. The set of additional step properties is defined by this
 * class. These are a step count and a list of {@link SourceElement source elements} the step can
 * suspend at.
 * <p>
 * The rules of stepping through the {@link SourceElement source elements} are following:
 * <ul>
 * <li>{@link SourceElement#STATEMENT}: steps suspend {@link SuspendAnchor#BEFORE before} guest
 * language statements, unless the step returns to a calling location, in which case they suspend
 * {@link SuspendAnchor#AFTER after}. Step depth is interpreted based on pushed stack frames.</li>
 * <li>{@link SourceElement#EXPRESSION}: steps suspend {@link SuspendAnchor#BEFORE before} and
 * {@link SuspendAnchor#AFTER after} guest language expressions. Step depth is interpreted based on
 * both pushed stack frames and expression nesting hierarchy.</li>
 * </ul>
 *
 * @since 0.33
 */
public final class StepConfig {

    private static final StepConfig EMPTY = new StepConfig(null, null, 0, null, null);
    private static final Collection<SourceElement> allElements = Arrays.asList(SourceElement.values());
    private static final Map<SourceElement, Set<SuspendAnchor>> defaultAnchors;

    private final Set<SourceElement> sourceElements;
    private final Map<SourceElement, Set<SuspendAnchor>> preferredAnchors;
    private final int stepCount;

    private final Class<? extends Tag> tag;
    private final SuspendAnchor tagAnchor;

    static {
        Map<SourceElement, Set<SuspendAnchor>> anchors = new EnumMap<>(SourceElement.class);
        anchors.put(SourceElement.ROOT, DebuggerSession.ANCHOR_SET_ALL);
        anchors.put(SourceElement.STATEMENT, DebuggerSession.ANCHOR_SET_BEFORE);
        anchors.put(SourceElement.EXPRESSION, DebuggerSession.ANCHOR_SET_ALL);
        // Assert that we've filled everything.
        assert anchors.keySet().containsAll(allElements);
        defaultAnchors = Collections.unmodifiableMap(anchors);
    }

    StepConfig(Set<SourceElement> sourceElements, Map<SourceElement, Set<SuspendAnchor>> preferredAnchors, int count, Class<? extends Tag> tag, SuspendAnchor tagAnchor) {
        this.sourceElements = sourceElements;
        this.preferredAnchors = preferredAnchors;
        this.stepCount = count;
        this.tag = tag;
        this.tagAnchor = tagAnchor;
    }

    /**
     * Create a new step configuration builder.
     *
     * @since 0.33
     */
    public static Builder newBuilder() {
        return EMPTY.new Builder();
    }

    /**
     * Get a set of {@link SourceElement}s that are enabled for the step. It must be a subset of
     * {@link SourceElement}s enabled in {@link DebuggerSession} which the step is prepared for. It
     * can return <code>null</code>, in which case all source elements enabled in the debugger
     * session are also enabled for the step.
     *
     * @return a set of source elements, or <code>null</code>
     */
    Set<SourceElement> getSourceElements() {
        return sourceElements;
    }

    boolean matches(DebuggerSession session, EventContext context, SuspendAnchor anchor) {
        if (tag != null) {
            return context.hasTag(tag) && this.tagAnchor == anchor;
        }

        Set<SourceElement> elements = sourceElements;
        if (elements == null) {
            elements = session.getSourceElements();
        }
        for (SourceElement se : elements) {
            if (context.hasTag(se.getTag()) && preferredAnchors.get(se).contains(anchor)) {
                return true;
            }
        }
        return false;
    }

    Class<? extends Tag> getTag() {
        return tag;
    }

    SuspendAnchor getTagAnchor() {
        return tagAnchor;
    }

    boolean containsSourceElement(DebuggerSession session, SourceElement sourceElement) {
        Set<SourceElement> elements = sourceElements;
        if (elements == null) {
            elements = session.getSourceElements();
        }
        return elements.contains(sourceElement);
    }

    /**
     * Get the step count. It specifies the number of times the step repeats itself before it
     * suspends the execution.
     */
    int getCount() {
        return stepCount;
    }

    /**
     * Builder of {@link StepConfig}.
     *
     * @see StepConfig#newBuilder()
     * @since 0.33
     */
    public final class Builder {

        private Set<SourceElement> stepElements;
        private Map<SourceElement, Set<SuspendAnchor>> preferredAnchors;
        private int stepCount = -1;

        private Class<? extends Tag> tag;
        private SuspendAnchor tagAnchor;

        private Builder() {
        }

        /**
         * Provide a list of {@link SourceElement}s that are enabled for the step. It must be a
         * subset of {@link SourceElement}s enabled in {@link DebuggerSession} which the step is
         * prepared for. At least one source element needs to be provided and can only be invoked
         * once per builder. When not called, by default all source elements enabled in the debugger
         * session are also enabled for the step.
         *
         * @param elements a non-empty list of source elements
         * @since 0.33
         */
        public Builder sourceElements(SourceElement... elements) {
            if (this.stepElements != null) {
                throw new IllegalStateException("Step source elements can only be set once per the builder.");
            }
            if (elements.length == 0) {
                throw new IllegalArgumentException("At least one source element needs to be provided.");
            }
            if (elements.length == 1) {
                stepElements = Collections.singleton(elements[0]);
            } else {
                stepElements = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(elements)));
            }
            return this;
        }

        /**
         * Provide a list of {@link SuspendAnchor}s for individual {@link SourceElement}s. By
         * default, following suspend anchors are applied:
         * <table>
         * <tr>
         * <th>{@link SourceElement}</th>
         * <th>{@link SuspendAnchor}s</th>
         * </tr>
         * <tr>
         * <td>{@link SourceElement#ROOT ROOT}</td>
         * <td>{@link SuspendAnchor#BEFORE BEFORE}, {@link SuspendAnchor#AFTER AFTER}</td>
         * </tr>
         * <tr>
         * <td>{@link SourceElement#STATEMENT STATEMENT}</td>
         * <td>{@link SuspendAnchor#BEFORE BEFORE}</td>
         * </tr>
         * <tr>
         * <td>{@link SourceElement#EXPRESSION EXPRESSION}</td>
         * <td>{@link SuspendAnchor#BEFORE BEFORE}, {@link SuspendAnchor#AFTER AFTER}</td>
         * </tr>
         * </table>
         * This method can be called repeatedly to override the defaults.
         *
         * @param element the element to set the suspend anchor for
         * @param anchors a list of suspend anchors
         * @since 19.0
         */
        public Builder suspendAnchors(SourceElement element, SuspendAnchor... anchors) {
            if (this.tag != null && this.tagAnchor != null) {
                throw new IllegalStateException("suspendAnchors() is not compatible with tag(). Use one or the other.");
            }

            if (anchors.length == 0) {
                throw new IllegalArgumentException("At least one anchor needs to be provided.");
            }
            Objects.requireNonNull(element, "SourceElement must not be null.");
            if (preferredAnchors == null) {
                preferredAnchors = new EnumMap<>(SourceElement.class);
            }
            if (anchors.length == 1) {
                if (anchors[0] == SuspendAnchor.BEFORE) {
                    preferredAnchors.put(element, DebuggerSession.ANCHOR_SET_BEFORE);
                } else {
                    preferredAnchors.put(element, DebuggerSession.ANCHOR_SET_AFTER);
                }
            } else {
                preferredAnchors.put(element, DebuggerSession.ANCHOR_SET_ALL);
            }
            return this;
        }

        /**
         * Provide the step count. It specifies the number of times the step repeats itself before
         * it suspends the execution. Can only be invoked once per builder.
         *
         * @throws IllegalArgumentException if {@code count <= 0}
         * @since 0.33
         */
        public Builder count(int count) {
            if (count <= 0) {
                throw new IllegalArgumentException("Step count must be > 0");
            }
            if (this.stepCount > 0) {
                throw new IllegalStateException("Step count can only be set once per the builder.");
            }
            this.stepCount = count;
            return this;
        }

        /**
         * Set a tag to filter the applicable source sections.
         *
         * @since smarr/debugger
         */
        public Builder tag(@SuppressWarnings("hiding") Class<? extends Tag> tag, @SuppressWarnings("hiding") SuspendAnchor tagAnchor) {
            if (this.preferredAnchors != null) {
                throw new IllegalStateException("suspendAnchors() is not compatible with tag(). Use one or the other.");
            }

            if (this.tag != null && this.tagAnchor != null) {
                throw new IllegalStateException("Tag and anchor can only be set once per the builder.");
            }
            if (tag == null && tagAnchor == null) {
                throw new IllegalArgumentException("Tag and anchor cannot be null");
            }
            this.tag = tag;
            this.tagAnchor = tagAnchor;
            return this;
        }

        /**
         * Create a {@link StepConfig step configuration} from this builder.
         *
         * @since 0.33
         */
        public StepConfig build() {
            if (stepCount < 0) {
                stepCount = 1;
            }
            if (preferredAnchors == null) {
                preferredAnchors = defaultAnchors;
            } else {
                Collection<SourceElement> possibleElements = (stepElements != null) ? stepElements : allElements;
                if (!preferredAnchors.keySet().containsAll(possibleElements)) {
                    for (SourceElement elem : possibleElements) {
                        if (!preferredAnchors.containsKey(elem)) {
                            preferredAnchors.put(elem, defaultAnchors.get(elem));
                        }
                    }
                }
            }

            if (tag != null) {
                // revert the change of above. implementing it this way avoids code modifications
                // which will be harder to maintain
                preferredAnchors = null;
            }

            return new StepConfig(stepElements, preferredAnchors, stepCount, tag, tagAnchor);
        }
    }
}
