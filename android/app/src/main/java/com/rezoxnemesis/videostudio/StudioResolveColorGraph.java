package com.rezoxnemesis.videostudio;

import androidx.media3.common.Effect;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.SingleColorLut;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Original, source-preserving serial color node graph for mobile video.
 *
 * Compiles validated DaVinci-inspired primaries, RGB lift/gamma/gain wheels,
 * per-channel curves, soft hue qualifiers and filmic toe/shoulder to a compact
 * 17^3 GPU LUT. No source media, decoded frames or external .cube files are
 * written. Preview/export share NativeRenderEngine's exact effect list.
 *
 * This is NOT DaVinci's YRGB color-management engine, HDR scopes, automatic
 * tracked Power Windows, ACES conversion or native Resolve project support.
 */
@UnstableApi
public final class StudioResolveColorGraph {
    public static final int LUT_SIZE=17, MAX_NODES=8;
    private static final Set<String> TYPES=Set.of(
            "primaries","wheels","curve","hue_qualifier","filmic");
    private static final Set<String> PRIMARIES=Set.of(
            "type","exposure","contrast","pivot","saturation","temperature","tint");
    private static final Set<String> WHEELS=Set.of(
            "type","lift","gamma","gain");
    private static final Set<String> CURVE=Set.of("type","channel","points");
    private static final Set<String> QUAL=Set.of(
            "type","centerHue","width","softness","saturation","gain");
    private static final Set<String> FILMIC=Set.of("type","toe","shoulder");
    private StudioResolveColorGraph(){}

    public static JSONObject capabilities() throws Exception {
        return new JSONObject().put("schema","videostudio.resolve-color-graph.v1")
                .put("minimumNativeVersion","4.10.34")
                .put("gpu","Media3 SingleColorLut 17x17x17")
                .put("maxNodes",MAX_NODES).put("colorSpace","SDR RGB correction")
                .put("nodeTypes",new JSONArray()
                        .put("primaries").put("wheels").put("curve")
                        .put("hue_qualifier").put("filmic"))
                .put("nativeAction","resolve_grade_apply")
                .put("planAction","resolve_grade_plan")
                .put("previewAndExportSamePipeline",true)
                .put("colorManagedHDR",false).put("tracking",false)
                .put("scopes",false).put("sourceMediaUnchanged",true)
                .put("originalResolveCode",false)
                .put("galleryAccess",false);
    }

    public static JSONArray validate(JSONArray nodes) throws Exception {
        if(nodes==null||nodes.length()<1||nodes.length()>MAX_NODES)
            throw new IllegalArgumentException("Color pipeline needs 1..8 bounded serial nodes");
        JSONArray result=new JSONArray();
        for(int i=0;i<nodes.length();i++){
            Object raw=nodes.opt(i);
            if(!(raw instanceof JSONObject))
                throw new IllegalArgumentException("Color nodes must be objects");
            JSONObject node=(JSONObject)raw;
            String type=node.optString("type","");
            if(!TYPES.contains(type))throw new IllegalArgumentException("Unsupported native color node "+type);
            Set<String> keys="primaries".equals(type)?PRIMARIES:
                    "wheels".equals(type)?WHEELS:"curve".equals(type)?CURVE:
                    "hue_qualifier".equals(type)?QUAL:FILMIC;
            java.util.Iterator<String> iter=node.keys();
            while(iter.hasNext())if(!keys.contains(iter.next()))
                throw new IllegalArgumentException("Unknown color node field");
            JSONObject normalized=new JSONObject().put("type",type);
            switch(type) {
                case "primaries":
                    for(String n:new String[]{"exposure","contrast","pivot","saturation","temperature","tint"}){
                        double def="contrast".equals(n)||"saturation".equals(n)?1:
                                "pivot".equals(n)?.5:0;
                        double low="exposure".equals(n)?-4:"contrast".equals(n)?.25:
                                "pivot".equals(n)?0:"saturation".equals(n)?0:-1;
                        double high="exposure".equals(n)?4:"contrast".equals(n)?3:
                                "pivot".equals(n)?1:"saturation".equals(n)?3:1;
                        normalized.put(n,number(node,n,def,low,high));
                    }
                    break;
                case "wheels":
                    normalized.put("lift",rgb(node,"lift",0,-.3,.3));
                    normalized.put("gamma",rgb(node,"gamma",1,.25,4));
                    normalized.put("gain",rgb(node,"gain",1,0,4));
                    break;
                case "curve":
                    String channel=node.optString("channel","master");
                    if(!Set.of("master","red","green","blue").contains(channel))
                        throw new IllegalArgumentException("Curve channel must be master/red/green/blue");
                    normalized.put("channel",channel);
                    JSONArray points=node.optJSONArray("points");
                    if(points==null||points.length()<2||points.length()>16)
                        throw new IllegalArgumentException("Curve needs 2..16 x/y points");
                    JSONArray ordered=new JSONArray();
                    double lastX=-1;
                    for(int j=0;j<points.length();j++){
                        JSONArray pair=points.optJSONArray(j);
                        if(pair==null||pair.length()!=2)
                            throw new IllegalArgumentException("Curve points must be [x,y] pairs");
                        double x=numberAt(pair,0,0,1),y=numberAt(pair,1,0,1);
                        if(x<=lastX)throw new IllegalArgumentException("Curve X points must strictly increase");
                        if(j==0&&x!=0||j==points.length()-1&&x!=1)
                            throw new IllegalArgumentException("Curve must include x=0 and x=1 endpoints");
                        lastX=x;
                        ordered.put(new JSONArray().put(x).put(y));
                    }
                    normalized.put("points",ordered);
                    break;
                case "hue_qualifier":
                    normalized.put("centerHue",number(node,"centerHue",0,0,360));
                    normalized.put("width",number(node,"width",30,1,180));
                    normalized.put("softness",number(node,"softness",10,0,90));
                    normalized.put("saturation",number(node,"saturation",1,0,3));
                    normalized.put("gain",number(node,"gain",1,0,3));
                    break;
                case "filmic":
                    normalized.put("toe",number(node,"toe",0,0,1));
                    normalized.put("shoulder",number(node,"shoulder",0,0,1));
                    break;
            }
            result.put(normalized);
        }
        return result;
    }

