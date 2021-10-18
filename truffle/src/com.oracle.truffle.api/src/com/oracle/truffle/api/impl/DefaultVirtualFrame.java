/*
 * Copyright (c) 2012, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.impl;

import java.util.Arrays;

import com.oracle.truffle.api.TruffleRuntime;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * This is an implementation-specific class. Do not use or instantiate it. Instead, use
 * {@link TruffleRuntime#createVirtualFrame(Object[], FrameDescriptor)} to create a
 * {@link VirtualFrame}.
 */
final class DefaultVirtualFrame implements VirtualFrame {

    private final FrameDescriptor descriptor;
    private Object arg1;
    private Object arg2;
    private Object arg3;
    private Object arg4;
    private final Object[] remainingArgs;

    private Object[] locals;
    private byte[] tags;

    DefaultVirtualFrame(FrameDescriptor descriptor, Object[] arguments) {
        this.descriptor = descriptor;
        assert arguments.length == 0 || arguments.length > 4 : "We expect the other constructors to be used";
        if (arguments.length > 0) {
            this.arg1 = arguments[0];
            this.arg2 = arguments[1];
            this.arg3 = arguments[2];
            this.arg4 = arguments[3];
        }
        this.remainingArgs = arguments;
        // read size only once
        final int size = descriptor.getSize();
        this.locals = new Object[size];
        Object defaultValue = descriptor.getDefaultValue();
        if (defaultValue != null) {
            Arrays.fill(locals, defaultValue);
        }
        this.tags = new byte[size];
    }

    DefaultVirtualFrame(FrameDescriptor descriptor, Object arg1) {
        this.descriptor = descriptor;
        this.arg1 = arg1;
        this.remainingArgs = null;

        // read size only once
        final int size = descriptor.getSize();
        this.locals = new Object[size];
        Object defaultValue = descriptor.getDefaultValue();
        if (defaultValue != null) {
            Arrays.fill(locals, defaultValue);
        }
        this.tags = new byte[size];
    }

    DefaultVirtualFrame(FrameDescriptor descriptor, Object arg1, Object arg2) {
        this.descriptor = descriptor;
        this.arg1 = arg1;
        this.arg2 = arg2;
        this.remainingArgs = null;

        // read size only once
        final int size = descriptor.getSize();
        this.locals = new Object[size];
        Object defaultValue = descriptor.getDefaultValue();
        if (defaultValue != null) {
            Arrays.fill(locals, defaultValue);
        }
        this.tags = new byte[size];
    }

    DefaultVirtualFrame(FrameDescriptor descriptor, Object arg1, Object arg2, Object arg3) {
        this.descriptor = descriptor;
        this.arg1 = arg1;
        this.arg2 = arg2;
        this.arg3 = arg3;
        this.remainingArgs = null;

        // read size only once
        final int size = descriptor.getSize();
        this.locals = new Object[size];
        Object defaultValue = descriptor.getDefaultValue();
        if (defaultValue != null) {
            Arrays.fill(locals, defaultValue);
        }
        this.tags = new byte[size];
    }

    DefaultVirtualFrame(FrameDescriptor descriptor, Object arg1, Object arg2, Object arg3, Object arg4) {
        this.descriptor = descriptor;
        this.arg1 = arg1;
        this.arg2 = arg2;
        this.arg3 = arg3;
        this.arg4 = arg4;
        this.remainingArgs = null;

        // read size only once
        final int size = descriptor.getSize();
        this.locals = new Object[size];
        Object defaultValue = descriptor.getDefaultValue();
        if (defaultValue != null) {
            Arrays.fill(locals, defaultValue);
        }
        this.tags = new byte[size];
    }

    @Override
    public Object[] getArguments() {
        return remainingArgs;
    }

    @Override
    public Object getArgument(int idx) {
        if (idx == 0) {
            return arg1;
        }
        if (idx == 1) {
            return arg2;
        }
        if (idx == 2) {
            return arg3;
        }
        if (idx == 3) {
            return arg4;
        }
        return remainingArgs[idx];
    }

    @Override
    public void setArgument(int idx, Object val) {
        if (idx == 0) {
            arg1 = val;
        } else if (idx == 1) {
            arg2 = val;
        } else if (idx == 2) {
            arg3 = val;
        } else if (idx == 3) {
            arg4 = val;
        } else {
            remainingArgs[idx] = val;
        }
    }

    @Override
    public Object getArgument1() {
        return arg1;
    }

    @Override
    public void setArgument1(Object val) {
        arg1 = val;
    }

    @Override
    public Object getArgument2() {
        return arg2;
    }

    @Override
    public void setArgument2(Object val) {
        arg2 = val;
    }

    @Override
    public Object getArgument3() {
        return arg3;
    }

    @Override
    public void setArgument3(Object val) {
        arg3 = val;
    }

    @Override
    public Object getArgument4() {
        return arg4;
    }

    @Override
    public void setArgument4(Object val) {
        arg4 = val;
    }

    @Override
    public MaterializedFrame materialize() {
        return new DefaultMaterializedFrame(this);
    }

    @Override
    public Object getObject(FrameSlot slot) throws FrameSlotTypeException {
        verifyGet(slot, FrameSlotKind.Object);
        return locals[getFrameSlotIndex(slot)];
    }

    @Override
    public void setObject(FrameSlot slot, Object value) {
        verifySet(slot, FrameSlotKind.Object);
        locals[getFrameSlotIndex(slot)] = value;
    }

