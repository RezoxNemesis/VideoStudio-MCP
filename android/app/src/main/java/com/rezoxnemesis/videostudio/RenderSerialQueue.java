package com.rezoxnemesis.videostudio;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/** One in-flight native operation; acceptance never means native completion. */
final class RenderSerialQueue {
    private final int capacity;
    private final Consumer<Runnable> scheduler;
    private final ArrayDeque<Runnable> jobs=new ArrayDeque<>();
    RenderSerialQueue(int capacity,Consumer<Runnable> scheduler){
        if(capacity<1||scheduler==null)throw new IllegalArgumentException();this.capacity=capacity;this.scheduler=scheduler;
    }
    synchronized boolean submit(Runnable job){
        if(job==null||jobs.size()>=capacity)return false;
        boolean idle=jobs.isEmpty();jobs.addLast(job);if(idle)scheduler.accept(job);return true;
    }
    synchronized void completed(){
        if(jobs.isEmpty())return;jobs.removeFirst();if(!jobs.isEmpty())scheduler.accept(jobs.getFirst());
    }
    synchronized int size(){return jobs.size();}
}
