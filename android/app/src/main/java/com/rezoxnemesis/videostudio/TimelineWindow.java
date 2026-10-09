package com.rezoxnemesis.videostudio;

/** A final-render interval on the original program clock, in microseconds. */
final class TimelineWindow {
    final long startUs,endUs;
    TimelineWindow(long startUs,long endUs){
        if(startUs<0||endUs<=startUs||endUs-startUs>10_000_000L)throw new IllegalArgumentException("Invalid render window");
        this.startUs=startUs;this.endUs=endUs;
    }
    long durationUs(){return endUs-startUs;}
}
