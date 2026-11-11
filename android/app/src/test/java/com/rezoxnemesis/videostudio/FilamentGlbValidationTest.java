package com.rezoxnemesis.videostudio;

import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import static org.junit.Assert.*;

public class FilamentGlbValidationTest {
    private static File make(int magic,int version,int reportedSize,int actualSize) throws Exception {
        byte[] data=new byte[actualSize];
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(magic);b.putInt(version);b.putInt(reportedSize);
        File tmp=new File(System.getProperty("java.io.tmpdir"),
                "videostudio_3d_"+UUID.randomUUID()+".glb");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(data);}
        tmp.deleteOnExit();
        return tmp;
    }
    @Test public void valid3DContainerVersionIsRecognized() throws Exception {
        File f=make(0x46546c67,2,20,20);
        FilamentGlbActivity.verifyGlb(f);
    }
    @Test(expected=IllegalArgumentException.class)
    public void rejectsFakeNonGlbBytes() throws Exception {
        FilamentGlbActivity.verifyGlb(make(0x2348abcd,2,20,20));
    }
    @Test(expected=IllegalArgumentException.class)
    public void refusesWrongGlbVersion() throws Exception {
        FilamentGlbActivity.verifyGlb(make(0x46546c67,1,20,20));
    }
    @Test(expected=IllegalArgumentException.class)
    public void refusesCorruptDeclaredLength() throws Exception {
        FilamentGlbActivity.verifyGlb(make(0x46546c67,2,250,20));
    }
    @Test(expected=IllegalArgumentException.class)
    public void refusesTruncated3DAssets() throws Exception {
        FilamentGlbActivity.verifyGlb(make(0x46546c67,2,12,12));
    }
}
