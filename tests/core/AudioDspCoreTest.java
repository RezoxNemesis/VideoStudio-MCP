package com.rezoxnemesis.videostudio;
public final class AudioDspCoreTest {
 private static int checks;
 private static void check(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
 private static double rms(AudioDsp.Parameters settings,double frequency){AudioDsp dsp=new AudioDsp(48000,1,settings);double[] input=new double[1],output=new double[1];double sum=0;int n=0;for(int frame=0;frame<12000;frame++){input[0]=.1*Math.sin(2*Math.PI*frequency*frame/48000);dsp.processFrame(input,output);if(frame>4000){sum+=output[0]*output[0];n++;}}return Math.sqrt(sum/n);}
 public static void main(String[] args){
  AudioDsp.Parameters flat=new AudioDsp.Parameters();AudioDsp dsp=new AudioDsp(48000,2,flat);double[] in={.2,-.4},out=new double[2];dsp.processFrame(in,out);check(Math.abs(out[0]-.2)<1e-9&&Math.abs(out[1]+.4)<1e-9,"Bypass preserves stereo samples");
  AudioDsp.Parameters eq=new AudioDsp.Parameters();eq.lowDb=6;check(rms(eq,60)>rms(flat,60)*1.6,"Low-shelf EQ boosts bass");check(rms(eq,7000)<rms(flat,7000)*1.1,"Bass EQ leaves highs alone");
  AudioDsp.Parameters hp=new AudioDsp.Parameters();hp.highpassHz=200;check(rms(hp,20)<rms(flat,20)*.04,"High-pass rejects sub-bass");check(rms(hp,2000)>rms(flat,2000)*.95,"High-pass retains speech band");
  AudioDsp.Parameters lp=new AudioDsp.Parameters();lp.lowpassHz=1000;check(rms(lp,7000)<rms(flat,7000)*.03,"Low-pass rejects highs");
  AudioDsp.Parameters comp=new AudioDsp.Parameters();comp.thresholdDb=-12;comp.ratio=4;AudioDsp compressor=new AudioDsp(48000,1,comp);double[] mono={.8},value=new double[1];for(int i=0;i<4800;i++)compressor.processFrame(mono,value);check(value[0]>.15&&value[0]<.45,"Compressor attenuates sustained loud input");
  AudioDsp.Parameters delay=new AudioDsp.Parameters();delay.delayMs=10;delay.delayWet=1;delay.delayFeedback=.5;AudioDsp delayed=new AudioDsp(48000,1,delay);double[] impulse={.5};delayed.processFrame(impulse,value);check(Math.abs(value[0])<1e-9,"Wet delay waits for its real sample offset");impulse[0]=0;for(int i=1;i<=480;i++)delayed.processFrame(impulse,value);check(Math.abs(value[0]-.5)<1e-6,"First delay tap occurs at 10 ms");for(int i=481;i<=960;i++)delayed.processFrame(impulse,value);check(Math.abs(value[0]-.25)<1e-6,"Feedback creates a real second tap");
  AudioDsp.Parameters width=new AudioDsp.Parameters();width.stereoWidth=0;AudioDsp narrow=new AudioDsp(48000,2,width);narrow.processFrame(in,out);check(Math.abs(out[0]+.1)<1e-6&&Math.abs(out[1]+.1)<1e-6,"Width zero folds to mono");
  AudioDsp.Parameters limit=new AudioDsp.Parameters();limit.limiterDb=-6;AudioDsp limiter=new AudioDsp(48000,1,limit);limiter.processFrame(new double[]{2},value);check(value[0]<=Math.pow(10,-6/20d)+1e-6,"Limiter enforces ceiling");
  try{AudioDsp.Parameters invalid=new AudioDsp.Parameters();invalid.ratio=Double.NaN;new AudioDsp(48000,1,invalid);throw new AssertionError("Invalid DSP accepted");}catch(IllegalArgumentException expected){checks++;}
  try{AudioDsp.Parameters huge=new AudioDsp.Parameters();huge.delayMs=2000;huge.delayWet=1;new AudioDsp(192000,8,huge);throw new AssertionError("Unbounded delay memory accepted");}catch(IllegalArgumentException expected){checks++;}
  System.out.println("PASS "+checks+" audio DSP signal checks");
 }
}
