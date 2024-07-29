/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, 2023, Red Hat Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package com.oracle.objectfile.elf.dwarf.constants;

/**
 * All the Dwarf tags needed to type DIEs generated by GraalVM.
 */
public enum DwarfTag {
    DW_TAG_null(0),
    DW_TAG_array_type(0x01),
    DW_TAG_class_type(0x02),
    DW_TAG_formal_parameter(0x05),
    DW_TAG_member(0x0d),
    DW_TAG_pointer_type(0x0f),
    DW_TAG_compile_unit(0x11),
    DW_TAG_structure_type(0x13),
    DW_TAG_typedef(0x16),
    DW_TAG_union_type(0x17),
    DW_TAG_inheritance(0x1c),
    DW_TAG_inlined_subroutine(0x1d),
    DW_TAG_subrange_type(0x21),
    DW_TAG_base_type(0x24),
    DW_TAG_constant(0x27),
    DW_TAG_subprogram(0x2e),
    DW_TAG_variable(0x34),
    DW_TAG_namespace(0x39),
    DW_TAG_unspecified_type(0x3b),
    DW_TAG_type_unit(0x41);

    private final int value;

    DwarfTag(int i) {
        value = i;
    }

    public int value() {
        return value;
    }
}
