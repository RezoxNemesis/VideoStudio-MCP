package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

/** CPU geometry and output-clock checks; these do not establish GPU rendering evidence. */
public class Cinematic3DTrailGeometryTest {
    private static Object call(String name,Class<?>[] types,Object... args) {
        try {
            return Class.forName("com.rezoxnemesis.videostudio.Cinematic3DTrailGeometry")
                    .getMethod(name,types).invoke(null,args);
        } catch(java.lang.reflect.InvocationTargetException failure) {
            if(failure.getCause() instanceof RuntimeException) throw (RuntimeException)failure.getCause();
            throw new AssertionError(failure.getCause());
        } catch(ReflectiveOperationException absent) {
            throw new AssertionError("Output-frame full-blade trail geometry helper is absent",absent);
        }
    }
    private static double[] matrix(double[] start,double[] end,double[] previousStart,double[] previousEnd,int age) {
        return (double[])call("matrix",new Class<?>[]{double[].class,double[].class,double[].class,double[].class,int.class},
                start,end,previousStart,previousEnd,age);
    }
    private static int priorFrame(int current,int age) {
        return (Integer)call("priorFrame",new Class<?>[]{int.class,int.class},current,age);
    }
    private static double length(double[] m,int column) {
        int offset=column*4;
        return Math.sqrt(m[offset]*m[offset]+m[offset+1]*m[offset+1]+m[offset+2]*m[offset+2]);
    }
    private static void invisible(double[] m) {
        assertEquals(16,m.length);
        for(int i=0;i<12;i++)assertEquals("Degenerate ribbon must have no extent",0,m[i],0);
        for(double value:m)assertTrue(Double.isFinite(value));
        assertEquals(1,m[15],0);
    }
    private static void rejects(Runnable action) {
        try { action.run(); fail("Invalid trail input accepted"); }
        catch(IllegalArgumentException expected) { }
    }

    @Test public void unitCubeTransformSpansTheWholeBladeAndSweptRectangle() {
        double[] m=matrix(new double[]{2,0,0},new double[]{2,2,0},
                new double[]{0,0,0},new double[]{0,2,0},0);
        assertArrayEquals(new double[]{2,0,0,0, 0,2,0,0, 0,0,.006,0, 1,1,0,1},m,1e-12);
        // Cube y=-.5 and y=.5 reach the blade base and tip, not a short tip segment.
        assertEquals(0,m[13]-.5*m[5],1e-12);
        assertEquals(2,m[13]+.5*m[5],1e-12);
    }

    @Test public void geometryMatchesNewerBladeLengthAndProjectsSweepOffNewerAxis() {
        double[] m=matrix(new double[]{2,1,0},new double[]{2,5,0},
                new double[]{0,0,0},new double[]{0,2,0},2);
        assertEquals(2,length(m,0),1e-12);
        assertEquals(4,length(m,1),1e-12);
        assertArrayEquals(new double[]{1,2,0},new double[]{m[12],m[13],m[14]},1e-12);
        assertEquals(0,m[0]*m[4]+m[1]*m[5]+m[2]*m[6],1e-12);
    }

    @Test public void rotatedBladeProducesFiniteRightHandedOrthogonalColumns() {
        double[] m=matrix(new double[]{1,0,1},new double[]{1,2,3},
                new double[]{0,0,0},new double[]{0,2,2},0);
        for(double value:m)assertTrue(Double.isFinite(value));
        assertEquals(Math.sqrt(1.5),length(m,0),1e-12);
        assertEquals(Math.sqrt(8),length(m,1),1e-12);
        assertEquals(.006,length(m,2),1e-12);
        for(int a=0;a<3;a++)for(int b=a+1;b<3;b++)
            assertEquals(0,m[4*a]*m[4*b]+m[4*a+1]*m[4*b+1]+m[4*a+2]*m[4*b+2],1e-12);
        double determinant=m[0]*(m[5]*m[10]-m[6]*m[9])
                -m[4]*(m[1]*m[10]-m[2]*m[9])+m[8]*(m[1]*m[6]-m[2]*m[5]);
        assertTrue("Trail transform must keep a right-handed basis",determinant>0);
        assertEquals(0,m[3],0);assertEquals(0,m[7],0);assertEquals(0,m[11],0);assertEquals(1,m[15],0);
    }

    @Test public void changedBladeDirectionUsesTheNewerAxisAsInBlender() {
        double[] m=matrix(new double[]{2,0,0},new double[]{2,2,0},
                new double[]{0,0,0},new double[]{0,0,2},0);
        assertEquals(2,length(m,1),1e-12);
        assertArrayEquals(new double[]{0,2,0},new double[]{m[4],m[5],m[6]},1e-12);
    }