    private static double number(JSONObject o,String key,double fallback,double lo,double hi) {
        Object v=o.opt(key);
        if(v==null)return fallback;
        if(!(v instanceof Number))throw new IllegalArgumentException(key+" must be numeric");
        double x=((Number)v).doubleValue();
        if(!Double.isFinite(x)||x<lo||x>hi)
            throw new IllegalArgumentException(key+" outside ["+lo+","+hi+"]");
        return x;
    }
    private static double numberAt(JSONArray a,int i,double lo,double hi) {
        Object v=a.opt(i);
        if(!(v instanceof Number))throw new IllegalArgumentException("Curve needs numeric points");
        double d=((Number)v).doubleValue();
        if(!Double.isFinite(d)||d<lo||d>hi)
            throw new IllegalArgumentException("Curve point must be in [0,1]");
        return d;
    }
    private static JSONArray rgb(JSONObject node,String key,double def,double lo,double hi) {
        JSONArray v=node.optJSONArray(key);
        if(v==null){
            if(node.has(key))throw new IllegalArgumentException(key+" needs exactly 3 numeric channels");
            return new JSONArray().put((Object)Double.valueOf(def))
                    .put((Object)Double.valueOf(def)).put((Object)Double.valueOf(def));
        }
        if(v.length()!=3)throw new IllegalArgumentException(key+" must contain three channels");
        JSONArray rgb=new JSONArray();
        for(int i=0;i<3;i++)rgb.put((Object)Double.valueOf(numberAtRgb(v,i,key,lo,hi)));
        return rgb;
    }
    private static double numberAtRgb(JSONArray values,int i,String label,double lo,double hi) {
        Object raw=values.opt(i);
        if(!(raw instanceof Number))throw new IllegalArgumentException(label+" channel must be numeric");
        double v=((Number)raw).doubleValue();
        if(!Double.isFinite(v)||v<lo||v>hi)
            throw new IllegalArgumentException(label+" channel outside permitted range");
        return v;
    }

    /** CPU LUT builder, NEVER invoked for each video frame. */
    public static int[][][] buildCube(JSONArray nodes) throws Exception {
        JSONArray prepared=validate(nodes);
        ArrayList<JSONObject> serialized=new ArrayList<>();
        for(int i=0;i<prepared.length();i++)serialized.add(prepared.getJSONObject(i));
        int[][][] cube=new int[LUT_SIZE][LUT_SIZE][LUT_SIZE];
        for(int r=0;r<LUT_SIZE;r++)for(int g=0;g<LUT_SIZE;g++)for(int b=0;b<LUT_SIZE;b++){
            double[] color={r/(double)(LUT_SIZE-1),g/(double)(LUT_SIZE-1),b/(double)(LUT_SIZE-1)};
            for(JSONObject node:serialized)applyNode(color,node);
            int ir=toByte(color[0]),ig=toByte(color[1]),ib=toByte(color[2]);
            cube[r][g][b]=0xff000000|(ir<<16)|(ig<<8)|ib;
        }
        return cube;
    }

