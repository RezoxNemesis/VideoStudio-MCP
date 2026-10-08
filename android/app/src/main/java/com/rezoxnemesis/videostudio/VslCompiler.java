package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Bounded native VSL frontend. Unsupported neural operations are explicit compiler requirements. */
public final class VslCompiler {
    public static final String VERSION = "0.1";
    public static final String DEMO = "vsl 0.1\nscene living_world {\n duration = 4s\n fps = 30\n canvas = 720x1280\n background = \"#081326\"\n object crystal {\n  type = cube\n  position = vector(-0.8, 0, 4)\n  move = vector(0.8, 0.3, 4)\n  size = 0.65\n  color = \"#22dcff\"\n  spin = 35deg\n }\n cloth flag {\n  position = vector(0.65, 0.4, 0)\n  width = 0.3\n  height = 0.2\n  color = \"#eb44ff\"\n  wind = 0.06\n }\n camera {\n  orbit = 4deg\n }\n generate {\n  only = changed_regions\n  temporal_consistency = strict\n  progressive = true\n  max_repairs = 2\n }\n}\n";

    public JSONObject compile(String source, String projectId) throws Exception {
        if (source == null || source.trim().isEmpty() || source.length() > 20000)
            throw new IllegalArgumentException("VSL source requires 1..20000 characters");
        JSONObject out = new JSONObject(), graph = new JSONObject(), camera = new JSONObject(), policy = new JSONObject();
        JSONArray entities = new JSONArray(), missing = new JSONArray();
        graph.put("version", 1); graph.put("background", "#081326"); graph.put("objects", entities);
        out.put("language", "vsl"); out.put("languageVersion", VERSION); out.put("runtimeTarget", "android_native");
        out.put("projectId", projectId); out.put("name", "scene"); out.put("durationMs", 4000); out.put("fps", 30);
        out.put("width", 720); out.put("height", 1280); out.put("graph", graph); out.put("camera", camera);
        out.put("policy", policy); out.put("missingCapabilities", missing);
        policy.put("progressive", true); policy.put("maxRepairs", 2); policy.put("only", "changed_regions");
        Deque<String> stack = new ArrayDeque<>();
        Set<String> ids = new HashSet<>(); JSONObject current = null;
        List<String> statements = statements(source);
        for (int i=0; i<statements.size(); i++) {
            String statement=statements.get(i).trim(); if(statement.isEmpty()) continue;
            try {
                if (statement.equals("}")) {
                    if (stack.isEmpty()) throw new IllegalArgumentException("Unexpected closing brace");
                    String closed=stack.pop(); if (!closed.equals("scene")) current=null;
                    continue;
                }
                if (statement.endsWith("{")) {
                    String header=statement.substring(0,statement.length()-1).trim();
                    String[] words=header.split("\\s+"); String kind=words[0];
                    if (!stack.isEmpty() && !stack.peek().equals("scene")) throw new IllegalArgumentException("Nested entity blocks are not supported");
                    if (kind.equals("scene")) {
                        if (!stack.isEmpty() || words.length!=2) throw new IllegalArgumentException("scene <name> expected");
                        out.put("name", id(words[1])); stack.push(kind); continue;
                    }
                    if (kind.equals("object") || kind.equals("subject") || kind.equals("portal") || kind.equals("cloth")) {
                        if(words.length!=2 || entities.length()>=128) throw new IllegalArgumentException("Entity requires an ID; limit 128");
                        String name=id(words[1]); if(!ids.add(name)) throw new IllegalArgumentException("Duplicate entity: "+name);
                        current=new JSONObject(); current.put("id",name); current.put("kind",kind);
                        current.put("type",kind.equals("cloth") ? "cloth" : kind.equals("portal") || kind.equals("subject") ? "image" : "cube");
                        current.put("spin",0); entities.put(current); stack.push(kind); continue;
                    }
                    if (kind.equals("camera")) { current=camera; stack.push(kind); continue; }
                    if (kind.equals("generate")) { current=policy; stack.push(kind); continue; }
                    if (kind.equals("light")) {
                        current=new JSONObject(); graph.put("light",current); stack.push(kind); continue;
                    }
                    throw new IllegalArgumentException("Unknown block: "+kind);
                }
                if (statement.equals("vsl 0.1")) continue;
                int equal=statement.indexOf('=');
                if(equal<1) throw new IllegalArgumentException("Expected property = value");
                String key=statement.substring(0,equal).trim(), value=statement.substring(equal+1).trim();
                String kind=stack.isEmpty() ? "scene" : stack.peek();
                if(kind.equals("scene")) {
                    switch(key) {
                        case "duration": out.put("durationMs",Math.round(number(value,"s",.1,120)*1000)); break;
                        case "fps": out.put("fps",integer(value,12,60)); break;
                        case "canvas": {
                            String[] dims=value.split("x"); if(dims.length!=2) throw new IllegalArgumentException("canvas WIDTHxHEIGHT expected");
                            out.put("width",integer(dims[0],128,1920)); out.put("height",integer(dims[1],128,1920)); break;
                        }
                        case "background": graph.put(key,color(value)); break;
                        default: throw new IllegalArgumentException("Unknown scene property: "+key);
                    }
                } else if(kind.equals("camera")) {
                    switch(key) {
                        case "orbit": graph.put("cameraOrbit",number(value,"deg",-45,45)); break;
                        case "dolly": graph.put("cameraDolly",number(value,"m",-2,2)); break;
                        case "fov": graph.put(key,number(value,"deg",20,100)); break;
                        default: throw new IllegalArgumentException("Unknown camera property: "+key);
                    }
                    current.put(key,value);
                } else if(kind.equals("generate")) {
                    switch(key) {
                        case "only": if(!value.equals("changed_regions") && !value.equals("full_frame")) throw new IllegalArgumentException("Invalid generation scope"); current.put(key,value); break;
                        case "temporal_consistency": if(!value.equals("strict")) throw new IllegalArgumentException("Only strict continuity supported"); current.put(key,value); break;
                        case "progressive": current.put(key,bool(value)); break;
                        case "max_repairs": current.put("maxRepairs",integer(value,0,2)); break;
                        case "semantic_validation": if(!value.equals("unchecked") && !value.equals("required")) throw new IllegalArgumentException("Invalid semantic validation policy"); current.put("semanticValidation",value); if(value.equals("required")) missing.put("render.critique.semantic"); break;
                        default: throw new IllegalArgumentException("Unknown generate property: "+key);
                    }
                } else if(kind.equals("light")) {
                    if(key.equals("direction")) current.put(key,vector(value));
                    else if(key.equals("intensity")) current.put(key,number(value,"",0,2));
                    else throw new IllegalArgumentException("Unknown light property: "+key);
                } else {
                    switch(key) {
                        case "type": if(!Arrays.asList("circle","rectangle","ellipse","line","cube","pyramid","image","video","cloth").contains(value)) throw new IllegalArgumentException("Unsupported object type"); current.put(key,value); break;
                        case "position": case "move": {
                            JSONArray v=vector(value); String prefix=key.equals("move")?"to":"";
                            for(int k=0;k<3;k++) current.put(prefix+(prefix.isEmpty()?new String[]{"x","y","z"}[k]:new String[]{"X","Y","Z"}[k]),v.getDouble(k)); break;
                        }
                        case "asset": current.put("assetId",unquote(value)); break;
                        case "identity": {
                            if(!value.startsWith("lock(") || !value.endsWith(")")) throw new IllegalArgumentException("identity = lock(assetId) expected");
                            current.put("assetId",unquote(value.substring(5,value.length()-1).trim())); current.put("identityLocked",true); break;
                        }
                        case "preserve": current.put(key,value); break;
                        case "pose": current.put(key,value); missing.put("image.synthesis.pose"); break;
                        case "depth": if(value.equals("auto")) missing.put("monocular.depth"); else current.put("z",number(value,"m",.1,100)); break;
                        case "color": current.put(key,color(value)); break;
                        case "size": case "width": case "height": current.put(key,number(value,"",.001,10)); break;
                        case "spin": case "rotation": current.put(key,number(value,"deg",-360,360)); break;
                        case "wind": current.put(key,number(value,"",0,.15)); break;
                        case "turbulence": current.put(key,number(value,"",0,1)); break;
                        case "roughness": current.put(key,number(value,"",0,1)); break;
                        case "physics": if(!value.equals("analytic_wind")) throw new IllegalArgumentException("Only analytic_wind physics supported"); current.put(key,value); break;
                        default: throw new IllegalArgumentException("Unknown entity property: "+key);
                    }
                }
            } catch(Exception e) { throw new IllegalArgumentException("VSL statement "+(i+1)+": "+e.getMessage(),e); }
        }
        if(!stack.isEmpty()) throw new IllegalArgumentException("Unclosed block: "+stack.peek());
        if(entities.length()==0) throw new IllegalArgumentException("Scene requires at least one entity");
        for(int i=0;i<entities.length();i++) {
            JSONObject entity=entities.getJSONObject(i); String type=entity.getString("type");
            if((type.equals("image") || type.equals("video")) && entity.optString("assetId").isEmpty()) throw new IllegalArgumentException("Image entity requires an imported asset: "+entity.getString("id"));
            if(!type.equals("image") && !type.equals("video") && entity.has("assetId")) throw new IllegalArgumentException("Asset identity requires type = image");
        }
        out.put("ready",missing.length()==0); out.put("semanticValidation","unchecked");
        out.put("progressiveStages",new JSONArray(new int[]{320,512,720,1080}));
        return out;
    }
    static String id(String value) {
        if(!value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid entity/scene ID"); return value;
    }
    private static boolean bool(String value) { if(!value.equals("true")&&!value.equals("false")) throw new IllegalArgumentException("Boolean expected"); return Boolean.parseBoolean(value); }
    private static int integer(String value,int min,int max) { double n=number(value,"",min,max); if(n!=Math.floor(n)) throw new IllegalArgumentException("Integer expected"); return (int)n; }
    static double number(String value,String unit,double min,double max) {
        String text=value.trim(); if(!unit.isEmpty() && text.endsWith(unit)) text=text.substring(0,text.length()-unit.length());
        double n; try {n=Double.parseDouble(text);} catch(Exception e) {throw new IllegalArgumentException("Expected number"+(unit.isEmpty()?"":" in "+unit));}
        if(!Double.isFinite(n) || n<min || n>max) throw new IllegalArgumentException("Number must be "+min+".."+max); return n;
    }
    private static JSONArray vector(String value) throws Exception {
        if(!value.startsWith("vector(")||!value.endsWith(")")) throw new IllegalArgumentException("vector(x,y,z) expected");
        String[] values=value.substring(7,value.length()-1).split(","); if(values.length!=3) throw new IllegalArgumentException("Vector requires three coordinates");
        JSONArray result=new JSONArray(); for(String v:values) result.put(number(v,"m",-100,100)); return result;
    }
    private static String color(String value) { String c=unquote(value); if(!c.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) throw new IllegalArgumentException("Hex color required"); return c; }
    private static String unquote(String value) { return value.startsWith("\"")&&value.endsWith("\"")?value.substring(1,value.length()-1):value; }
    private static List<String> statements(String source) {
        List<String> result=new ArrayList<>(); StringBuilder buffer=new StringBuilder(); boolean quote=false,comment=false; int parens=0;
        for(int i=0;i<source.length();i++) {
            char c=source.charAt(i);
            if(comment) {if(c!='\n') continue; comment=false;}
            if(!quote && c=='/' && i+1<source.length() && source.charAt(i+1)=='/') {comment=true; i++; continue;}
            if(c=='"') quote=!quote;
            if(!quote) {
                if(c=='(') parens++; if(c==')') parens--;
                if(parens<0) throw new IllegalArgumentException("Unbalanced parentheses");
                if(parens==0 && (c=='\n'||c==';'||c=='{'||c=='}')) {
                    if(c=='{') buffer.append(c);
                    if(buffer.length()>0) result.add(buffer.toString()); buffer.setLength(0);
                    if(c=='}') result.add("}"); continue;
                }
            }
            buffer.append(c);
        }
        if(quote||parens!=0) throw new IllegalArgumentException("Unclosed string or expression");
        if(buffer.length()>0) result.add(buffer.toString()); return result;
    }
}
