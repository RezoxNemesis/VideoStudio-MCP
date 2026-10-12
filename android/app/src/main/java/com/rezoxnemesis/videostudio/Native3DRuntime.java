package com.rezoxnemesis.videostudio;
import com.google.android.filament.Filament;
import com.google.android.filament.gltfio.Gltfio;
import com.google.android.filament.utils.Utils;
import java.util.function.Consumer;
/** Every JNI provider must be loaded before ModelViewer / UbershaderProvider construction. */
public final class Native3DRuntime {
    private static boolean initialized;
    private Native3DRuntime(){}
    static void load(Consumer<String> loader){loader.accept("filament");loader.accept("gltfio");loader.accept("utils");}
    public static synchronized void initialize(){if(initialized)return;try{load(name->{switch(name){case "filament":Filament.init();break;case "gltfio":Gltfio.init();break;case "utils":Utils.init();break;default:throw new IllegalStateException("Unknown native 3D library");}});initialized=true;}catch(LinkageError missing){throw new IllegalStateException("Native 3D libraries are unavailable on this ABI",missing);}}
}