    @Override
    public byte getByte(FrameSlot slot) throws FrameSlotTypeException {
        verifyGet(slot, FrameSlotKind.Byte);
        return (byte) locals[getFrameSlotIndex(slot)];
    }

    @Override
    public void setByte(FrameSlot slot, byte value) {
        verifySet(slot, FrameSlotKind.Byte);
        locals[getFrameSlotIndex(slot)] = value;
    }

    @Override
    public boolean getBoolean(FrameSlot slot) throws FrameSlotTypeException {
        verifyGet(slot, FrameSlotKind.Boolean);
        return (boolean) locals[getFrameSlotIndex(slot)];
    }

    @Override
    public void setBoolean(FrameSlot slot, boolean value) {
        verifySet(slot, FrameSlotKind.Boolean);
        locals[getFrameSlotIndex(slot)] = value;
    }

    @Override
    public int getInt(FrameSlot slot) throws FrameSlotTypeException {
        verifyGet(slot, FrameSlotKind.Int);
        return (int) locals[getFrameSlotIndex(slot)];
    }

    @Override
    public void setInt(FrameSlot slot, int value) {
        verifySet(slot, FrameSlotKind.Int);
        locals[getFrameSlotIndex(slot)] = value;
    }

    @Override
    public long getLong(FrameSlot slot) throws FrameSlotTypeException {
        verifyGet(slot, FrameSlotKind.Long);
        return (long) locals[getFrameSlotIndex(slot)];
    }

    @Override
    public void setLong(FrameSlot slot, long value) {
        verifySet(slot, FrameSlotKind.Long);
        locals[getFrameSlotIndex(slot)] = value;
    }

    @Override
    public float getFloat(FrameSlot slot) throws FrameSlotTypeException {
        verifyGet(slot, FrameSlotKind.Float);
        return (float) locals[getFrameSlotIndex(slot)];
    }

    @Override
    public void setFloat(FrameSlot slot, float value) {
        verifySet(slot, FrameSlotKind.Float);
        locals[getFrameSlotIndex(slot)] = value;
    }

    @Override
    public double getDouble(FrameSlot slot) throws FrameSlotTypeException {
        verifyGet(slot, FrameSlotKind.Double);
        return (double) locals[getFrameSlotIndex(slot)];
    }

    @Override
    public void setDouble(FrameSlot slot, double value) {
        verifySet(slot, FrameSlotKind.Double);
        locals[getFrameSlotIndex(slot)] = value;
    }

    @Override
    public FrameDescriptor getFrameDescriptor() {
        return this.descriptor;
    }

    @Override
    public Object getValue(FrameSlot slot) {
        int slotIndex = getSlotIndexChecked(slot);
        return locals[slotIndex];
    }

    private int getSlotIndexChecked(FrameSlot slot) {
        int slotIndex = getFrameSlotIndex(slot);
        if (slotIndex >= tags.length) {
            if (!resize()) {
                throw new IllegalArgumentException(String.format("The frame slot '%s' is not known by the frame descriptor.", slot));
            }
        }
        return slotIndex;
    }

    private void verifySet(FrameSlot slot, FrameSlotKind accessKind) {
        int slotIndex = getSlotIndexChecked(slot);
        tags[slotIndex] = (byte) accessKind.ordinal();
    }

    private void verifyGet(FrameSlot slot, FrameSlotKind accessKind) throws FrameSlotTypeException {
        int slotIndex = getSlotIndexChecked(slot);
        byte tag = tags[slotIndex];
        if (accessKind == FrameSlotKind.Object ? tag != 0 : tag != accessKind.ordinal()) {
            throw new FrameSlotTypeException();
        }
    }

    private boolean resize() {
        int oldSize = tags.length;
        int newSize = descriptor.getSize();
        if (newSize > oldSize) {
            locals = Arrays.copyOf(locals, newSize);
            Arrays.fill(locals, oldSize, newSize, descriptor.getDefaultValue());
            tags = Arrays.copyOf(tags, newSize);
            return true;
        }
        return false;
    }

    private byte getTag(FrameSlot slot) {
        int slotIndex = getSlotIndexChecked(slot);
        return tags[slotIndex];
    }

    @SuppressWarnings("deprecation")
    private static int getFrameSlotIndex(FrameSlot slot) {
        return slot.getIndex();
    }

    @Override
    public boolean isObject(FrameSlot slot) {
        return getTag(slot) == FrameSlotKind.Object.ordinal();
    }

    @Override
    public boolean isByte(FrameSlot slot) {
        return getTag(slot) == FrameSlotKind.Byte.ordinal();
    }

    @Override
    public boolean isBoolean(FrameSlot slot) {
        return getTag(slot) == FrameSlotKind.Boolean.ordinal();
    }

    @Override
    public boolean isInt(FrameSlot slot) {
        return getTag(slot) == FrameSlotKind.Int.ordinal();
    }

    @Override
    public boolean isLong(FrameSlot slot) {
        return getTag(slot) == FrameSlotKind.Long.ordinal();
    }

    @Override
    public boolean isFloat(FrameSlot slot) {
        return getTag(slot) == FrameSlotKind.Float.ordinal();
    }

    @Override
    public boolean isDouble(FrameSlot slot) {
        return getTag(slot) == FrameSlotKind.Double.ordinal();
    }
}
