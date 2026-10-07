package com.rezoxnemesis.videostudio;

/** Overflow-safe storage preflight for large imports, proxies and renders. */
public final class StorageBudget {
    public static final class Check {
        public final boolean allowed;
        public final long freeBytes;
        public final long requiredBytes;
        public final long reserveBytes;
        public final long requiredWithReserveBytes;
        public final long shortfallBytes;
        public final String state;

        Check(boolean allowed,
              long freeBytes,
              long requiredBytes,
              long reserveBytes,
              long requiredWithReserveBytes,
              long shortfallBytes) {
            this.allowed=allowed;
            this.freeBytes=freeBytes;
            this.requiredBytes=requiredBytes;
            this.reserveBytes=reserveBytes;
            this.requiredWithReserveBytes=requiredWithReserveBytes;
            this.shortfallBytes=shortfallBytes;
            this.state=allowed ? "ready" : "waiting_storage";
        }
    }

    private StorageBudget() {}

    public static Check check(long freeBytes,long requiredBytes,long reserveBytes) {
        long free=Math.max(0L,freeBytes);
        long required=Math.max(0L,requiredBytes);
        long reserve=Math.max(0L,reserveBytes);
        long total=saturatingAdd(required,reserve);
        boolean allowed=free>=total;
        long shortfall=allowed ? 0L : safeShortfall(total,free);
        return new Check(allowed,free,required,reserve,total,shortfall);
    }

    public static Check checkTransfer(long freeBytes,
                                      long expectedBytes,
                                      long completedBytes,
                                      long reserveBytes) {
        long remaining=0L;
        if(expectedBytes>0L){
            long completed=Math.max(0L,completedBytes);
            remaining=completed>=expectedBytes ? 0L : expectedBytes-completed;
        }
        return check(freeBytes,remaining,reserveBytes);
    }

    public static long saturatingAdd(long left,long right) {
        long a=Math.max(0L,left);
        long b=Math.max(0L,right);
        if(Long.MAX_VALUE-a<b) return Long.MAX_VALUE;
        return a+b;
    }

    private static long safeShortfall(long required,long free) {
        if(required<=free) return 0L;
        return required-free;
    }
}
