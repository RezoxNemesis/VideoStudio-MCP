package com.rezoxnemesis.videostudio;
import org.json.JSONObject;
import java.util.Iterator;
public final class AudioDspSettings {
    private AudioDspSettings(){}
    public static AudioDsp.Parameters read(JSONObject settings){
        AudioDsp.Parameters p=new AudioDsp.Parameters();if(settings==null)return p;
        Iterator<String> keys=settings.keys();while(keys.hasNext()){
            String key=keys.next();Object raw=settings.opt(key);if(!(raw instanceof Number)||!Double.isFinite(((Number)raw).doubleValue()))throw new IllegalArgumentException("Audio setting must be numeric: "+key);double v=((Number)raw).doubleValue();
            switch(key){
                case "lowDb":p.lowDb=v;break;case "midDb":p.midDb=v;break;case "highDb":p.highDb=v;break;case "highpassHz":p.highpassHz=v;break;case "lowpassHz":p.lowpassHz=v;break;
                case "thresholdDb":p.thresholdDb=v;break;case "ratio":p.ratio=v;break;case "attackMs":p.attackMs=v;break;case "releaseMs":p.releaseMs=v;break;case "makeupDb":p.makeupDb=v;break;
                case "gateDb":p.gateDb=v;break;case "delayMs":p.delayMs=v;break;case "delayWet":p.delayWet=v;break;case "delayFeedback":p.delayFeedback=v;break;case "stereoWidth":p.stereoWidth=v;break;case "limiterDb":p.limiterDb=v;break;
                default:throw new IllegalArgumentException("Unknown audio setting: "+key);
            }
        }
        new AudioDsp(48000,2,p);return p;
    }
}
