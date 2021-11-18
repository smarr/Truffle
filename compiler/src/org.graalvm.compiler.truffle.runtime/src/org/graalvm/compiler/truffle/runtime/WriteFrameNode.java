package org.graalvm.compiler.truffle.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecuteNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

import sun.misc.Unsafe;

public final class WriteFrameNode extends ExecuteNode {

    @Child private ExecuteNode exp;

    @CompilationFinal private int state;
    private final FrameSlot slot;

    WriteFrameNode(FrameSlot slot, ExecuteNode exp) {
        this.slot = slot;
        this.exp = insert(exp);
    }

    @Override
    public ExecuteNode getChild1() {
        return exp;
    }

    private static byte[] resizeOrError(FrameWithoutBoxing f) {
        if (!f.resize()) {
            throw new IllegalArgumentException("The frame slot is not known by the frame descriptor.");
        }
        return f.getTags();
    }

    private void executeBooleanWrite(VirtualFrame frame, boolean expValue) {
        FrameWithoutBoxing f = (FrameWithoutBoxing) frame;
        int frameSlotIndex = slot.getIndex();

        byte[] cachedTags = f.getTags();
        if (CompilerDirectives.inInterpreter() && frameSlotIndex >= cachedTags.length) {
            cachedTags = resizeOrError(f);
        }

        // -- verify set
        cachedTags[frameSlotIndex] = FrameWithoutBoxing.BOOLEAN_TAG;
        long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
        long[] primitiveLocals = f.getPrimitiveLocals();
        FrameWithoutBoxing.unsafePutInt(primitiveLocals, offset, expValue ? 1 : 0, slot);
    }

    private void executeLongWrite(VirtualFrame frame, long expValue) {
        FrameWithoutBoxing f = (FrameWithoutBoxing) frame;
        int frameSlotIndex = slot.getIndex();

        byte[] cachedTags = f.getTags();
        if (CompilerDirectives.inInterpreter() && frameSlotIndex >= cachedTags.length) {
            cachedTags = resizeOrError(f);
        }

        // -- verify set
        cachedTags[frameSlotIndex] = FrameWithoutBoxing.LONG_TAG;
        long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
        long[] primitiveLocals = f.getPrimitiveLocals();
        FrameWithoutBoxing.unsafePutLong(primitiveLocals, offset, expValue, slot);
    }

    private void executeDoubleWrite(VirtualFrame frame, double expValue) {
        FrameWithoutBoxing f = (FrameWithoutBoxing) frame;
        int frameSlotIndex = slot.getIndex();

        byte[] cachedTags = f.getTags();
        if (CompilerDirectives.inInterpreter() && frameSlotIndex >= cachedTags.length) {
            cachedTags = resizeOrError(f);
        }

        // -- verify set
        cachedTags[frameSlotIndex] = FrameWithoutBoxing.DOUBLE_TAG;
        long offset = Unsafe.ARRAY_LONG_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
        long[] primitiveLocals = f.getPrimitiveLocals();
        FrameWithoutBoxing.unsafePutDouble(primitiveLocals, offset, expValue, slot);
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        int state = this.state;
        if ((state & 0b1000) != 0 /* is-state_0 writeGeneric(VirtualFrame, Object) */) {
            Object expValue = this.exp.executeGeneric(frame);
            writeGeneric(frame, expValue);
            if (expValue instanceof Boolean) {
                return (boolean) expValue;
            }
            throw new UnexpectedResultException(expValue);
        }

        return executeBooleanState(frame, state);
    }

    public boolean executeBooleanState(VirtualFrame frame, int state) throws UnexpectedResultException {
        Object expObjectValue;
        try {
            boolean expValue = exp.executeBoolean(frame);
            if ((state & 0b1) != 0 /* writeBoolean */) {
                FrameSlotKind kind = slot.kind;
                // is boolean kind
                if (kind == FrameSlotKind.Boolean) {
                    executeBooleanWrite(frame, expValue);
                    return expValue;
                }
                if (kind == FrameSlotKind.Illegal) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    frame.getFrameDescriptor().setFrameSlotKind(slot, FrameSlotKind.Boolean);
                    executeBooleanWrite(frame, expValue);
                    return expValue;
                }
            }
            expObjectValue = expValue;
        } catch (UnexpectedResultException e) {
            expObjectValue = e.getResult();
        }

