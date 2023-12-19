/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.posix.linux;

import static com.oracle.svm.core.Isolates.IMAGE_HEAP_A_RELOCATABLE_POINTER;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_BEGIN;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_END;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_RELOCATABLE_BEGIN;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_RELOCATABLE_END;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_WRITABLE_BEGIN;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_WRITABLE_END;
import static com.oracle.svm.core.posix.linux.ProcFSSupport.findMapping;
import static com.oracle.svm.core.util.UnsignedUtils.isAMultiple;
import static com.oracle.svm.core.util.UnsignedUtils.roundUp;
import static org.graalvm.word.WordFactory.signed;

import java.util.concurrent.ThreadLocalRandom;

import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.ComparableWord;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.SignedWord;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.c.CGlobalData;
import com.oracle.svm.core.c.CGlobalDataFactory;
import com.oracle.svm.core.c.function.CEntryPointErrors;
import com.oracle.svm.core.code.DynamicMethodAddressResolutionHeapSupport;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.headers.LibC;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.os.AbstractImageHeapProvider;
import com.oracle.svm.core.os.VirtualMemoryProvider;
import com.oracle.svm.core.os.VirtualMemoryProvider.Access;
import com.oracle.svm.core.posix.PosixUtils;
import com.oracle.svm.core.posix.headers.Fcntl;
import com.oracle.svm.core.posix.headers.Unistd;
import com.oracle.svm.core.util.VMError;

import jdk.graal.compiler.word.Word;

/**
 * An optimal image heap provider for Linux which creates isolate image heaps that retain the
 * copy-on-write, lazy loading and reclamation semantics provided by the original heap's backing
 * resource.
 *
 * This is accomplished by discovering the backing executable or shared object file the kernel has
 * mmapped to the original heap image virtual address, as well as the location in the file storing
 * the original heap. A new memory map is created to a new virtual range pointing to this same
 * location. This allows the kernel to share the same physical pages between multiple heaps that
 * have not been modified, as well as lazily load them only when needed.
 *
 * The implementation avoids dirtying the pages of the original, and only referencing what is
 * strictly required.
 */
public class LinuxImageHeapProvider extends AbstractImageHeapProvider {
    /** Magic value to verify that a located image file matches our loaded image. */
    public static final CGlobalData<Pointer> MAGIC = CGlobalDataFactory.createWord(WordFactory.<Word> signed(ThreadLocalRandom.current().nextLong()));

    private static final CGlobalData<CCharPointer> PROC_SELF_MAPS = CGlobalDataFactory.createCString("/proc/self/maps");

    private static final SignedWord UNASSIGNED_FD = signed(-1);
    private static final SignedWord CANNOT_OPEN_FD = signed(-2);
    private static final CGlobalData<WordPointer> CACHED_IMAGE_FD = CGlobalDataFactory.createWord(UNASSIGNED_FD);
    private static final CGlobalData<WordPointer> CACHED_IMAGE_HEAP_OFFSET = CGlobalDataFactory.createWord();

