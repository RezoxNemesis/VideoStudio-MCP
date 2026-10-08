package com.rezoxnemesis.videostudio;

/** One hardware render at a time; waiting manual exports precede queued autonomous work. */
final class RenderGate {
    private boolean occupied;
    private int manualWaiting;
    synchronized void acquire(boolean manual) throws InterruptedException {
        if(manual)manualWaiting++;
        try {while(occupied||(!manual&&manualWaiting>0))wait();occupied=true;}
        finally {if(manual)manualWaiting--;notifyAll();}
    }
    synchronized void release(){occupied=false;notifyAll();}
    synchronized int availablePermits(){return occupied?0:1;}
}
