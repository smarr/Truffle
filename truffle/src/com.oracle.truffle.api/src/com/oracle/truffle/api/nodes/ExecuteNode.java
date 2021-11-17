package com.oracle.truffle.api.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public abstract class ExecuteNode extends Node {
    public abstract Object executeGeneric(VirtualFrame frame);

    public boolean executeBoolean(final VirtualFrame frame) throws UnexpectedResultException {
        Object value = executeGeneric(frame);
        if (value instanceof Boolean) {
            return (boolean) value;
        }
        throw new UnexpectedResultException(value);
    }

    public long executeLong(final VirtualFrame frame) throws UnexpectedResultException {
        Object value = executeGeneric(frame);
        if (value instanceof Long) {
            return (long) value;
        }
        throw new UnexpectedResultException(value);
    }

    public double executeDouble(final VirtualFrame frame) throws UnexpectedResultException {
        Object value = executeGeneric(frame);
        if (value instanceof Double) {
            return (double) value;
        }
        throw new UnexpectedResultException(value);
    }
}
