package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.util.Arrays;

/**
 * Deterministic native PCM DSP used by the Media3 audio processor.
 *
 * Equalizer: three causal crossover bands (~200Hz / ~3500Hz);
 * Dynamics: RMS-free envelope follower, feed-forward compression, noise gate
 * and bounded sample-peak limiter. This is NOT phase-linear mastering EQ,
 * EBU-R128 LUFS measurement, lookahead true-peak limiting or Dolby mixing.
 *
 * Each channel has independent filter/envelope state; no audio sample arrays
 * are allocated in the per-frame call.
 */
public final class StudioFairlightDsp {
    private final int sampleRate,channels;
    private final double lowCoeff,highCoeff,highpassCoeff,lowpassCoeff;
    private final double attackCoeff,releaseCoeff,gateRise,gateFall;
    private final double[] low,upper,highpass,lowpass,envelope,gate;
    private final double eqLow,eqMid,eqHigh,compressorThreshold,ratio;
    private final double makeup,outputGain,limiter,gateThreshold;
    private final boolean highpassEnabled,lowpassEnabled,compressorEnabled,gateEnabled,bypass;
    private long samples;
    private double summedPower,peak;

    public StudioFairlightDsp(JSONObject input,int sampleRate,int channels)throws Exception{
        if(sampleRate<8000||sampleRate>192000||channels<1||channels>8)
            throw new IllegalArgumentException("Fairlight PCM supports 8–192kHz and 1–8 channels");
        JSONObject spec=StudioFairlightSettings.validate(input);
        this.sampleRate=sampleRate;this.channels=channels;
        low=new double[channels];upper=new double[channels];
        highpass=new double[channels];lowpass=new double[channels];
        envelope=new double[channels];gate=new double[channels];
        eqLow=db(spec.getDouble("eqLowDb"));
        eqMid=db(spec.getDouble("eqMidDb"));
        eqHigh=db(spec.getDouble("eqHighDb"));
        lowCoeff=coefficient(200,sampleRate);
        highCoeff=coefficient(Math.min(3500,sampleRate*.41),sampleRate);
        double hp=spec.getDouble("highpassHz"),lp=spec.getDouble("lowpassHz");
        highpassEnabled=hp>0;lowpassEnabled=lp>0;
        highpassCoeff=highpassEnabled?coefficient(hp,sampleRate):0;
        lowpassCoeff=lowpassEnabled?coefficient(Math.min(lp,sampleRate*.45),sampleRate):0;
        attackCoeff=1-Math.exp(-1000/(sampleRate*spec.getDouble("attackMs")));
        releaseCoeff=1-Math.exp(-1000/(sampleRate*spec.getDouble("releaseMs")));
        gateRise=1-Math.exp(-1000/(sampleRate*3.0));
        gateFall=1-Math.exp(-1000/(sampleRate*Math.max(50,spec.getDouble("releaseMs"))));
        compressorThreshold=db(spec.getDouble("compressorThresholdDb"));
        ratio=spec.getDouble("compressorRatio");
        compressorEnabled=ratio>1.00001;
        makeup=db(spec.getDouble("makeupDb"));
        outputGain=db(spec.getDouble("outputDb"));
        limiter=db(spec.getDouble("limiterCeilingDb"));
        gateThreshold=db(spec.getDouble("noiseGateThresholdDb"));
        gateEnabled=spec.getDouble("noiseGateThresholdDb")>-79.99;
        bypass=spec.getBoolean("bypass");
        Arrays.fill(gate,1.0);
    }

    public short processSample(int channel,short value){
        if(channel<0||channel>=channels)
            throw new IllegalArgumentException("PCM channel out of bounds");
        double x=value/32768.0;
        double out=x;
        if(!bypass){
            low[channel]+=lowCoeff*(x-low[channel]);
            upper[channel]+=highCoeff*(x-upper[channel]);
            out=low[channel]*eqLow+
                    (upper[channel]-low[channel])*eqMid+
                    (x-upper[channel])*eqHigh;
            if(highpassEnabled){
                highpass[channel]+=highpassCoeff*(out-highpass[channel]);
                out-=highpass[channel];
            }
            if(lowpassEnabled){
                lowpass[channel]+=lowpassCoeff*(out-lowpass[channel]);
                out=lowpass[channel];
            }
            double abs=Math.abs(out);
            double coefficient=abs>envelope[channel]?attackCoeff:releaseCoeff;
            envelope[channel]+=(abs-envelope[channel])*coefficient;
            if(compressorEnabled&&envelope[channel]>compressorThreshold){
                double desired=Math.pow(compressorThreshold/
                        Math.max(envelope[channel],1e-12),1-1/ratio);
                out*=desired;
            }
            if(gateEnabled){
                double target=envelope[channel]<gateThreshold?0:1;
                gate[channel]+=(target-gate[channel])*
                        (target>gate[channel]?gateRise:gateFall);
                out*=gate[channel];
            }
            out*=makeup*outputGain;
            // Sample-peak limiter only. No true-peak / intersample claim.
            out=Math.max(-limiter,Math.min(limiter,out));
        }
        out=Math.max(-1.0,Math.min(32767.0/32768.0,out));
        peak=Math.max(peak,Math.abs(out));
        summedPower+=out*out;
        samples++;
        return (short)Math.max(-32768,Math.min(32767,Math.round(out*32768.0)));
    }

    public short[] process(short[] interleaved){
        if(interleaved==null||interleaved.length%channels!=0)
            throw new IllegalArgumentException("PCM samples must be whole interleaved frames");
        short[] processed=new short[interleaved.length];
        for(int i=0;i<interleaved.length;i++)
            processed[i]=processSample(i%channels,interleaved[i]);
        return processed;
    }

    public void reset(){
        for(double[] state:new double[][]{low,upper,highpass,lowpass,envelope})
            Arrays.fill(state,0.0);
        Arrays.fill(gate,1.0);
        samples=0;summedPower=0;peak=0;
    }

    public double samplePeakDbfs(){
        return peak<=0?-120:20*Math.log10(peak);
    }
    public double approximateRmsDbfs(){
        return samples==0?-120:20*Math.log10(Math.max(1e-9,
                Math.sqrt(summedPower/samples)));
    }
    public int channelCount(){return channels;}
    public int sampleRate(){return sampleRate;}

    private static double db(double decibels){return Math.pow(10,decibels/20);}
    private static double coefficient(double hz,int rate){
        return 1-Math.exp(-2*Math.PI*hz/rate);
    }
}
