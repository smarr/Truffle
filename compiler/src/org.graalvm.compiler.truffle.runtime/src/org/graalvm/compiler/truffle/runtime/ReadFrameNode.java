package org.graalvm.compiler.truffle.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.UnsupportedSpecializationException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameRead;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeCost;

import sun.misc.Unsafe;

public final class ReadFrameNode extends Node implements FrameRead {
    private final FrameSlot slot;
    @CompilationFinal private int state;

    ReadFrameNode(FrameSlot slot) {
        this.slot = slot;
    }

    @Override
    public Object executeRead(Frame frameValue) {
        FrameWithoutBoxing frame = (FrameWithoutBoxing) frameValue;

        int state = this.state;
        int frameSlotIndex = slot.getIndex();
        byte tag;

        byte[] cachedTags = frame.getTags();
        try {
            tag = cachedTags[frameSlotIndex];
        } catch (ArrayIndexOutOfBoundsException e) {
            tag = frame.resizeAndGetTags()[frameSlotIndex];
        }

        if ((state & 0b1) != 0 /* is-state_0 readBoolean(Frame) */) {
            boolean condition = tag == FrameWithoutBoxing.BOOLEAN_TAG;
            if (condition) {
                long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
                long[] primitiveLocals = frame.getPrimitiveLocals();
                return FrameWithoutBoxing.unsafeGetInt(primitiveLocals, offset, condition, slot) != 0;
            }
        }
        if ((state & 0b10) != 0 /* is-state_0 readInt(Frame) */) {
            boolean condition = tag == FrameWithoutBoxing.INT_TAG;
            if (condition) {
                long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
                long[] primitiveLocals = frame.getPrimitiveLocals();
                return FrameWithoutBoxing.unsafeGetInt(primitiveLocals, offset, condition, slot);
            }
        }
        if ((state & 0b100) != 0 /* is-state_0 readLong(Frame) */) {
            boolean condition = tag == FrameWithoutBoxing.LONG_TAG;
            if (condition) {
                long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
                long[] primitiveLocals = frame.getPrimitiveLocals();
                return FrameWithoutBoxing.unsafeGetLong(primitiveLocals, offset, condition, slot);
            }
        }
        if ((state & 0b1000) != 0 /* is-state_0 readDouble(Frame) */) {
            boolean condition = tag == FrameWithoutBoxing.DOUBLE_TAG;
            if (condition) {
                long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
                long[] primitiveLocals = frame.getPrimitiveLocals();
                return FrameWithoutBoxing.unsafeGetDouble(primitiveLocals, offset, condition, slot);
            }
        }
        if ((state & 0b10000) != 0 /* is-state_0 readObject(Frame) */) {
            boolean condition = tag == FrameWithoutBoxing.OBJECT_TAG;
            if (condition) {
                return FrameWithoutBoxing.unsafeGetObject(frame.getLocals(), Unsafe.ARRAY_OBJECT_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_OBJECT_INDEX_SCALE, condition, slot);
            }
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return executeAndSpecialize(frameValue);
    }

    private Object executeAndSpecialize(Frame frameValue) {
        FrameWithoutBoxing frame = (FrameWithoutBoxing) frameValue;

        int state = this.state;
        int frameSlotIndex = slot.getIndex();
        byte tag;

        byte[] cachedTags = frame.getTags();
        try {
            tag = cachedTags[frameSlotIndex];
        } catch (ArrayIndexOutOfBoundsException e) {
            tag = frame.resizeAndGetTags()[frameSlotIndex];
        }

        boolean condition = tag == FrameWithoutBoxing.BOOLEAN_TAG;
        if (condition) {
            this.state = state = state | 0b1 /* add-state_0 readBoolean(Frame) */;
            long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
            long[] primitiveLocals = frame.getPrimitiveLocals();
            return FrameWithoutBoxing.unsafeGetInt(primitiveLocals, offset, condition, slot) != 0;
        }

        condition = tag == FrameWithoutBoxing.INT_TAG;
        if (condition) {
            this.state = state = state | 0b10 /* add-state_0 readInt(Frame) */;
            long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
            long[] primitiveLocals = frame.getPrimitiveLocals();
            return FrameWithoutBoxing.unsafeGetInt(primitiveLocals, offset, condition, slot);
        }

        condition = tag == FrameWithoutBoxing.LONG_TAG;
        if (condition) {
            this.state = state = state | 0b100 /* add-state_0 readLong(Frame) */;
            long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
            long[] primitiveLocals = frame.getPrimitiveLocals();
            return FrameWithoutBoxing.unsafeGetLong(primitiveLocals, offset, condition, slot);
        }

        condition = tag == FrameWithoutBoxing.DOUBLE_TAG;
        if (condition) {
            this.state = state = state | 0b1000 /* add-state_0 readDouble(Frame) */;
            long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
            long[] primitiveLocals = frame.getPrimitiveLocals();
            return FrameWithoutBoxing.unsafeGetDouble(primitiveLocals, offset, condition, slot);
        }

        condition = tag == FrameWithoutBoxing.OBJECT_TAG;
        if (condition) {
            this.state = state = state | 0b10000 /* add-state_0 readObject(Frame) */;
            return FrameWithoutBoxing.unsafeGetObject(frame.getLocals(), Unsafe.ARRAY_OBJECT_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_OBJECT_INDEX_SCALE, condition, slot);
        }

        throw new UnsupportedSpecializationException(this, new Node[]{});
    }

    @Override
    public NodeCost getCost() {
        int state = this.state;
        if (state == 0) {
            return NodeCost.UNINITIALIZED;
        } else {
            if ((state & (state - 1)) == 0 /* is-single-state_0 */) {
                return NodeCost.MONOMORPHIC;
            }
        }
        return NodeCost.POLYMORPHIC;
    }
}
