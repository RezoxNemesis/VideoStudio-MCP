package com.rezoxnemesis.videostudio;

/** Stateful PCM filters/dynamics/delay with a fixed memory budget and no frame allocations. */
public final class AudioDsp {
    public static final class Parameters {
        public double lowDb=0,midDb=0,highDb=0,highpassHz=0,lowpassHz=0,thresholdDb=0,ratio=1,attackMs=10,releaseMs=100,makeupDb=0,gateDb=-120,delayMs=0,delayWet=0,delayFeedback=0,stereoWidth=1,limiterDb=0;
    }
    private final int channels;
    private final Biquad[][] filters;
    private final float[][] delay;
    private final double attack,release,threshold,ratio,makeup,gateThreshold,wet,feedback,width,ceiling;
    private double envelope,compressionGain=1,gateGain=1;
    private int cursor;
    public AudioDsp(int rate,int channels,Parameters p){
        if(p==null||rate<8000||rate>192000||channels<1||channels>8)throw new IllegalArgumentException("DSP requires 8–192 kHz PCM with 1–8 channels");
        bounded(p.lowDb,-24,24);bounded(p.midDb,-24,24);bounded(p.highDb,-24,24);bounded(p.highpassHz,0,2000);bounded(p.lowpassHz,0,20000);
        bounded(p.thresholdDb,-60,0);bounded(p.ratio,1,20);bounded(p.attackMs,1,200);bounded(p.releaseMs,10,2000);bounded(p.makeupDb,-24,24);bounded(p.gateDb,-120,0);
        bounded(p.delayMs,0,2000);bounded(p.delayWet,0,1);bounded(p.delayFeedback,0,.95);bounded(p.stereoWidth,0,2);bounded(p.limiterDb,-24,0);
        if(channels!=2&&p.stereoWidth!=1)throw new IllegalArgumentException("Stereo width requires a stereo source");
        if(p.delayWet>0&&p.delayMs<=0)throw new IllegalArgumentException("A wet delay needs a positive delay time");
        this.channels=channels;attack=Math.exp(-1/(rate*p.attackMs/1000));release=Math.exp(-1/(rate*p.releaseMs/1000));
        threshold=db(p.thresholdDb);ratio=p.ratio;makeup=db(p.makeupDb);gateThreshold=p.gateDb<=-120?0:db(p.gateDb);
        wet=p.delayWet;feedback=p.delayFeedback;width=p.stereoWidth;ceiling=db(p.limiterDb);
        int length=wet==0?0:Math.max(1,(int)Math.round(rate*p.delayMs/1000));
        if((long)length*channels*4>8L*1024*1024)throw new IllegalArgumentException("Delay exceeds the 8 MB audio memory budget; reduce time, channels or sample rate");
        delay=length==0?null:new float[channels][length];filters=new Biquad[channels][5];
        for(int channel=0;channel<channels;channel++){
            filters[channel][0]=p.highpassHz==0?null:cut(rate,p.highpassHz,true);
            filters[channel][1]=p.lowDb==0?null:shelf(rate,120,p.lowDb,false);
            filters[channel][2]=p.midDb==0?null:peak(rate,1000,p.midDb);
            filters[channel][3]=p.highDb==0?null:shelf(rate,6000,p.highDb,true);
            filters[channel][4]=p.lowpassHz==0?null:cut(rate,p.lowpassHz,false);
        }
    }
    public void processFrame(double[] input,double[] output){
        if(input.length<channels||output.length<channels)throw new IllegalArgumentException("Incomplete PCM frame");
        double level=0;
        for(int channel=0;channel<channels;channel++){
            double value=input[channel];if(!Double.isFinite(value))throw new IllegalArgumentException("Non-finite PCM sample");
            for(Biquad filter:filters[channel])if(filter!=null)value=filter.process(value);
            output[channel]=value;level=Math.max(level,Math.abs(value));
        }
        double detectorTime=level>envelope?attack:release;envelope=detectorTime*envelope+(1-detectorTime)*level;
        double target=ratio<=1||envelope<=threshold?1:Math.pow(threshold/Math.max(envelope,1e-12),1-1/ratio);
        double gainTime=target<compressionGain?attack:release;compressionGain=gainTime*compressionGain+(1-gainTime)*target;
        double gateTarget=gateThreshold>0&&envelope<gateThreshold?0:1,gateTime=gateTarget>gateGain?attack:release;
        gateGain=gateTime*gateGain+(1-gateTime)*gateTarget;
        for(int channel=0;channel<channels;channel++){
            double value=output[channel]*compressionGain*gateGain*makeup;
            if(delay!=null){double previous=delay[channel][cursor];delay[channel][cursor]=(float)Math.max(-4,Math.min(4,value+previous*feedback));value=value*(1-wet)+previous*wet;}
            output[channel]=value;
        }
        if(delay!=null)cursor=(cursor+1)%delay[0].length;
        if(channels==2&&width!=1){double mid=(output[0]+output[1])/2,side=(output[0]-output[1])/2*width;output[0]=mid+side;output[1]=mid-side;}
        for(int channel=0;channel<channels;channel++)output[channel]=Math.max(-ceiling,Math.min(ceiling,output[channel]));
    }
    private static void bounded(double value,double min,double max){if(!Double.isFinite(value)||value<min||value>max)throw new IllegalArgumentException("Audio parameter is outside its supported range");}
    private static double db(double value){return Math.pow(10,value/20);}
    private static double frequency(int rate,double hz){return 2*Math.PI*Math.min(hz,rate*.45)/rate;}
    private static Biquad cut(int rate,double hz,boolean high){double w=frequency(rate,hz),cos=Math.cos(w),alpha=Math.sin(w)/(2*.7071067811865476);double b0=high?(1+cos)/2:(1-cos)/2,b1=high?-(1+cos):1-cos;return new Biquad(b0,b1,b0,1+alpha,-2*cos,1-alpha);}
    private static Biquad peak(int rate,double hz,double gain){double w=frequency(rate,hz),a=Math.pow(10,gain/40),alpha=Math.sin(w)/(2*.7071067811865476),cos=Math.cos(w);return new Biquad(1+alpha*a,-2*cos,1-alpha*a,1+alpha/a,-2*cos,1-alpha/a);}
    private static Biquad shelf(int rate,double hz,double gain,boolean high){
        double w=frequency(rate,hz),a=Math.pow(10,gain/40),c=Math.cos(w),alpha=Math.sin(w)*Math.sqrt(2)/2,t=2*Math.sqrt(a)*alpha;
        if(high)return new Biquad(a*((a+1)+(a-1)*c+t),-2*a*((a-1)+(a+1)*c),a*((a+1)+(a-1)*c-t),(a+1)-(a-1)*c+t,2*((a-1)-(a+1)*c),(a+1)-(a-1)*c-t);
        return new Biquad(a*((a+1)-(a-1)*c+t),2*a*((a-1)-(a+1)*c),a*((a+1)-(a-1)*c-t),(a+1)+(a-1)*c+t,-2*((a-1)+(a+1)*c),(a+1)+(a-1)*c-t);
    }
    private static final class Biquad {
        final double b0,b1,b2,a1,a2;double z1,z2;
        Biquad(double b0,double b1,double b2,double a0,double a1,double a2){this.b0=b0/a0;this.b1=b1/a0;this.b2=b2/a0;this.a1=a1/a0;this.a2=a2/a0;}
        double process(double input){double output=b0*input+z1;z1=b1*input-a1*output+z2;z2=b2*input-a2*output;if(Math.abs(z1)<1e-30)z1=0;if(Math.abs(z2)<1e-30)z2=0;return output;}
    }
}