    public static Effect createLut(JSONArray nodes) throws Exception {
        return SingleColorLut.createFromCube(buildCube(nodes));
    }

    public static int colorAt(JSONArray nodes,int red,int green,int blue) throws Exception {
        if(red<0||red>255||green<0||green>255||blue<0||blue>255)
            throw new IllegalArgumentException("Expected 8-bit RGB");
        JSONArray prepared=validate(nodes);
        double[] rgb={red/255.,green/255.,blue/255.};
        for(int i=0;i<prepared.length();i++)applyNode(rgb,prepared.getJSONObject(i));
        return 0xff000000|(toByte(rgb[0])<<16)|(toByte(rgb[1])<<8)|toByte(rgb[2]);
    }
    private static void applyNode(double[] rgb,JSONObject n) throws Exception {
        switch(n.getString("type")){
            case "primaries":{
                double exposure=Math.pow(2,n.getDouble("exposure"));
                double contrast=n.getDouble("contrast"),pivot=n.getDouble("pivot");
                for(int i=0;i<3;i++)rgb[i]=(rgb[i]*exposure-pivot)*contrast+pivot;
                double t=n.getDouble("temperature"),tint=n.getDouble("tint");
                rgb[0]+=t*.12;
                rgb[2]-=t*.12;
                rgb[1]+=tint*.10;
                double y=rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722;
                double saturation=n.getDouble("saturation");
                for(int i=0;i<3;i++)rgb[i]=y+(rgb[i]-y)*saturation;
                break;
            }
            case "wheels":{
                JSONArray lift=n.getJSONArray("lift"),gamma=n.getJSONArray("gamma"),gain=n.getJSONArray("gain");
                for(int i=0;i<3;i++)
                    rgb[i]=Math.pow(clamp(rgb[i]+lift.getDouble(i)),1/gamma.getDouble(i))
                            *gain.getDouble(i);
                break;
            }
            case "curve":{
                JSONArray points=n.getJSONArray("points");
                String channel=n.getString("channel");
                for(int i=0;i<3;i++){
                    if(!"master".equals(channel) && !channel.equals(i==0?"red":i==1?"green":"blue"))continue;
                    rgb[i]=curve(clamp(rgb[i]),points);
                }
                break;
            }
            case "hue_qualifier":{
                double max=Math.max(rgb[0],Math.max(rgb[1],rgb[2]));
                double min=Math.min(rgb[0],Math.min(rgb[1],rgb[2])),delta=max-min;
                if(delta<.00001)break;
                double hue=max==rgb[0]?60*((rgb[1]-rgb[2])/delta%6):
                        max==rgb[1]?60*((rgb[2]-rgb[0])/delta+2):
                                60*((rgb[0]-rgb[1])/delta+4);
                hue=(hue+360)%360;
                double distance=Math.abs(hue-n.getDouble("centerHue"));
                distance=Math.min(distance,360-distance);
                double width=n.getDouble("width"),soft=n.getDouble("softness");
                double weight=soft<=.00001?(distance<=width?1:0):
                        1-smooth((distance-width)/soft);
                if(weight<=0)break;
                double y=rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722;
                for(int i=0;i<3;i++){
                    double selected=(y+(rgb[i]-y)*n.getDouble("saturation"))*n.getDouble("gain");
                    rgb[i]=rgb[i]*(1-weight)+selected*weight;
                }
                break;
            }
            case "filmic":{
                double toe=n.getDouble("toe"),shoulder=n.getDouble("shoulder");
                for(int i=0;i<3;i++){
                    double x=clamp(rgb[i]);
                    if(toe>0)x=Math.pow(x,1+toe*1.5);
                    if(shoulder>0){
                        double a=1+shoulder*1.75;
                        x=(a*x)/(1+(a-1)*x);
                    }
                    rgb[i]=x;
                }
                break;
            }
            default:throw new IllegalArgumentException("Unknown node");
        }
    }
    private static double curve(double x,JSONArray pairs) throws Exception {
        for(int i=1;i<pairs.length();i++){
            JSONArray b=pairs.getJSONArray(i),a=pairs.getJSONArray(i-1);
            double x1=b.getDouble(0);
            if(x>x1&&i<pairs.length()-1)continue;
            double x0=a.getDouble(0),factor=(x-x0)/(x1-x0);
            return a.getDouble(1)+(b.getDouble(1)-a.getDouble(1))*factor;
        }
        return x;
    }
    private static double smooth(double v){double t=clamp(v);return t*t*(3-2*t);}
    private static double clamp(double v){return Math.max(0,Math.min(1,v));}
    private static int toByte(double v){return (int)Math.round(clamp(v)*255);}
}