    @Test public void sixAgesHaveDecreasingSpecifiedThickness() {
        for(int age=0;age<6;age++) {
            double[] m=matrix(new double[]{1,0,0},new double[]{1,2,0},
                    new double[]{0,0,0},new double[]{0,2,0},age);
            assertEquals(.006*(1-age/7.0),length(m,2),1e-12);
        }
    }

    @Test public void holdAddsNoGeometryWhileOldOutputFrameSegmentsExpire() {
        int[] expectedVisible={0,1,2,3,3,3,3,2,1,0,0};
        for(int current=0;current<expectedVisible.length;current++) {
            int visible=0;
            for(int age=0;age<6;age++) {
                int older=priorFrame(current,age);
                if(older<0)continue;
                int newer=older+1;
                double oldX=Math.min(older,3),newX=Math.min(newer,3);
                double[] m=matrix(new double[]{newX,0,0},new double[]{newX,2,0},
                        new double[]{oldX,0,0},new double[]{oldX,2,0},age);
                if(length(m,0)>0)visible++;
            }
            assertEquals("Window must age on output frames even when actor phase is held at frame 3",
                    expectedVisible[current],visible);
        }
    }

    @Test public void exactSixPriorFramesResetAtCutsAndAreIndependentOfScrubOrder() {
        for(int age=0;age<6;age++)assertEquals(19-age,priorFrame(20,age));
        for(int current:new int[]{0,2,50,1,0,7,3})for(int age=0;age<6;age++)
            assertEquals(Math.max(-1,current-age-1),priorFrame(current,age));
        for(int age=0;age<6;age++)assertEquals("New shot frame zero cannot sample the previous shot",-1,priorFrame(0,age));
        rejects(()->priorFrame(6,6));rejects(()->priorFrame(6,-1));rejects(()->priorFrame(-1,0));
    }

    @Test public void stationaryAxialMotionAndDegenerateBladesHaveNoVisibleExtent() {
        double[] start={0,0,0},end={0,2,0};
        invisible(matrix(start,end,start,end,0));
        invisible(matrix(new double[]{0,1,0},new double[]{0,3,0},start,end,0));
        invisible(matrix(new double[]{1,0,0},new double[]{1,0,0},start,end,0));
        invisible(matrix(new double[]{1,0,0},new double[]{1,2,0},start,start,0));
    }

    @Test public void reversedOlderBladeDoesNotCollapseANondegenerateNewerBlade() {
        double[] m=matrix(new double[]{1,2,0},new double[]{1,0,0},
                new double[]{0,0,0},new double[]{0,2,0},0);
        assertArrayEquals(new double[]{1,0,0,0, 0,-2,0,0, 0,0,-.006,0, .5,1,0,1},m,1e-12);
    }

    @Test public void authoringSweepThresholdSuppressesNumericallyStationaryRibbons() {
        for(double sweep:new double[]{0,1e-8,1e-5})
            invisible(matrix(new double[]{sweep,0,0},new double[]{sweep,2,0},
                    new double[]{0,0,0},new double[]{0,2,0},0));
        assertEquals(1.01e-5,length(matrix(new double[]{1.01e-5,0,0},new double[]{1.01e-5,2,0},
                new double[]{0,0,0},new double[]{0,2,0},0),0),1e-12);
    }

    @Test public void nativeNegativeZBladeEndpointsStillProduceFullLengthWorldRibbon() {
        double[] m=matrix(new double[]{1,0,-.16},new double[]{1,0,-1.06},
                new double[]{0,0,-.16},new double[]{0,0,-1.06},0);
        assertArrayEquals(new double[]{1,0,0,0, 0,0,-.9,0, 0,.006,0,0, .5,0,-.61,1},m,1e-12);
    }

    @Test public void malformedNonfiniteAndOverflowInputsAreRejected() {
        double[] start={0,0,0},end={0,2,0};
        rejects(()->matrix(null,end,start,end,0));
        rejects(()->matrix(new double[]{0,0},end,start,end,0));
        rejects(()->matrix(new double[]{0,0,0,0},end,start,end,0));
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            rejects(()->matrix(new double[]{invalid,0,0},end,start,end,0));
        rejects(()->matrix(new double[]{Double.MAX_VALUE,0,0},new double[]{Double.MAX_VALUE,2,0},
                new double[]{-Double.MAX_VALUE,0,0},new double[]{-Double.MAX_VALUE,2,0},0));
        rejects(()->matrix(start,end,start,end,-1));rejects(()->matrix(start,end,start,end,6));
    }
}
