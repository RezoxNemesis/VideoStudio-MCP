package com.rezoxnemesis.videostudio;

import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.LightManager;
import com.google.android.filament.Scene;

/**
 * Optional real physically-based key/fill/rim lighting for existing GLB
 * materials. GLB mesh, UVs, texture bitmaps, normals and roughness values
 * are never modified. This is lighting, not invented extra texture pixels.
 */
final class CinematicStudioLighting {
    private CinematicStudioLighting() {}

    static final class Light {
        final float r,g,b,intensity,dx,dy,dz;
        final boolean shadow;
        Light(float r,float g,float b,float intensity,
              float dx,float dy,float dz,boolean shadow){
            if(!valid(r)||!valid(g)||!valid(b)||!valid(intensity)
                    ||!valid(dx)||!valid(dy)||!valid(dz)
                    ||r<0||g<0||b<0||r>1.5||g>1.5||b>1.5
                    ||intensity<1000||intensity>130000
                    ||Math.abs(dx)>1||Math.abs(dy)>1||Math.abs(dz)>1
                    ||Math.hypot(Math.hypot(dx,dy),dz)<.5)
                throw new IllegalArgumentException("Invalid PBR studio light");
            this.r=r;this.g=g;this.b=b;this.intensity=intensity;
            this.dx=dx;this.dy=dy;this.dz=dz;this.shadow=shadow;
        }
    }

    private static boolean valid(float f){return Float.isFinite(f);}

    /** Key casts a real shadow; cool fill and amber rim remain inexpensive. */
    static Light[] threePoint() {
        return new Light[]{
            new Light(1.0f,.93f,.84f,59000,-.38f,-.78f,-.45f,true),
            new Light(.70f,.81f,1.0f,19000,.72f,-.49f,-.41f,false),
            new Light(1.0f,.77f,.55f,24500,.34f,-.40f,.86f,false)
        };
    }

    static int[] install(Engine engine,Scene scene) {
        if(engine==null||scene==null)
            throw new IllegalArgumentException("Live Filament lighting scene required");
        Light[] preset=threePoint();
        int[] entities=new int[preset.length];
        int added=0;
        try{
            for(int i=0;i<preset.length;i++){
                Light light=preset[i];
                int entity=EntityManager.get().create();
                entities[i]=entity;
                new LightManager.Builder(LightManager.Type.SUN)
                        .color(light.r,light.g,light.b)
                        .intensity(light.intensity)
                        .direction(light.dx,light.dy,light.dz)
                        .castShadows(light.shadow)
                        .build(engine,entity);
                scene.addEntity(entity);
                added++;
            }
            return entities;
        }catch(RuntimeException|LinkageError failed){
            // Cleanup *all* acquired IDs, including a partially constructed light.
            remove(engine,scene,entities);
            throw failed;
        }
    }

    static void remove(Engine engine,Scene scene,int[] entities) {
        if(entities==null)return;
        for(int entity:entities){
            if(entity==0)continue;
            if(scene!=null)try{scene.remove(entity);}catch(RuntimeException ignored){}
            if(engine!=null)try{engine.destroyEntity(entity);}catch(RuntimeException ignored){}
            try{EntityManager.get().destroy(entity);}catch(RuntimeException ignored){}
        }
    }
}
