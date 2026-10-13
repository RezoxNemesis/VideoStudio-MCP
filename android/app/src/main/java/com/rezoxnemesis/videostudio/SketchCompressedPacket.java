package com.rezoxnemesis.videostudio;

import java.io.*;
import java.util.zip.*;

/** Exact single-member gzip packets, bounded on input and expansion, with CRC proof. */
final class SketchCompressedPacket {
    private SketchCompressedPacket() {}

    static byte[] read(InputStream input, int expandedLimit) throws IOException {
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        byte[] block = new byte[32768];
        int read;
        while ((read = input.read(block)) != -1) {
            cancelled();
            if (source.size()+read > expandedLimit+65536)
                throw new IOException("Compressed drawing packet exceeds bounded import budget");
            source.write(block,0,read);
        }
        byte[] bytes = source.toByteArray();
        int offset = header(bytes);
        Inflater inflater = new Inflater(true);
        CRC32 crc = new CRC32();
        ByteArrayOutputStream expanded = new ByteArrayOutputStream();
        try {
            inflater.setInput(bytes,offset,bytes.length-offset);
            while (!inflater.finished()) {
                cancelled();
                read = inflater.inflate(block);
                if (expanded.size()+read > expandedLimit)
                    throw new IllegalArgumentException("Drawing packet expansion exceeds bounded import budget");
                expanded.write(block,0,read); crc.update(block,0,read);
                if (read==0 && !inflater.finished())
                    throw new IOException("Truncated or invalid gzip drawing packet");
            }
            int trailer = bytes.length-inflater.getRemaining();
            if (trailer+8 != bytes.length)
                throw new IOException("Drawing packet must contain a single gzip member with no trailing data");
            if (unsigned32(bytes,trailer) != crc.getValue() ||
                    unsigned32(bytes,trailer+4) != expanded.size())
                throw new IOException("Drawing packet gzip checksum/size mismatch");
            return expanded.toByteArray();
        } catch (DataFormatException invalid) {
            throw new IOException("Invalid gzip drawing data",invalid);
        } finally { inflater.end(); }
    }

    private static int header(byte[] bytes) throws IOException {
        if (bytes.length<18 || u(bytes,0)!=0x1f || u(bytes,1)!=0x8b || u(bytes,2)!=8)
            throw new IOException("Invalid gzip drawing header");
        int flags = u(bytes,3), offset = 10;
        if ((flags & 0xe0)!=0) throw new IOException("Reserved gzip header flags");
        if ((flags & 4)!=0) {
            require(bytes,offset,2);
            int size = u(bytes,offset) | u(bytes,offset+1)<<8;
            offset += 2; require(bytes,offset,size); offset += size;
        }
        if ((flags & 8)!=0) offset = skipString(bytes,offset);
        if ((flags & 16)!=0) offset = skipString(bytes,offset);
        if ((flags & 2)!=0) {
            require(bytes,offset,2);
            CRC32 crc = new CRC32(); crc.update(bytes,0,offset);
            if ((crc.getValue() & 0xffff) != (u(bytes,offset) | u(bytes,offset+1)<<8))
                throw new IOException("Drawing packet gzip header checksum mismatch");
            offset += 2;
        }
        require(bytes,offset,8); return offset;
    }
    private static int skipString(byte[] bytes,int offset) throws IOException {
        while (offset<bytes.length) {
            if ((offset & 32767)==0) cancelled();
            if (bytes[offset++]==0) return offset;
        }
        throw new IOException("Truncated gzip drawing metadata");
    }
    private static int u(byte[] bytes,int offset) { return bytes[offset] & 255; }
    private static long unsigned32(byte[] bytes,int offset) throws IOException {
        require(bytes,offset,4);
        return (long)u(bytes,offset) | (long)u(bytes,offset+1)<<8 |
                (long)u(bytes,offset+2)<<16 | (long)u(bytes,offset+3)<<24;
    }
    private static void require(byte[] bytes,int offset,int count) throws IOException {
        if (offset<0 || count<0 || offset>bytes.length-count)
            throw new IOException("Truncated gzip drawing packet");
    }
    private static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Compressed drawing import cancelled");
    }
}
