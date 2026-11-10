package ccsuite.ccacc.trim;

import module java.base;
import static java.lang.foreign.ValueLayout.*;

/** The Windows calls, through the Foreign Function API as {@code IdleTrim} makes its own: OpenProcess, K32GetProcessMemoryInfo, K32EmptyWorkingSet, GlobalMemoryStatusEx. Each answers null or 0 when unavailable. */
final class Win {

    static final int QUERY_LIMITED = 0x1000, SET_QUOTA = 0x0100;

    private static final MethodHandle OPEN, CLOSE, MEM, EMPTY, RAM, IO;

    static {
        MethodHandle open = null, close = null, mem = null, empty = null, ram = null, io = null;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                var linker = Linker.nativeLinker();
                var k = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
                open = linker.downcallHandle(k.find("OpenProcess").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
                close = linker.downcallHandle(k.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
                mem = linker.downcallHandle(k.find("K32GetProcessMemoryInfo").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
                empty = linker.downcallHandle(k.find("K32EmptyWorkingSet").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
                ram = linker.downcallHandle(k.find("GlobalMemoryStatusEx").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
                io = linker.downcallHandle(k.find("GetProcessIoCounters").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            } catch (Throwable e) {
                open = null;
            }
        }
        OPEN = open; CLOSE = close; MEM = mem; EMPTY = empty; RAM = ram; IO = io;
    }

    static boolean available() { return OPEN != null; }

    /** Working set and private bytes of a process, read with the query right only; null when it cannot be opened. */
    static Mem mem(long pid) { return with(pid, QUERY_LIMITED, Win::read); }

    /** Read plus write operations the process has made so far (GetProcessIoCounters); -1 when unreadable. An idle claude.exe makes none, a turn makes dozens. */
    static long ioOps(long pid) { return Optional.ofNullable(with(pid, QUERY_LIMITED, Win::readIo)).orElse(-1L); }

    /** EmptyWorkingSet on that process, then its memory; null when the process could not be opened or the call failed. */
    static Mem trim(long pid) {
        return with(pid, QUERY_LIMITED | SET_QUOTA, h -> {
            try {
                return (int) EMPTY.invoke(h) == 0 ? null : read(h);
            } catch (Throwable e) {
                return null;
            }
        });
    }

    /** Free physical RAM in bytes, 0 when unknown. */
    static long freeRam() {
        if (RAM == null) return 0;
        try (var a = Arena.ofConfined()) {
            var buf = a.allocate(64);
            buf.set(JAVA_INT, 0, 64);
            return (int) RAM.invoke(buf) == 0 ? 0 : buf.get(JAVA_LONG, 16);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static <T> T with(long pid, int access, Function<MemorySegment, T> f) {
        if (OPEN == null) return null;
        try {
            var h = (MemorySegment) OPEN.invoke(access, 0, (int) pid);
            if (h.equals(MemorySegment.NULL)) return null;
            try {
                return f.apply(h);
            } finally {
                var ignored = (int) CLOSE.invoke(h);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    /** IO_COUNTERS is 48 bytes: ReadOperationCount at 0, WriteOperationCount at 8 (the Other counters tick all the time, even idle, and are not used). */
    private static Long readIo(MemorySegment h) {
        try (var a = Arena.ofConfined()) {
            var buf = a.allocate(48);
            return (int) IO.invoke(h, buf) == 0 ? null : buf.get(JAVA_LONG, 0) + buf.get(JAVA_LONG, 8);
        } catch (Throwable e) {
            return null;
        }
    }

    /** PROCESS_MEMORY_COUNTERS_EX on x64 is 80 bytes: WorkingSetSize at 16, PrivateUsage at 72. */
    private static Mem read(MemorySegment h) {
        try (var a = Arena.ofConfined()) {
            var buf = a.allocate(80);
            buf.set(JAVA_INT, 0, 80);
            if ((int) MEM.invoke(h, buf, 80) == 0) return null;
            return new Mem().workingSet(buf.get(JAVA_LONG, 16)).privateBytes(buf.get(JAVA_LONG, 72));
        } catch (Throwable e) {
            return null;
        }
    }

    private Win() {}
}
