package com.rezoxnemesis.videostudio;

/** Pure world-space transforms for the authored unit_cube_v1 six-output-frame ribbons. */
public final class Cinematic3DTrailGeometry {
    private static final int WINDOW_FRAMES=6;
    private static final double MIN_SWEEP=1e-5;

    private Cinematic3DTrailGeometry() {}

    /** Older endpoint sample for one interval. Shot-local indices prevent history across cuts. */
    public static int priorFrame(int current,int age) {
        requireAge(age);
        if(current<0)throw new IllegalArgumentException("A nonnegative shot-local output frame is required");
        return Math.max(-1,current-age-1);
    }

    /**
     * Column-major transform of a cube with vertices in [-.5,.5]^3. Its columns are the
     * perpendicular midpoint sweep, the complete newer blade, and the ribbon normal.
     * Inputs are world-space base/tip endpoints for adjacent output frames, not clip frames.
     * Blender pose_vfx uses the newer blade axis/length and the same sweep cutoff/thickness.
     */
    public static double[] matrix(double[] currentStart,double[] currentEnd,
                                  double[] previousStart,double[] previousEnd,int age) {
        requireAge(age);
        requirePoint(currentStart);requirePoint(currentEnd);
        requirePoint(previousStart);requirePoint(previousEnd);
        double[] blade=subtract(currentEnd,currentStart);
        double[] previousBlade=subtract(previousEnd,previousStart);
        double bladeLength=length(blade),previousLength=length(previousBlade);
        double[] currentMid=midpoint(currentStart,currentEnd);
        double[] previousMid=midpoint(previousStart,previousEnd);
        double[] center=midpoint(currentMid,previousMid);
        double[] result=new double[16];
        result[12]=center[0];result[13]=center[1];result[14]=center[2];result[15]=1;
        // Degenerate or stationary samples remain valid homogeneous transforms with no extent.
        if(bladeLength==0||previousLength==0)return result;
        double[] bladeUnit={blade[0]/bladeLength,blade[1]/bladeLength,blade[2]/bladeLength};
        double[] sweep=subtract(currentMid,previousMid);
        double along=finite(sweep[0]*bladeUnit[0]+sweep[1]*bladeUnit[1]+sweep[2]*bladeUnit[2]);
        for(int axis=0;axis<3;axis++)sweep[axis]=finite(sweep[axis]-bladeUnit[axis]*along);
        double separation=length(sweep);
        if(separation<=MIN_SWEEP)return result;
        double[] across={sweep[0]/separation,sweep[1]/separation,sweep[2]/separation};
        double[] normal={across[1]*bladeUnit[2]-across[2]*bladeUnit[1],
                across[2]*bladeUnit[0]-across[0]*bladeUnit[2],
                across[0]*bladeUnit[1]-across[1]*bladeUnit[0]};
        double normalLength=length(normal);
        if(normalLength==0)return result;
        double thickness=.006*(1-age/7.0);
        for(int axis=0;axis<3;axis++) {
            result[axis]=sweep[axis];
            result[4+axis]=blade[axis];
            result[8+axis]=finite(normal[axis]/normalLength*thickness);
        }
        return result;
    }

    private static void requireAge(int age) {
        if(age<0||age>=WINDOW_FRAMES)throw new IllegalArgumentException("Trail age must be 0 through 5");
    }

    private static void requirePoint(double[] point) {
        if(point==null||point.length!=3)throw new IllegalArgumentException("A three-coordinate blade endpoint is required");
        for(double value:point)finite(value);
    }

    private static double finite(double value) {
        if(!Double.isFinite(value))throw new IllegalArgumentException("Trail coordinates and geometry must be finite");
        return value;
    }

    private static double[] subtract(double[] a,double[] b) {
        return new double[]{finite(a[0]-b[0]),finite(a[1]-b[1]),finite(a[2]-b[2])};
    }

    private static double[] midpoint(double[] a,double[] b) {
        // Halving before addition avoids overflowing otherwise valid same-sign coordinates.
        return new double[]{finite(a[0]*.5+b[0]*.5),finite(a[1]*.5+b[1]*.5),finite(a[2]*.5+b[2]*.5)};
    }

    private static double length(double[] vector) {
        return finite(Math.hypot(Math.hypot(vector[0],vector[1]),vector[2]));
    }
}