        CompilerDirectives.transferToInterpreterAndInvalidate();
        Object result = executeAndSpecialize(frame, expObjectValue);
        if (result instanceof Boolean) {
            return (boolean) result;
        }
        throw new UnexpectedResultException(result);
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        int state = this.state;
        if ((state & 0b1000) != 0 /* is-state_0 writeGeneric(VirtualFrame, Object) */) {
            Object expValue = this.exp.executeGeneric(frame);
            writeGeneric(frame, expValue);
            if (expValue instanceof Long) {
                return (long) expValue;
            }
            throw new UnexpectedResultException(expValue);
        }

        return executeLongState(frame, state);
    }

    public long executeLongState(VirtualFrame frame, int state) throws UnexpectedResultException {
        Object expObjectValue;
        try {
            long expValue = exp.executeLong(frame);
            if ((state & 0b10) != 0 /* writeLong */) {
                FrameSlotKind kind = slot.kind;
                if (kind == FrameSlotKind.Long) {
                    executeLongWrite(frame, expValue);
                    return expValue;
                }
                if (kind == FrameSlotKind.Illegal) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    frame.getFrameDescriptor().setFrameSlotKind(slot, FrameSlotKind.Long);
                    executeLongWrite(frame, expValue);
                    return expValue;
                }
            }
            expObjectValue = expValue;
        } catch (UnexpectedResultException e) {
            expObjectValue = e.getResult();
        }

        CompilerDirectives.transferToInterpreterAndInvalidate();
        Object result = executeAndSpecialize(frame, expObjectValue);
        if (result instanceof Long) {
            return (long) result;
        }
        throw new UnexpectedResultException(result);
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        int state = this.state;
        if ((state & 0b1000) != 0 /* is-state_0 writeGeneric(VirtualFrame, Object) */) {
            Object expValue = this.exp.executeGeneric(frame);
            writeGeneric(frame, expValue);
            if (expValue instanceof Double) {
                return (double) expValue;
            }
            throw new UnexpectedResultException(expValue);
        }

        return executeDoubleState(frame, state);
    }

    public double executeDoubleState(VirtualFrame frame, int state) throws UnexpectedResultException {
        Object expObjectValue;
        try {
            double expValue = exp.executeDouble(frame);
            if ((state & 0b100) != 0 /* writeDouble */) {
                FrameSlotKind kind = slot.kind;
                if (kind == FrameSlotKind.Double) {
                    executeDoubleWrite(frame, expValue);
                    return expValue;
                }
                if (kind == FrameSlotKind.Illegal) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    frame.getFrameDescriptor().setFrameSlotKind(slot, FrameSlotKind.Double);
                    executeDoubleWrite(frame, expValue);
                    return expValue;
                }
            }
            expObjectValue = expValue;
        } catch (UnexpectedResultException e) {
            expObjectValue = e.getResult();
        }

        CompilerDirectives.transferToInterpreterAndInvalidate();
        Object result = executeAndSpecialize(frame, expObjectValue);
        if (result instanceof Double) {
            return (double) result;
        }
        throw new UnexpectedResultException(result);
    }

    private Object executeAndSpecialize(VirtualFrame frameValue, Object expValue) {
        // This should only be called when we can actually still specialize anything
        assert (state & 0b1000) == 0;
        int state = this.state;
        if (expValue instanceof Boolean) {
            boolean expBoolValue = (boolean) expValue;
            FrameSlotKind kind = slot.kind;
            if (kind == FrameSlotKind.Boolean) {
                this.state = state = state | 0b1 /* add writeBoolean */;
                executeBooleanWrite(frameValue, expBoolValue);
                return expValue;
            }
            if (kind == FrameSlotKind.Illegal) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                this.state = state = state | 0b1 /* add writeBoolean */;
                frameValue.getFrameDescriptor().setFrameSlotKind(slot, FrameSlotKind.Boolean);
                executeBooleanWrite(frameValue, expBoolValue);
                return expValue;
            }
        }

        if (expValue instanceof Long) {
            long expLongValue = (long) expValue;
            FrameSlotKind kind = slot.kind;
            if (kind == FrameSlotKind.Long) {
                this.state = state = state | 0b10 /* add writeLong */;
                executeLongWrite(frameValue, expLongValue);
                return expValue;
            }
            if (kind == FrameSlotKind.Illegal) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                this.state = state = state | 0b10 /* add writeLong */;
                frameValue.getFrameDescriptor().setFrameSlotKind(slot, FrameSlotKind.Long);
                executeLongWrite(frameValue, expLongValue);
                return expValue;
            }
        }

        if (expValue instanceof Double) {
            double expDoubleValue = (double) expValue;
            FrameSlotKind kind = slot.kind;
            if (kind == FrameSlotKind.Double) {
                this.state = state = state | 0b100 /* add writeDouble */;
                executeDoubleWrite(frameValue, expDoubleValue);
                return expValue;
            }
            if (kind == FrameSlotKind.Illegal) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                this.state = state = state | 0b100 /* add writeDouble */;
                frameValue.getFrameDescriptor().setFrameSlotKind(slot, FrameSlotKind.Double);
                executeDoubleWrite(frameValue, expDoubleValue);
                return expValue;
            }
        }

        /* remove writeBoolean, writeLong, writeDouble */
        state = state & 0xfffffff8;

        /* add writeGeneric */
        this.state = state = state | 0b1000;

        return writeGeneric(frameValue, expValue);
    }

    private Object writeGeneric(VirtualFrame frameValue, Object expValue) {
        FrameSlotKind kind = slot.kind;
        if (kind != FrameSlotKind.Object) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            frameValue.getFrameDescriptor().setFrameSlotKind(slot, FrameSlotKind.Object);
        }

        FrameWithoutBoxing f = (FrameWithoutBoxing) frameValue;
        int frameSlotIndex = slot.getIndex();

        byte[] cachedTags = f.getTags();
        if (CompilerDirectives.inInterpreter() && frameSlotIndex >= cachedTags.length) {
            cachedTags = resizeOrError(f);
        }

        // -- verify set
        cachedTags[frameSlotIndex] = FrameWithoutBoxing.OBJECT_TAG;
        long offset = Unsafe.ARRAY_OBJECT_BASE_OFFSET + frameSlotIndex * (long) Unsafe.ARRAY_OBJECT_INDEX_SCALE;
        Object[] locals = f.getLocals();

        FrameWithoutBoxing.unsafePutObject(locals, offset, expValue, slot);
        return expValue;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        int state = this.state;
        /* only-active writeBoolean */
        try {
            if (state == 0b1) {
                return executeBooleanState(frame, state);
            } else if (state == 0b10) {
                return executeLongState(frame, state);
            } else if (state == 0b100) {
                return executeDoubleState(frame, state);
            }
        } catch (UnexpectedResultException e) {
            return e.getResult();
        }

        Object expValue = exp.executeGeneric(frame);

        if ((state & 0b1) != 0 && expValue instanceof Boolean) {
            boolean expBoolValue = (boolean) expValue;
            FrameSlotKind kind = slot.kind;
            if (kind == FrameSlotKind.Boolean) {
                this.state = state = state | 0b1 /* add writeBoolean */;
                executeBooleanWrite(frame, expBoolValue);
                return expValue;
            }
            if (kind == FrameSlotKind.Illegal) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                this.state = state = state | 0b1 /* add writeBoolean */;
                frame.getFrameDescriptor().setFrameSlotKind(slot, FrameSlotKind.Boolean);
                executeBooleanWrite(frame, expBoolValue);
                return expValue;
            }
        }

        if ((state & 0b10) != 0 && expValue instanceof Long) {
            long expLongValue = (long) expValue;
            FrameSlotKind kind = slot.kind;
            if (kind == FrameSlotKind.Long) {
                this.state = state = state | 0b10 /* add writeLong */;
                executeLongWrite(frame, expLongValue);
                return expValue;
            }
            if (kind == FrameSlotKind.Illegal) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                this.state = state = state | 0b10 /* add writeLong */;
                frame.getFrameDescriptor().setFrameSlotKind(slot, FrameSlotKind.Long);
                executeLongWrite(frame, expLongValue);
                return expValue;
            }
        }

        if ((state & 0b100) != 0 && expValue instanceof Double) {
            double expDoubleValue = (double) expValue;
            FrameSlotKind kind = slot.kind;
            if (kind == FrameSlotKind.Double) {
                this.state = state = state | 0b100 /* add writeDouble */;
                executeDoubleWrite(frame, expDoubleValue);
                return expValue;
            }
            if (kind == FrameSlotKind.Illegal) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                this.state = state = state | 0b100 /* add writeDouble */;
                frame.getFrameDescriptor().setFrameSlotKind(slot, FrameSlotKind.Double);
                executeDoubleWrite(frame, expDoubleValue);
                return expValue;
            }
        }

        if (state == 0b1000) {
            return writeGeneric(frame, expValue);
        }

        return executeAndSpecialize(frame, expValue);
    }

}
