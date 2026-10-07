package com.rezoxnemesis.videostudio;
import org.junit.Test;
import static org.junit.Assert.*;
public class SceneMathTest {
 @Test public void rotationPreservesDistance() {
  double[] v=SceneMath.rotate(2,3,4,.7,1.1,.3);
  assertEquals(29,v[0]*v[0]+v[1]*v[1]+v[2]*v[2],1e-9);
 }
 @Test public void perspectiveMakesFarObjectsSmaller() {
  double[] near=SceneMath.project(1,0,2,50,1),far=SceneMath.project(1,0,4,50,1);
  assertEquals((near[0]-.5)/2,far[0]-.5,1e-9);
 }
 @Test public void nearPlaneAndInvalidCoordinatesAreRejected() {
  assertNull(SceneMath.project(1,1,0,50,1));assertNull(SceneMath.project(Double.NaN,1,3,50,1));
 }
 @Test public void easingHasStableEndpointsAndMonotonicMotion() {
  assertEquals(0,SceneMath.ease(-1),0);assertEquals(1,SceneMath.ease(2),0);
  double previous=0;for(int i=0;i<=100;i++){double value=SceneMath.ease(i/100.0);assertTrue(value>=previous);previous=value;}
 }
 @Test public void degenerateFacesHaveFiniteLight() {
  assertTrue(Double.isFinite(SceneMath.diffuse(new double[]{0,0,0},new double[]{0,0,0},new double[]{0,0,0})));
 }
}
