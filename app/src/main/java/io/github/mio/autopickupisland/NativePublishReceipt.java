package io.github.mio.autopickupisland;

/** Cross-system transport state, not a replacement for ColorOS order policy. */
final class NativePublishReceipt {
    enum State { PENDING, POSTED, FAILED, EXPIRED }
    private final long deadline;
    private State state = State.PENDING;

    NativePublishReceipt(long deadline) { this.deadline = deadline; }

    synchronized boolean canPost(long now) {
        if (state == State.PENDING && now >= deadline) state = State.EXPIRED;
        return state == State.PENDING;
    }

    synchronized boolean posted(long now) {
        // The framework notify call already returned successfully: even if its Binder
        // round-trip crossed the deadline it must not be reported as a definite failure.
        if (state == State.POSTED || state == State.FAILED) return false;
        state = State.POSTED;
        return true;
    }

    synchronized void failed() {
        if (state == State.PENDING) state = State.FAILED;
    }

    synchronized State state(long now) { canPost(now); return state; }
}
