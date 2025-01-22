/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.constantpool;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.espresso.classfile.descriptors.Symbol;
import com.oracle.truffle.espresso.classfile.descriptors.Type;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.runtime.EspressoException;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

public final class FailedCallSiteLink extends AbstractStickyFailure implements CallSiteLink {
    private final Method method;
    private final int bci;

    public FailedCallSiteLink(Method method, int bci, EspressoException failure) {
        super(failure);
        this.method = method;
        this.bci = bci;
    }

    @Override
    public boolean isFailed() {
        return true;
    }

    @Override
    public boolean matchesCallSite(Method siteMethod, int siteBci) {
        return bci == siteBci && method == siteMethod;
    }

    @Override
    public StaticObject getMemberName() {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw EspressoError.shouldNotReachHere("Exception should have been thrown earlier by calling checkFail.");
    }

    @Override
    public StaticObject getUnboxedAppendix() {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw EspressoError.shouldNotReachHere("Exception should have been thrown earlier by calling checkFail.");
    }

    @Override
    public Symbol<Type>[] getParsedSignature() {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw EspressoError.shouldNotReachHere("Exception should have been thrown earlier by calling checkFail.");
    }
}
