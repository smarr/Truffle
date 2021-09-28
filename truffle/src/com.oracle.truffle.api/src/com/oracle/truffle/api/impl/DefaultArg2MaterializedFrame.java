package com.oracle.truffle.api.impl;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.MaterializedFrame;

public class DefaultArg2MaterializedFrame implements MaterializedFrame {
    private final DefaultArg2VirtualFrame wrapped;

    DefaultArg2MaterializedFrame(DefaultArg2VirtualFrame wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public Object[] getArguments() {
        return wrapped.getArguments();
    }

    public Object getArgument1() {
        return wrapped.getArgument1();
    }

    public Object getArgument2() {
        return wrapped.getArgument2();
    }

    @Override
    public Object getObject(FrameSlot slot) throws FrameSlotTypeException {
        return wrapped.getObject(slot);
    }

    @Override
    public void setObject(FrameSlot slot, Object value) {
        wrapped.setObject(slot, value);
    }

    @Override
    public byte getByte(FrameSlot slot) throws FrameSlotTypeException {
        return wrapped.getByte(slot);
    }

    @Override
    public void setByte(FrameSlot slot, byte value) {
        wrapped.setByte(slot, value);
    }

    @Override
    public boolean getBoolean(FrameSlot slot) throws FrameSlotTypeException {
        return wrapped.getBoolean(slot);
    }

    @Override
    public void setBoolean(FrameSlot slot, boolean value) {
        wrapped.setBoolean(slot, value);
    }

    @Override
    public int getInt(FrameSlot slot) throws FrameSlotTypeException {
        return wrapped.getInt(slot);
    }

    @Override
    public void setInt(FrameSlot slot, int value) {
        wrapped.setInt(slot, value);
    }

    @Override
    public long getLong(FrameSlot slot) throws FrameSlotTypeException {
        return wrapped.getLong(slot);
    }

    @Override
    public void setLong(FrameSlot slot, long value) {
        wrapped.setLong(slot, value);
    }

    @Override
    public float getFloat(FrameSlot slot) throws FrameSlotTypeException {
        return wrapped.getFloat(slot);
    }

    @Override
    public void setFloat(FrameSlot slot, float value) {
        wrapped.setFloat(slot, value);
    }

    @Override
    public double getDouble(FrameSlot slot) throws FrameSlotTypeException {
        return wrapped.getDouble(slot);
    }

    @Override
    public void setDouble(FrameSlot slot, double value) {
        wrapped.setDouble(slot, value);
    }

    @Override
    public Object getValue(FrameSlot slot) {
        return wrapped.getValue(slot);
    }

    @Override
    public MaterializedFrame materialize() {
        return this;
    }

    @Override
    public FrameDescriptor getFrameDescriptor() {
        return wrapped.getFrameDescriptor();
    }

    @Override
    public boolean isObject(FrameSlot slot) {
        return wrapped.isObject(slot);
    }

    @Override
    public boolean isByte(FrameSlot slot) {
        return wrapped.isByte(slot);
    }

    @Override
    public boolean isBoolean(FrameSlot slot) {
        return wrapped.isBoolean(slot);
    }

    @Override
    public boolean isInt(FrameSlot slot) {
        return wrapped.isInt(slot);
    }

    @Override
    public boolean isLong(FrameSlot slot) {
        return wrapped.isLong(slot);
    }

    @Override
    public boolean isFloat(FrameSlot slot) {
        return wrapped.isFloat(slot);
    }

    @Override
    public boolean isDouble(FrameSlot slot) {
        return wrapped.isDouble(slot);
    }
}
