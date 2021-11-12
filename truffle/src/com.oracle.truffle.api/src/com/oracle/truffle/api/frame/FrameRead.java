package com.oracle.truffle.api.frame;

public interface FrameRead {
    Object executeRead(Frame frame);
}
