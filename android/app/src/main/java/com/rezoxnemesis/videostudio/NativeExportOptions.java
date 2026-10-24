package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/** Explicit per-export waiting choice; omission preserves the native SDK default. */
final class NativeExportOptions {
    static final String FIELD="maxOutputSampleDelayMs";
    static final long MIN_DELAY_MS=25_000,MAX_DELAY_MS=120_000;
    private NativeExportOptions(){}

    static Long read(JSONObject parameters){
        if(parameters==null||!parameters.has(FIELD))return null;
        Object value=parameters.opt(FIELD);
        if(!(value instanceof Number))throw invalid();
        Number number=(Number)value;double decimal=number.doubleValue();long integer=number.longValue();
        if(!Double.isFinite(decimal)||decimal!=(double)integer)throw invalid();
        return validate(integer);
    }

    static Long validate(Long value){
        if(value!=null&&(value<MIN_DELAY_MS||value>MAX_DELAY_MS))throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid(){
        return new IllegalArgumentException(FIELD+" must be an integer from 25000 to 120000 milliseconds");
    }
}