    @Override
    public boolean guaranteesHeapPreferredAddressSpaceAlignment() {
        return true;
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected UnsignedWord getImageHeapAddressSpaceSize() {
        int imageHeapOffset = Heap.getHeap().getImageHeapOffsetInAddressSpace();
        assert imageHeapOffset >= 0;
        UnsignedWord size = WordFactory.unsigned(imageHeapOffset);
        size = size.add(getImageHeapSizeInFile(IMAGE_HEAP_BEGIN.get(), IMAGE_HEAP_END.get()));
        return size;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected UnsignedWord getTotalRequiredAddressSpaceSize() {
        UnsignedWord size = getImageHeapAddressSpaceSize();
        if (DynamicMethodAddressResolutionHeapSupport.isEnabled()) {
            size = size.add(DynamicMethodAddressResolutionHeapSupport.get().getDynamicMethodAddressResolverPreHeapMemoryBytes());
        }
        return size;
    }

    @Override
    @Uninterruptible(reason = "Called during isolate initialization.")
    public int initialize(Pointer reservedAddressSpace, UnsignedWord reservedSize, WordPointer basePointer, WordPointer endPointer) {
        Pointer heapBase;
        Pointer allocatedMemory = WordFactory.nullPointer();
        UnsignedWord requiredSize = getTotalRequiredAddressSpaceSize();
        if (reservedAddressSpace.isNull()) {
            UnsignedWord alignment = WordFactory.unsigned(Heap.getHeap().getPreferredAddressSpaceAlignment());
            allocatedMemory = VirtualMemoryProvider.get().reserve(requiredSize, alignment, false);
            if (allocatedMemory.isNull()) {
                return CEntryPointErrors.RESERVE_ADDRESS_SPACE_FAILED;
            }
            heapBase = allocatedMemory;
        } else {
            if (reservedSize.belowThan(requiredSize)) {
                return CEntryPointErrors.INSUFFICIENT_ADDRESS_SPACE;
            }
            heapBase = reservedAddressSpace;
        }
        UnsignedWord remainingSize = requiredSize;

        if (DynamicMethodAddressResolutionHeapSupport.isEnabled()) {
            int error = DynamicMethodAddressResolutionHeapSupport.get().initialize();
            if (error != CEntryPointErrors.NO_ERROR) {
                freeImageHeap(allocatedMemory);
                return error;
            }

            UnsignedWord preHeapRequiredBytes = DynamicMethodAddressResolutionHeapSupport.get().getDynamicMethodAddressResolverPreHeapMemoryBytes();
            heapBase = heapBase.add(preHeapRequiredBytes);
            remainingSize = remainingSize.subtract(preHeapRequiredBytes);

            Pointer installOffset = heapBase.subtract(DynamicMethodAddressResolutionHeapSupport.get().getRequiredPreHeapMemoryInBytes());
            error = DynamicMethodAddressResolutionHeapSupport.get().install(installOffset);
            if (error != CEntryPointErrors.NO_ERROR) {
                freeImageHeap(allocatedMemory);
                return error;
            }
        }

        int imageHeapOffsetInAddressSpace = Heap.getHeap().getImageHeapOffsetInAddressSpace();
        basePointer.write(heapBase);
        Pointer firstBase = heapBase.add(imageHeapOffsetInAddressSpace);
        remainingSize = remainingSize.subtract(imageHeapOffsetInAddressSpace);
        int result = initializeImageHeap(firstBase, remainingSize, endPointer,
                        CACHED_IMAGE_FD.get(), CACHED_IMAGE_HEAP_OFFSET.get(), MAGIC.get(),
                        IMAGE_HEAP_BEGIN.get(), IMAGE_HEAP_END.get(),
                        IMAGE_HEAP_RELOCATABLE_BEGIN.get(), IMAGE_HEAP_A_RELOCATABLE_POINTER.get(), IMAGE_HEAP_RELOCATABLE_END.get(),
                        IMAGE_HEAP_WRITABLE_BEGIN.get(), IMAGE_HEAP_WRITABLE_END.get());
        if (result != CEntryPointErrors.NO_ERROR) {
            freeImageHeap(allocatedMemory);
        }
        return result;
    }

    @Uninterruptible(reason = "Called during isolate initialization.")
    private int initializeImageHeap(Pointer imageHeap, UnsignedWord reservedSize, WordPointer endPointer, WordPointer cachedFd, WordPointer cachedOffset,
                    Pointer magicAddress, Word heapBeginSym, Word heapEndSym, Word heapRelocsSym, Pointer heapAnyRelocPointer, Word heapRelocsEndSym, Word heapWritableSym, Word heapWritableEndSym) {
        assert heapBeginSym.belowOrEqual(heapWritableSym) && heapWritableSym.belowOrEqual(heapWritableEndSym) && heapWritableEndSym.belowOrEqual(heapEndSym);
        assert heapBeginSym.belowOrEqual(heapRelocsSym) && heapRelocsSym.belowOrEqual(heapRelocsEndSym) && heapRelocsEndSym.belowOrEqual(heapEndSym);
        assert heapAnyRelocPointer.isNull() || (heapRelocsSym.belowOrEqual(heapAnyRelocPointer) && heapAnyRelocPointer.belowThan(heapRelocsEndSym));

        SignedWord fd = cachedFd.read();

        /*
         * Find and open the image file. We cache the file descriptor and the determined offset in
         * the file for subsequent isolate initializations. We intentionally allow racing in this
         * step to avoid stalling threads.
         */
        if (fd.equal(UNASSIGNED_FD)) {
            int opened = openImageFile(cachedOffset, heapBeginSym, heapRelocsSym, magicAddress);
            SignedWord previous = ((Pointer) cachedFd).compareAndSwapWord(0, fd, signed(opened), LocationIdentity.ANY_LOCATION);
            if (previous.equal(fd)) {
                fd = signed(opened);
            } else {
                if (opened >= 0) {
                    Unistd.NoTransitions.close(opened);
                }
                fd = previous;
            }
        }

        UnsignedWord pageSize = VirtualMemoryProvider.get().getGranularity();
        UnsignedWord imageHeapSize = getImageHeapSizeInFile(heapBeginSym, heapEndSym);
        assert reservedSize.aboveOrEqual(imageHeapSize);
        if (endPointer.isNonNull()) {
            endPointer.write(roundUp(imageHeap.add(imageHeapSize), pageSize));
        }

        /*
         * If we cannot find or open the image file, fall back to copy it from memory (the image
         * heap must be in pristine condition for that).
         */
        if (fd.equal(CANNOT_OPEN_FD)) {
            return initializeImageHeapByCopying(imageHeap, heapBeginSym, heapEndSym, heapWritableSym, heapWritableEndSym, imageHeapSize);
        }

        // Create memory mappings from the image file.
        UnsignedWord fileOffset = cachedOffset.read();
        imageHeap = VirtualMemoryProvider.get().mapFile(imageHeap, imageHeapSize, fd, fileOffset, Access.READ);
        if (imageHeap.isNull()) {
            return CEntryPointErrors.MAP_HEAP_FAILED;
        }

        if (heapAnyRelocPointer.isNonNull()) {
            ComparableWord relocatedValue = heapAnyRelocPointer.readWord(0);
            ComparableWord mappedValue = imageHeap.readWord(heapAnyRelocPointer.subtract(heapBeginSym));
            if (relocatedValue.notEqual(mappedValue)) {
                /*
                 * Addresses were relocated by dynamic linker, so copy them, but first remap the
                 * pages to avoid swapping them in from disk.
                 */
                Pointer relocsBegin = imageHeap.add(heapRelocsSym.subtract(heapBeginSym));
                UnsignedWord relocsSize = heapRelocsEndSym.subtract(heapRelocsSym);
                if (!isAMultiple(relocsSize, pageSize)) {
                    return CEntryPointErrors.PROTECT_HEAP_FAILED;
                }
                Pointer committedRelocsBegin = VirtualMemoryProvider.get().commit(relocsBegin, relocsSize, Access.READ | Access.WRITE);
                if (committedRelocsBegin.isNull() || committedRelocsBegin != relocsBegin) {
                    return CEntryPointErrors.PROTECT_HEAP_FAILED;
                }
                LibC.memcpy(relocsBegin, heapRelocsSym, relocsSize);
                if (VirtualMemoryProvider.get().protect(relocsBegin, relocsSize, Access.READ) != 0) {
                    return CEntryPointErrors.PROTECT_HEAP_FAILED;
                }
            }
        }

        // Unprotect writable pages.
        Pointer writableBegin = imageHeap.add(heapWritableSym.subtract(heapBeginSym));
        UnsignedWord writableSize = heapWritableEndSym.subtract(heapWritableSym);
        if (VirtualMemoryProvider.get().protect(writableBegin, writableSize, Access.READ | Access.WRITE) != 0) {
            return CEntryPointErrors.PROTECT_HEAP_FAILED;
        }

        return CEntryPointErrors.NO_ERROR;
    }

    @Uninterruptible(reason = "Called during isolate initialization.")
    private static int initializeImageHeapByCopying(Pointer imageHeap, Word heapBeginSym, Word heapEndSym, Word heapWritableSym, Word heapWritableEndSym, UnsignedWord imageHeapSize) {
        Pointer committedBegin = VirtualMemoryProvider.get().commit(imageHeap, imageHeapSize, Access.READ | Access.WRITE);
        if (committedBegin.isNull()) {
            return CEntryPointErrors.MAP_HEAP_FAILED;
        }
        LibC.memcpy(imageHeap, heapBeginSym, imageHeapSize);

        Word readOnlyBytesAtBegin = heapWritableSym.subtract(heapBeginSym);
        if (readOnlyBytesAtBegin.aboveThan(0) && VirtualMemoryProvider.get().protect(imageHeap, readOnlyBytesAtBegin, Access.READ) != 0) {
            return CEntryPointErrors.PROTECT_HEAP_FAILED;
        }
        Pointer writableEnd = imageHeap.add(heapWritableEndSym.subtract(heapBeginSym));
        Word readOnlyBytesAtEnd = heapEndSym.subtract(heapWritableEndSym);
        if (readOnlyBytesAtEnd.aboveThan(0) && VirtualMemoryProvider.get().protect(writableEnd, readOnlyBytesAtEnd, Access.READ) != 0) {
            return CEntryPointErrors.PROTECT_HEAP_FAILED;
        }
        return CEntryPointErrors.NO_ERROR;
    }

    /**
     * Locate our image file, containing the image heap. Unfortunately we must open it by its path.
     *
     * NOTE: we look for the relocatables partition of the linker-mapped heap because it always
     * stays mapped, while the rest of the linker-mapped heap can be unmapped after tearing down the
     * first isolate. We do not use /proc/self/exe because it breaks with some tools like Valgrind.
     */
    @Uninterruptible(reason = "Called during isolate initialization.")
    private static int openImageFile(WordPointer cachedOffset, Word heapBeginSym, Word heapRelocsSym, Pointer magicAddress) {
        final int failfd = (int) CANNOT_OPEN_FD.rawValue();
        int mapfd = Fcntl.NoTransitions.open(PROC_SELF_MAPS.get(), Fcntl.O_RDONLY(), 0);
        if (mapfd == -1) {
            return failfd;
        }
        final int bufferSize = 4096; // assumed MAX_PATHLEN
        CCharPointer buffer = StackValue.get(bufferSize);

        // The relocatables partition might stretch over two adjacent mappings due to permission
        // differences, so only locate the mapping for the first page of relocatables
        UnsignedWord pageSize = VirtualMemoryProvider.get().getGranularity();
        WordPointer relocsMappingStart = StackValue.get(WordPointer.class);
        WordPointer relocsMappingFileOffset = StackValue.get(WordPointer.class);
        boolean found = findMapping(mapfd, buffer, bufferSize, heapRelocsSym, heapRelocsSym.add(pageSize), relocsMappingStart, relocsMappingFileOffset, true);
        if (!found) {
            Unistd.NoTransitions.close(mapfd);
            return failfd;
        }
        int opened = Fcntl.NoTransitions.open(buffer, Fcntl.O_RDONLY(), 0);
        if (opened < 0) {
            Unistd.NoTransitions.close(mapfd);
            return failfd;
        }

        boolean valid = magicAddress.isNull() || checkImageFileMagic(mapfd, opened, buffer, bufferSize, magicAddress);
        Unistd.NoTransitions.close(mapfd);
        if (!valid) {
            Unistd.NoTransitions.close(opened);
            return failfd;
        }

        Word imageHeapRelocsOffset = heapRelocsSym.subtract(heapBeginSym);
        Word imageHeapOffset = heapRelocsSym.subtract(relocsMappingStart.read()).subtract(imageHeapRelocsOffset);
        UnsignedWord fileOffset = imageHeapOffset.add(relocsMappingFileOffset.read());
        cachedOffset.write(fileOffset);
        return opened;
    }

    @Uninterruptible(reason = "Called during isolate initialization.")
    private static boolean checkImageFileMagic(int mapfd, int imagefd, CCharPointer buffer, int bufferSize, Pointer magicAddress) {
        if (Unistd.NoTransitions.lseek(mapfd, signed(0), Unistd.SEEK_SET()).notEqual(0)) {
            return false;
        }

        // Find the offset of the magic word in the image file. We cannot reliably compute it
        // from the image heap offset below because it might be in a different file segment.
        int wordSize = ConfigurationValues.getTarget().wordSize;
        WordPointer magicMappingStart = StackValue.get(WordPointer.class);
        WordPointer magicMappingFileOffset = StackValue.get(WordPointer.class);
        boolean found = findMapping(mapfd, buffer, bufferSize, magicAddress, magicAddress.add(wordSize), magicMappingStart, magicMappingFileOffset, false);
        if (!found) {
            return false;
        }
        Word magicFileOffset = (Word) magicAddress.subtract(magicMappingStart.read()).add(magicMappingFileOffset.read());

        // Compare the magic word in memory with the magic word read from the file
        if (Unistd.NoTransitions.lseek(imagefd, magicFileOffset, Unistd.SEEK_SET()).notEqual(magicFileOffset)) {
            return false;
        }

        if (PosixUtils.readBytes(imagefd, buffer, wordSize, 0) != wordSize) {
            return false;
        }
        Word fileMagic = ((WordPointer) buffer).read();
        return fileMagic.equal(magicAddress.readWord(0));
    }

    @Override
    @Uninterruptible(reason = "Called during isolate tear-down.")
    public int freeImageHeap(PointerBase heapBase) {
        if (heapBase.isNull()) { // no memory allocated
            return CEntryPointErrors.NO_ERROR;
        }
        VMError.guarantee(heapBase.notEqual(IMAGE_HEAP_BEGIN.get()), "reusing the image heap is no longer supported");

        UnsignedWord totalAddressSpaceSize = getTotalRequiredAddressSpaceSize();
        Pointer addressSpaceStart = (Pointer) heapBase;
        if (DynamicMethodAddressResolutionHeapSupport.isEnabled()) {
            UnsignedWord preHeapRequiredBytes = DynamicMethodAddressResolutionHeapSupport.get().getDynamicMethodAddressResolverPreHeapMemoryBytes();
            addressSpaceStart = addressSpaceStart.subtract(preHeapRequiredBytes);
        }
        if (VirtualMemoryProvider.get().free(addressSpaceStart, totalAddressSpaceSize) != 0) {
            return CEntryPointErrors.FREE_IMAGE_HEAP_FAILED;
        }
        return CEntryPointErrors.NO_ERROR;
    }

    @Override
    protected UnsignedWord getImageHeapSizeInFile() {
        throw VMError.shouldNotReachHere("use the variant that takes pointers");
    }
}
