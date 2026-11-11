package com.rezoxnemesis.videostudio;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class NativeGpuReadbackTest {
    @Test public void flipsOpenGlRowsAndConvertsRgbaToAndroidArgb() {
        // GL bottom row = red, green; GL top row = blue, white.
        byte[] buffer={
            (byte)255,0,0,(byte)255,
            0,(byte)255,0,(byte)255,
            0,0,(byte)255,(byte)255,
            (byte)255,(byte)255,(byte)255,(byte)255
        };
        int[] result=NativeGpuReadback.toTopLeftArgb(ByteBuffer.wrap(buffer),2,2);
        assertArrayEquals(new int[]{
            0xff0000ff,0xffffffff,0xffff0000,0xff00ff00
        },result);
    }
    @Test public void respectsByteBufferPositionAndAlpha() {
        ByteBuffer buffer=ByteBuffer.wrap(new byte[]{
            9,9,9,9,(byte)128,10,20,50
        });
        buffer.position(4);
        assertEquals(0x32800a14,
                NativeGpuReadback.toTopLeftArgb(buffer,1,1)[0]);
        assertEquals(4,buffer.position());
    }
    @Test(expected=IllegalArgumentException.class)
    public void rejectsMalformedGpuPixels() {
        NativeGpuReadback.toTopLeftArgb(ByteBuffer.allocate(8),4,4);
    }
    @Test(expected=IllegalArgumentException.class)
    public void rejectsOversizedGpuRender() {
        NativeGpuReadback.toTopLeftArgb(ByteBuffer.allocate(10),1080,1920);
    }
}
