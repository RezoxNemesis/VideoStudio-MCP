package com.rezoxnemesis.videostudio;
import java.io.File;
import java.io.*;
import org.json.JSONObject;
/** Original score and sound design, synthesized as native owned PCM. */
public final class JungleFilmSoundtrack {
    private JungleFilmSoundtrack(){}
    private static final int RATE=24000;
    private static final double TAU=Math.PI*2;
    public static JSONObject writeStudy(File file)throws Exception {return write(file,20,true);}
    public static JSONObject write(File file,int seconds)throws Exception {
        return write(file,seconds,false);
    }
    private static JSONObject write(File file,int seconds,boolean study)throws Exception {
        if(seconds<1||seconds>120||file.exists())throw new IllegalArgumentException("Choose a new 1..120 second soundtrack file");
        File parent=file.getParentFile();if(parent!=null)parent.mkdirs();int samples=seconds*RATE;double peak=0,sum=0;
        try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(file),128*1024)){
            ascii(out,"RIFF");u32(out,36L+samples*4L);ascii(out,"WAVEfmt ");u32(out,16);u16(out,1);u16(out,2);u32(out,RATE);u32(out,RATE*4);u16(out,4);u16(out,16);ascii(out,"data");u32(out,samples*4L);
            long noise=13717;
            for(int n=0;n<samples;n++){
                if((n&65535)==0&&Thread.currentThread().isInterrupted())throw new InterruptedException("Soundtrack interrupted");
                double t=n/(double)RATE;noise=(1664525*noise+1013904223)&0xffffffffL;double white=((noise>>>8)/(double)0x1000000)*2-1;
                double story=study?(t<6?30+t:84+t-6):t;
                double ambience=.012*white+.007*Math.sin(TAU*2111*t)*Math.pow(.5+.5*Math.sin(TAU*7*t),9);
                double harmony=music(story)*(study?Math.min(1,Math.abs(t-6)/.12):1),effects=0,pan=0;
                if(!study){
                // Repeating footfall events follow the authored running sections.
                if(t>6&&t<11||t>26&&t<30||t>44&&t<49||t>104&&t<111){double step=(t*(t>20?4.2:2.5))%1;effects+=Math.exp(-step*24)*(.024*white+.028*Math.sin(TAU*83*t));}
                double[] hits={23.2,31.7,33.7,34.2,44.6,45.4,46.1,46.9,47.6,48.3,54.6,57.8,64.2,97.1};
                for(int i=0;i<hits.length;i++){double a=t-hits[i];if(a>=0&&a<.55){effects+=Math.exp(-a*15)*(.07*Math.sin(TAU*745*a)+.035*Math.sin(TAU*1247*a)+.025*Math.sin(TAU*2631*a))+.07*white*Math.exp(-a*38);pan=Math.sin(i)*.4;}}
                double[] roars={13.6,22.3,35.2,54.0};for(double event:roars){double a=t-event;if(a>=0&&a<1.6){double env=Math.sin(Math.PI*a/1.6)*Math.exp(-a*.7);effects+=env*(.12*Math.sin(TAU*64*a+1.3*Math.sin(TAU*7*a))+.045*Math.sin(TAU*141*a)+.035*white);}}
                double[] cries={66.25,67.4,72.2,79.5,108.1};for(int i=0;i<cries.length;i++){double a=t-cries[i];if(a>=0&&a<.65){double env=Math.sin(Math.PI*a/.65)*Math.exp(-a*2);effects+=.09*env*Math.sin(TAU*(980*a-175*a*a)+.7*Math.sin(TAU*12*a));pan=.25;}}
                double[] birds={.4,2.6,4.8,9.4,15.1,76.4,83.5,105.4,112.5,116.1};for(int i=0;i<birds.length;i++){double a=t-birds[i];if(a>=0&&a<.65){double env=Math.sin(Math.PI*a/.65)*(.5+.5*Math.cos(TAU*8*a));effects+=.025*env*Math.sin(TAU*(1900*a+550*a*a));}}
                // Wind acceleration and the final charged attack.
                if(t>=39&&t<44){double a=(t-39)/5;effects+=white*.055*a*(.6+.4*Math.sin(TAU*13*t));}
                if(t>=44&&t<49){double a=(t-44)*1.35%1;effects+=white*.055*Math.exp(-Math.pow((a-.45)*7,2));}
                if(t>=57&&t<66){double a=(t-57)/9;effects+=white*.045*Math.sin(Math.PI*a)+.022*Math.sin(TAU*(120*t+30*Math.sin(t)));}
                }else{
                    // Only the authored visible contact receives a metallic transient.
                    double hit=t-43/24d;
                    if(hit>=0&&hit<.65)effects+=Math.exp(-hit*13)*(.09*Math.sin(TAU*745*hit)+.045*Math.sin(TAU*1247*hit)+.025*Math.sin(TAU*2631*hit))+.08*white*Math.exp(-hit*35);
                    effects+=.055*white*Math.exp(-Math.pow((t-1.52)/.22,2));
                    effects+=.018*white*Math.exp(-Math.pow((t-2.23)/.32,2));
                }
                boolean speaking=false;for(int i=0;i<SketchJungleFilm.LINE_START.length;i++)if(story>=SketchJungleFilm.LINE_START[i]-.1&&story<SketchJungleFilm.LINE_START[i]+SketchJungleFilm.LINE_SLOTS[i])speaking=true;
                double scoreGain=speaking?.3:1,fade=Math.min(1,t/1.5)*Math.min(1,(seconds-t)/1.8);
                double common=(ambience+harmony*scoreGain+effects*(speaking?.7:1))*fade;
                double left=soft(common*(1-pan*.3)+.002*Math.sin(TAU*137*t)),right=soft(common*(1+pan*.3)+.002*Math.sin(TAU*139*t));
                peak=Math.max(peak,Math.max(Math.abs(left),Math.abs(right)));sum+=left*left+right*right;u16(out,(short)Math.round(left*32767));u16(out,(short)Math.round(right*32767));
            }
        }catch(Exception e){file.delete();throw e;}
        return PcmWaveFile.inspect(file).put("provider","original native PCM composition").put("stockAudioUsed",false).put("peak",peak).put("rms",Math.sqrt(sum/(samples*2))).put("scoreAndEffects",true).put("studyTimeline",study);
    }
    private static double music(double t){
        int[] roots={52,48,45,47};int root=roots[((int)(t/8))%4];if(t>=75)root=new int[]{45,48,52,55}[((int)((t-75)/8))%4];
        double activity=t<20?.45:t<66?1:t<75?.12:.55;
        double pad=0;for(int semitone:new int[]{0,7,12}){double f=note(root+semitone);pad+=(Math.sin(TAU*f*t)+.21*Math.sin(TAU*f*2*t))*.014;}
        int[] melody={76,79,83,81,79,76,74,71,72,76,79,76,74,71,69,71};double beat=t<66?.8:1.35,local=t%beat;int key=melody[(int)(t/beat)%melody.length];if(t>75)key-=5;double f=note(key),env=(1-Math.exp(-local*18))*Math.exp(-local*3/beat);double lead=.04*env*(Math.sin(TAU*f*t+.012*Math.sin(TAU*5.2*t))+.1*Math.sin(TAU*f*2*t));
        double drum=0;if(t>=20&&t<66){double a=(t-.05)% .42;drum=.057*Math.sin(TAU*(70*a-42*a*a))*Math.exp(-a*21);double p=t%.84;drum+=.018*Math.sin(TAU*115*p)*Math.exp(-p*28);}
        return (pad+lead+drum)*activity;
    }
    private static double note(int midi){return 440*Math.pow(2,(midi-69)/12d);}private static double soft(double x){return .88*Math.tanh(x/.88);}
    private static void ascii(OutputStream out,String text)throws Exception {out.write(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}private static void u16(OutputStream out,int value)throws Exception {out.write(value&255);out.write((value>>>8)&255);}private static void u32(OutputStream out,long value)throws Exception {u16(out,(int)value);u16(out,(int)(value>>>16));}
}
