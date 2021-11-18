package com.oracle.truffle.api.impl;

import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecuteNode;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

public class DefaultWriteFrameNode extends ExecuteNode {
    private final FrameSlot slot;
    @Child private ExecuteNode exp;

    DefaultWriteFrameNode(FrameSlot slot, ExecuteNode exp) {
        this.slot = slot;
        this.exp = insert(exp);
    }

    @Override
    public ExecuteNode getChild1() {
        return exp;
    }

    @Override
    public NodeCost getCost() {
        return NodeCost.MONOMORPHIC;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object value = exp.executeGeneric(frame);
        frame.setObject(slot, value);
        return value;
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        Object value;
        boolean ure = false;
        try {
            value = exp.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            value = e.getResult();
            ure = true;
        }
        frame.setObject(slot, value);
        if (!ure && value instanceof Boolean) {
            return (boolean) value;
        }

        throw new UnexpectedResultException(value);
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object value;
        boolean ure = false;
        try {
            value = exp.executeLong(frame);
        } catch (UnexpectedResultException e) {
            value = e.getResult();
            ure = true;
        }
        frame.setObject(slot, value);
        if (!ure && value instanceof Long) {
            return (long) value;
        }

        throw new UnexpectedResultException(value);
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object value;
        boolean ure = false;
        try {
            value = exp.executeDouble(frame);
        } catch (UnexpectedResultException e) {
            value = e.getResult();
            ure = true;
        }
        frame.setObject(slot, value);
        if (!ure && value instanceof Double) {
            return (double) value;
        }

        throw new UnexpectedResultException(value);
    }

}
