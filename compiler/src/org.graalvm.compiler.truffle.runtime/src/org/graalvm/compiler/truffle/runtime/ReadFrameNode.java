package org.graalvm.compiler.truffle.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.UnsupportedSpecializationException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecuteNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

import sun.misc.Unsafe;

public final class ReadFrameNode extends ExecuteNode {
    private final FrameSlot slot;
    private final Object uninitializedValue;

    @CompilationFinal private int state;

    ReadFrameNode(FrameSlot slot, Object uninitializedValue) {
        this.slot = slot;
        this.uninitializedValue = uninitializedValue;
    }

    @Override
    public boolean executeBoolean(VirtualFrame frameValue) throws UnexpectedResultException {
        int state = this.state;

        if ((state & 0b10) != 0 /* is-state_0 doBoolean(VirtualFrame) */) {
            FrameWithoutBoxing frame = (FrameWithoutBoxing) frameValue;
            int frameSlotIndex = slot.getIndex();
            byte tag;

            byte[] cachedTags = frame.getTags();
            try {
                tag = cachedTags[frameSlotIndex];
            } catch (ArrayIndexOutOfBoundsException e) {
                frame.resize();
                cachedTags = frame.getTags();
                tag = cachedTags[frameSlotIndex];
            }

            boolean condition = tag == FrameWithoutBoxing.BOOLEAN_TAG;
            if (condition) {
                long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
                long[] primitiveLocals = frame.getPrimitiveLocals();
                return FrameWithoutBoxing.unsafeGetInt(primitiveLocals, offset, condition, slot) != 0;
            }
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        Object value = executeAndSpecialize(frameValue);
        if (value instanceof Boolean) {
            return (boolean) value;
        }

        throw new UnexpectedResultException(value);
    }

    @Override
    public long executeLong(VirtualFrame frameValue) throws UnexpectedResultException {
        int state = this.state;

        if ((state & 0b100) != 0 /* is-state_0 readLong(Frame) */) {
            FrameWithoutBoxing frame = (FrameWithoutBoxing) frameValue;
            int frameSlotIndex = slot.getIndex();
            byte tag;

            byte[] cachedTags = frame.getTags();
            try {
                tag = cachedTags[frameSlotIndex];
            } catch (ArrayIndexOutOfBoundsException e) {
                frame.resize();
                cachedTags = frame.getTags();
                tag = cachedTags[frameSlotIndex];
            }

            boolean condition = tag == FrameWithoutBoxing.LONG_TAG;
            if (condition) {
                long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
                long[] primitiveLocals = frame.getPrimitiveLocals();
                return FrameWithoutBoxing.unsafeGetLong(primitiveLocals, offset, condition, slot);
            }
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        Object value = executeAndSpecialize(frameValue);
        if (value instanceof Long) {
            return (long) value;
        }

        throw new UnexpectedResultException(value);
    }

    @Override
    public double executeDouble(VirtualFrame frameValue) throws UnexpectedResultException {
        int state = this.state;

        if ((state & 0b1000) != 0 /* is-state_0 readDouble(Frame) */) {
            FrameWithoutBoxing frame = (FrameWithoutBoxing) frameValue;
            int frameSlotIndex = slot.getIndex();
            byte tag;

            byte[] cachedTags = frame.getTags();
            try {
                tag = cachedTags[frameSlotIndex];
            } catch (ArrayIndexOutOfBoundsException e) {
                frame.resize();
                cachedTags = frame.getTags();
                tag = cachedTags[frameSlotIndex];
            }

            boolean condition = tag == FrameWithoutBoxing.DOUBLE_TAG;
            if (condition) {
                long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
                long[] primitiveLocals = frame.getPrimitiveLocals();
                return FrameWithoutBoxing.unsafeGetDouble(primitiveLocals, offset, condition, slot);
            }
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        Object value = executeAndSpecialize(frameValue);
        if (value instanceof Double) {
            return (double) value;
        }

        throw new UnexpectedResultException(value);
    }

    @Override
    public Object executeGeneric(VirtualFrame frameValue) {
        FrameWithoutBoxing frame = (FrameWithoutBoxing) frameValue;

        int state = this.state;
        int frameSlotIndex = slot.getIndex();
        byte tag;

        byte[] cachedTags = frame.getTags();
        try {
            tag = cachedTags[frameSlotIndex];
        } catch (ArrayIndexOutOfBoundsException e) {
            frame.resize();
            cachedTags = frame.getTags();
            tag = cachedTags[frameSlotIndex];
        }

        if ((state & 0b1) != 0 /* uninitialized */) {
            boolean condition = tag == FrameWithoutBoxing.ILLEGAL_TAG;
            if (condition) {
                return uninitializedValue;
            }
        }

        if ((state & 0b10) != 0 /* is-state_0 readBoolean(Frame) */) {
            boolean condition = tag == FrameWithoutBoxing.BOOLEAN_TAG;
            if (condition) {
                long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
                long[] primitiveLocals = frame.getPrimitiveLocals();
                return FrameWithoutBoxing.unsafeGetInt(primitiveLocals, offset, condition, slot) != 0;
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
            frame.resize();
            cachedTags = frame.getTags();
            tag = cachedTags[frameSlotIndex];
        }

        boolean condition = tag == FrameWithoutBoxing.ILLEGAL_TAG;
        if (condition) {
            this.state = state = state | 0b1 /* add-state_0 readInt(Frame) */;
            return uninitializedValue;
        }

        condition = tag == FrameWithoutBoxing.BOOLEAN_TAG;
        if (condition) {
            this.state = state = state | 0b10 /* add-state_0 readBoolean(Frame) */;
            long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
            long[] primitiveLocals = frame.getPrimitiveLocals();
            return FrameWithoutBoxing.unsafeGetInt(primitiveLocals, offset, condition, slot) != 0;
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
