package com.rezoxnemesis.videostudio;

/** SD1.5 epsilon-prediction DDIM, eta0, leading timestep spacing, offset1. */
final class LocalDrawingSchedule {
    static final float LATENT_SCALE=.18215f;
    private final double[] alpha=new double[1000];
    LocalDrawingSchedule(){double product=1;for(int i=0;i<1000;i++){double beta=Math.sqrt(.00085)+(Math.sqrt(.012)-Math.sqrt(.00085))*i/999;product*=1-beta*beta;alpha[i]=product;}}
    double alpha(int timestep){if(timestep<0||timestep>=1000)throw new IllegalArgumentException("Invalid denoising timestep");return alpha[timestep];}
    int[] timesteps(int steps,double strength){
        if(steps<4||steps>30||!Double.isFinite(strength)||strength<.1||strength>1)throw new IllegalArgumentException("Use4..30 steps and strength0.1..1");
        int active=Math.max(1,(int)Math.floor(steps*strength)),ratio=1000/steps;int[] out=new int[active];
        for(int i=0;i<active;i++)out[i]=(active-1-i)*ratio+1;return out;
    }
    float[] addNoise(float[] source,float[] noise,int timestep){if(source.length!=noise.length)throw new IllegalArgumentException("Latent shapes differ");float[] out=new float[source.length];double a=Math.sqrt(alpha(timestep)),b=Math.sqrt(1-alpha(timestep));for(int i=0;i<out.length;i++)out[i]=(float)(a*source[i]+b*noise[i]);return out;}
    float[] step(float[] sample,float[] epsilon,int timestep,int previous){
        if(sample.length!=epsilon.length||previous>=timestep||previous< -1)throw new IllegalArgumentException("Invalid DDIM step");
        double current=alpha(timestep),next=previous<0?alpha[0]:alpha(previous);float[] out=new float[sample.length];
        for(int i=0;i<out.length;i++){if(!Float.isFinite(sample[i])||!Float.isFinite(epsilon[i]))throw new IllegalArgumentException("Non-finite model output");double original=(sample[i]-Math.sqrt(1-current)*epsilon[i])/Math.sqrt(current);out[i]=(float)(Math.sqrt(next)*original+Math.sqrt(1-next)*epsilon[i]);if(!Float.isFinite(out[i]))throw new IllegalArgumentException("Non-finite latent output");}return out;
    }
}
