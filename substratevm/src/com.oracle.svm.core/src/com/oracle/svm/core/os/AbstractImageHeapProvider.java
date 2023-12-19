/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.os;

import static com.oracle.svm.core.Isolates.IMAGE_HEAP_BEGIN;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_END;

import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.heap.Heap;

import jdk.graal.compiler.word.Word;

public abstract class AbstractImageHeapProvider implements ImageHeapProvider {
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected UnsignedWord getImageHeapAddressSpaceSize() {
        UnsignedWord imageHeapSizeInFile = getImageHeapSizeInFile();
        return imageHeapSizeInFile.add(Heap.getHeap().getImageHeapOffsetInAddressSpace());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected static UnsignedWord getImageHeapSizeInFile(Word beginAddress, Word endAddress) {
        assert endAddress.aboveOrEqual(endAddress);
        return endAddress.subtract(beginAddress);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected static UnsignedWord getImageHeapSizeInFile() {
        return getImageHeapSizeInFile(IMAGE_HEAP_BEGIN.get(), IMAGE_HEAP_END.get());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected static Pointer getImageHeapBegin(Pointer heapBase) {
        return heapBase.add(Heap.getHeap().getImageHeapOffsetInAddressSpace());
    }
}
