package com.oracle.truffle.api;

import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;

public class AssumptionGroup implements Assumption {
    private static long globalEpoch = 0;

    private long allValidAtEpoch;

    @CompilerDirectives.CompilationFinal(dimensions = 1)
    private final Assumption[] assumptions;

    public AssumptionGroup(Assumption[] assumptions) {
        this.assumptions = assumptions;
        assert noNullAssumptions();
        if (isValidWithoutEpoch()) {
            allValidAtEpoch = globalEpoch;
        }
        assert isValid();
    }

    private boolean noNullAssumptions() {
        for (Assumption a : assumptions) {
            if (a == null) {
                return false;
            }
        }

        return true;
    }

    @Override
    @ExplodeLoop
    public void check() throws InvalidAssumptionException {
        if (CompilerDirectives.inInterpreter() && globalEpoch <= allValidAtEpoch) {
            // in interpreter, we use the globalEpoch, to shortcut the tests
            return;
        }

        for (Assumption a : assumptions) {
            a.check();
        }
    }

    @Override
    public boolean isValid() {
        if (CompilerDirectives.inInterpreter() && globalEpoch <= allValidAtEpoch) {
            // in interpreter, we use the globalEpoch, to shortcut the tests
            return true;
        }
        return isValidWithoutEpoch();
    }

    @ExplodeLoop
    private boolean isValidWithoutEpoch() {
        // if that fails, or if we are in compiled code, we check them all
        // in compiled code, this will be optimized out
        for (Assumption a : assumptions) {
            if (!a.isValid()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void invalidate() {
        for (Assumption a : assumptions) {
            a.invalidate();
        }
    }

    @Override
    public String getName() {
        return "AssumptionGroup";
    }

    public static void notifyOfInvalidation() {
        globalEpoch += 1;
    }

    public Assumption[] getAssumptions() {
        return assumptions;
    }
}
