package com.oracle.truffle.api.impl;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameRead;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeCost;

public final class DefaultReadFrameNode extends Node implements FrameRead {
    private final FrameSlot slot;

    DefaultReadFrameNode(FrameSlot slot) {
        this.slot = slot;
    }

    @Override
    public Object executeRead(Frame frameValue) {
        return frameValue.getValue(slot);
    }

    @Override
    public NodeCost getCost() {
        return NodeCost.MONOMORPHIC;
    }
}
