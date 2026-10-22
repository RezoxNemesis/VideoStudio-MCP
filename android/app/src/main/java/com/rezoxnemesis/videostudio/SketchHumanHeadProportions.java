package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/** Optional per-character local construction proportions; no source-pixel or hidden-view fitting. */
final class SketchHumanHeadProportions {
    private static final SketchHumanHeadProportions IDENTITY=new SketchHumanHeadProportions(1,1,1);
    final double jawWidth,chinLength,eyeWidth;
    private SketchHumanHeadProportions(double jaw,double chin,double eye){jawWidth=jaw;chinLength=chin;eyeWidth=eye;}

    static void validate(JSONObject settings)throws Exception {
        if(!settings.has("headProportions"))return;
        if(!"anatomy_sketch".equals(settings.optString("figureStyle")))throw SketchDocument.bad("Head proportions require anatomy_sketch");
        Object value=settings.get("headProportions");
        if(!(value instanceof JSONObject))throw SketchDocument.bad("Head proportions must be an object");
        JSONObject profile=(JSONObject)value;SketchDocument.fields(profile,"jawWidth,chinLength,eyeWidth");
        if(profile.length()==0)throw SketchDocument.bad("Head proportions need at least one explicit scale");
        for(String field:new String[]{"jawWidth","chinLength","eyeWidth"})if(profile.has(field))SketchDocument.number(profile,field,.75,1.25);
    }
    static SketchHumanHeadProportions from(JSONObject settings){
        JSONObject p=settings.optJSONObject("headProportions");if(p==null)return IDENTITY;
        return new SketchHumanHeadProportions(p.optDouble("jawWidth",1),p.optDouble("chinLength",1),p.optDouble("eyeWidth",1));
    }
    /** Mandibular taper joins the cheek continuously; the resting mouth anchors chin length. */
    double[] face(double[] point){
        if(jawWidth==1&&chinLength==1)return point;
        double x=point[0],y=point[1];
        if(jawWidth!=1&&y>11){double t=Math.min(1,(y-11)/6);double weight=t*t*(3-2*t);x*=1+(jawWidth-1)*weight;}
        if(chinLength!=1&&y>14.3)y=14.3+(y-14.3)*chinLength;
        return new double[]{x,y,point[2]};
    }
    /** Scale both tangent components, retaining genuine width at exact profile as well as front. */
    double[] eye(double[] point,int side){
        if(eyeWidth==1)return point;
        double cx=side*8.6,cz=17.55;
        return new double[]{cx+(point[0]-cx)*eyeWidth,point[1],cz+(point[2]-cz)*eyeWidth};
    }
    static JSONObject capabilities()throws Exception{return new JSONObject("{\"minimumNativeVersion\":\"4.10.13\",\"field\":\"headProportions\",\"fields\":[\"jawWidth\",\"chinLength\",\"eyeWidth\"],\"scaleRange\":[0.75,1.25],\"figureStyle\":\"anatomy_sketch\",\"coordinateSpace\":\"Local head construction before camera projection, triangle facing and depth\",\"jawWidth\":\"Lower mandibular width joins the cheek from localy11 to17\",\"chinLength\":\"Lower-face length below resting mouth localy14.3\",\"eyeWidth\":\"Socket, lids, iris and brow scale in localx and localz about their eye anchor\",\"manualControls\":\"Human acting > Figure style anatomy_sketch > Character head proportions\",\"storedGeometry\":\"Editable native paths or retained human.settings; no source pixels copied\",\"omittedBehavior\":\"Omission preserves original geometry, pixels, normalized requests and version floors; explicit identity scales preserve geometry and pixels\",\"recoversUnseenCharacterViews\":false,\"guaranteesAiIdentity\":false,\"limitations\":\"Authored proportional construction, not automatic source fitting, recovered side or rear anatomy, fitted fringe topology or approved character artwork\"}");}
}
