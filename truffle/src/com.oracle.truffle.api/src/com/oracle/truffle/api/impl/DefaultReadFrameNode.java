package com.oracle.truffle.api.impl;

import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecuteNode;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

public final class DefaultReadFrameNode extends ExecuteNode {
    private final FrameSlot slot;

    DefaultReadFrameNode(FrameSlot slot, Object uninitializedValue) {
        this.slot = slot;
    }

    @Override
    public NodeCost getCost() {
        return NodeCost.MONOMORPHIC;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return frame.getValue(slot);
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        Object value = frame.getValue(slot);
        if (value instanceof Boolean) {
            return (boolean) value;
        }

        throw new UnexpectedResultException(value);
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object value = frame.getValue(slot);
        if (value instanceof Long) {
            return (long) value;
        }

        throw new UnexpectedResultException(value);
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object value = frame.getValue(slot);
        if (value instanceof Double) {
            return (double) value;
        }

        throw new UnexpectedResultException(value);
    }
}
