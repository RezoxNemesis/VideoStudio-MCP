package com.rezoxnemesis.videostudio;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
/** Native ABI loading order test; real phone GPU execution is separately verified. */
public class Native3DRuntimeTest {
    @Test public void modelLoaderAndCameraUtilitiesAreLoadedBeforeUse() throws Exception {
        Class<?> runtime=Class.forName("com.rezoxnemesis.videostudio.Native3DRuntime");java.lang.reflect.Method method=runtime.getDeclaredMethod("load",java.util.function.Consumer.class);method.setAccessible(true);List<String> libraries=new ArrayList<>();method.invoke(null,(java.util.function.Consumer<String>)libraries::add);assertEquals(Arrays.asList("filament","gltfio","utils"),libraries);
    }
}
