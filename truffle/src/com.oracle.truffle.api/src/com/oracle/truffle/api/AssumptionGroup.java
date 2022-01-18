package com.oracle.truffle.api;

import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;

import java.util.Arrays;

public final class AssumptionGroup implements Assumption {
    private static long globalEpoch = 0;

    private long allValidAtEpoch;

    public static Assumption createFromAssumptions(Object... assumptions) {
        if (assumptions.length == 1) {
            if (assumptions[0].getClass() == Assumption[].class) {
                return create((Assumption[]) assumptions[0]);
            } else {
                return (Assumption) assumptions[0];
            }
        }

        int numAssumptions = 0;
        for (Object a : assumptions) {
            if (a.getClass() == Assumption[].class) {
                Assumption[] as = (Assumption[]) a;
                numAssumptions += as.length;
            } else {
                numAssumptions += 1;
            }
        }

        Assumption[] aArray = new Assumption[numAssumptions];

        int i = 0;
        for (Object a : assumptions) {
            if (a.getClass() == Assumption[].class) {
                Assumption[] as = (Assumption[]) a;

                for (Assumption aa : as) {
                    aArray[i] = aa;
                    i += 1;
                }

            } else {
                aArray[i] = (Assumption) a;
                i += 1;
            }
        }

        return create(aArray);
    }

    public static Assumption create(Assumption[] arr, Assumption a) {
        Assumption[] newArr = Arrays.copyOf(arr, arr.length + 1);
        newArr[arr.length] = a;
        return new AssumptionGroup(newArr);
    }

    public static Assumption create(Assumption a1, Assumption a2) {
        return new AssumptionGroup(new Assumption[] {a1, a2});
    }

    public static Assumption create(Assumption[] assumptions) {
        assert noNullAssumptions(assumptions);
        if (assumptions.length == 1) {
            return assumptions[0];
        }
        return new AssumptionGroup(assumptions);
    }

    private static boolean noNullAssumptions(Assumption[] assumptions) {
        for (Assumption a : assumptions) {
            if (a == null) {
                return false;
            }
        }
        return true;
    }

    @CompilerDirectives.CompilationFinal(dimensions = 1)
    private final Assumption[] assumptions;

    private AssumptionGroup(Assumption[] assumptions) {
        this.assumptions = assumptions;

        if (isValidWithoutEpoch()) {
            allValidAtEpoch = globalEpoch;
        } else {
            allValidAtEpoch = -1;
        }
    }



    @Override
    @ExplodeLoop
    public void check() throws InvalidAssumptionException {
        if (CompilerDirectives.inInterpreter() && globalEpoch <= allValidAtEpoch) {
            // in interpreter, we use the globalEpoch, to shortcut the tests
            return;
        }

        if (assumptions == null) {
            return;
        }

        for (Assumption a : assumptions) {
            if (a != null) {
                a.check();
            }
        }

        allValidAtEpoch = globalEpoch;
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
        CompilerAsserts.partialEvaluationConstant(assumptions);

        // if that fails, or if we are in compiled code, we check them all
        // in compiled code, this will be optimized out
        for (Assumption a : assumptions) {
            if (a == null || !a.isValid()) {
                return false;
            }
        }

        if (CompilerDirectives.inInterpreter()) {
            allValidAtEpoch = globalEpoch;
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
