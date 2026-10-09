package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.util.*;

/** Strict controls implemented by the shared preview/export pixel shader. */
public final class ClipCompositeSettings {
    private ClipCompositeSettings(){}
    public static final String[] KEYS={"chromaKey","chromaColor","chromaTolerance","chromaSoftness","spillSuppression","mask","maskCenterX","maskCenterY","maskWidth","maskHeight","maskFeather","maskInvert","maskCornerRadius"};
    public static void validate(JSONObject settings){
        if(settings==null)throw new IllegalArgumentException("Composite settings are required");
        Iterator<String> keys=settings.keys();while(keys.hasNext()){
            String key=keys.next();Object value=settings.opt(key);
            if(!Arrays.asList(KEYS).contains(key))throw new IllegalArgumentException("Unknown composite setting: "+key);
            if("chromaKey".equals(key)||"maskInvert".equals(key)){if(!(value instanceof Boolean))throw new IllegalArgumentException(key+" requires a boolean");}
            else if("chromaColor".equals(key)){if(!(value instanceof String)||!((String)value).matches("#[0-9A-Fa-f]{6}"))throw new IllegalArgumentException("Chroma colour must be #RRGGBB");}
            else if("mask".equals(key)){if(!(value instanceof String)||!Arrays.asList("none","rectangle","rounded_rect","ellipse").contains(value))throw new IllegalArgumentException("Unsupported mask shape");}
            else{
                if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException(key+" requires a finite number");
                double min=0,max=1;if("maskWidth".equals(key)||"maskHeight".equals(key))min=.01;
                if("maskFeather".equals(key)||"maskCornerRadius".equals(key))max=.5;
                if("chromaSoftness".equals(key)){min=.001;max=.5;}
                double number=((Number)value).doubleValue();if(number<min||number>max)throw new IllegalArgumentException(key+" is out of range");
            }
        }
    }
}
