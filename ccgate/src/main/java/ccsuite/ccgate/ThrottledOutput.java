package ccsuite.ccgate;

import module java.base;

/**
 * Delivers an answer no faster than a chosen number of bytes per second: the owner's way of slowing a caller down
 * without refusing it. Writes go out in small slices, each flushed, so a stream keeps arriving steadily instead of in
 * bursts; the pause before each slice keeps the average at the limit, whatever the size of the chunks the upstream sends.
 */
final class ThrottledOutput extends FilterOutputStream {

    final long bytesPerSecond;
    final long start = System.nanoTime();
    long sent;

    ThrottledOutput(OutputStream out, long bytesPerSecond) {
        super(out);
        this.bytesPerSecond = Math.max(1, bytesPerSecond);
    }

    @Override public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override public void write(byte[] b, int off, int len) throws IOException {
        var slice = (int) Math.max(1, Math.min(1024, bytesPerSecond / 10));
        for (var done = 0; done < len; ) {
            var n = Math.min(slice, len - done);
            pause();
            out.write(b, off + done, n);
            out.flush();
            sent += n;
            done += n;
        }
    }

    /** Waits until the bytes sent so far are no further ahead of the clock than the limit allows. */
    void pause() throws IOException {
        var due = start + sent * 1_000_000_000L / bytesPerSecond;
        var wait = due - System.nanoTime();
        if (wait <= 0) return;
        try {
            Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("throttled answer interrupted");
        }
    }
}
