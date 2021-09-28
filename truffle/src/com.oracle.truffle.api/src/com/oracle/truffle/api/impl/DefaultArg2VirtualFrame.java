package com.oracle.truffle.api.impl;

import java.util.Arrays;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;

final class DefaultArg2VirtualFrame implements VirtualFrame {
    private final FrameDescriptor descriptor;
    private final Object arg1;
    private final Object arg2;
    private Object[] locals;
    private byte[] tags;

    DefaultArg2VirtualFrame(FrameDescriptor descriptor, Object arg1, Object arg2) {
        this.descriptor = descriptor;
        this.arg1 = arg1;
        this.arg2 = arg2;
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
        throw new UnsupportedOperationException("todo, try to get rid of all these calls");
        // return arguments;
    }

    public Object getArgument1() {
        return arg1;
    }

    public Object getArgument2() {
        return arg2;
    }

    @Override
    public MaterializedFrame materialize() {
        return new DefaultArg2MaterializedFrame(this);
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
