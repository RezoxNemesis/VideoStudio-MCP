package androidx.media3.transformer;

/** Test-only access to the pinned SDK's package-private real exception factory. */
public final class ExportExceptionTestBridge {
    private ExportExceptionTestBridge(){}

    public static ExportException createForMuxer(Throwable cause,int errorCode){
        return ExportException.createForMuxer(cause,errorCode);
    }
}
