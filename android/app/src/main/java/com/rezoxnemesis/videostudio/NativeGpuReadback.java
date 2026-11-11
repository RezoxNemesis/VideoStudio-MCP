package com.rezoxnemesis.videostudio;

import java.nio.ByteBuffer;

/**
 * Filament readPixels returns tightly packed bottom-left-origin RGBA UBYTE.
 * Android Bitmap expects top-left-origin unpremultiplied ARGB ints.
 * Pure Java allows true pixel-level validation without a live GPU.
 */
public final class NativeGpuReadback {
    private NativeGpuReadback(){}
    public static int[] toTopLeftArgb(ByteBuffer rgba,int width,int height) {
        if(rgba==null||width<=0||height<=0||
                (long)width*height>540L*960L||
                rgba.remaining()<(long)width*height*4)
            throw new IllegalArgumentException("Invalid bounded GLB GPU frame buffer");
        int start=rgba.position(),stride=width*4;
        int[] pixels=new int[width*height];
        for(int y=0;y<height;y++){
            int originalRow=(height-1-y)*stride;
            for(int x=0;x<width;x++){
                int offset=start+originalRow+x*4;
                int r=rgba.get(offset)&255;
                int g=rgba.get(offset+1)&255;
                int b=rgba.get(offset+2)&255;
                int a=rgba.get(offset+3)&255;
                pixels[y*width+x]=(a<<24)|(r<<16)|(g<<8)|b;
            }
        }
        return pixels;
    }
}
