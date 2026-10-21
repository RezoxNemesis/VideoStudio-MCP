package com.rezoxnemesis.videostudio;
import org.json.*;

/** Separate native book for reviewing new drawings without replacing the earlier film. */
public final class JunglePerformanceStudy {
    public static final int FRAMES=480,FPS=24;
    private JunglePerformanceStudy(){}
    public static JSONObject drawCel(String id,int frame,double mouth)throws Exception {
        if(frame<0||frame>=FRAMES||!Double.isFinite(mouth)||mouth<0||mouth>1)throw SketchDocument.bad("Choose a study frame and a mouth opening from 0 to 1");
        int source=frame<144?720+frame:Math.min(2327,2016+frame-144);
        JSONObject doc=SketchJungleFilm.drawCel(id,source,mouth);doc.put("name",frame<144?"Sword exchange · cleanup study":"Recognition and remorse · cleanup study");
        if(frame<144)JungleActionDrawing.draw(doc,frame);
        else {
            JSONObject hero=SketchDocument.layer(doc,"hero");hero.put("objects",new JSONArray());
            SketchDocument.layer(doc,"dragon").put("objects",new JSONArray());SketchDocument.layer(doc,"effects").put("objects",new JSONArray());
            SketchDocument.layer(doc,"foreground").put("visible",false);
            JungleActingDrawing.portrait(new JungleCelInk(hero),(frame-144)/(double)FPS,mouth);
        }
        SketchDocument.validate(doc);return doc;
    }
    public static JSONObject populateRange(android.content.Context c,String id,int start,int count,double[] mouths)throws Exception {
        if(start<0||count<1||count>64||(long)start+count>FRAMES||mouths!=null&&mouths.length!=FRAMES)throw SketchDocument.bad("Draw 1..64 cels inside the 480-frame study");
        if(mouths!=null)for(double value:mouths)if(!Double.isFinite(value)||value<0||value>1)throw SketchDocument.bad("Mouth openings must be from 0 to 1");
        SketchBookStore.execute(c,"sketch_book_create",new JSONObject().put("bookId",id).put("operationId","jungle_performance_v2").put("name","Beneath the canopy · drawing and acting study").put("pageCount",FRAMES).put("width",1280).put("height",720).put("fps",FPS).put("background","#FF092c38"));
        SketchBookStore.execute(c,"sketch_book_edit",new JSONObject().put("bookId",id).put("operationId","performance_shots").put("expectedRevision",1).put("cues",new JSONArray().put(cue("action",0,143,"Anticipation, clash and recoil")).put(cue("recognition",144,287,"Eyes lead the head; recognition")).put(cue("remorse",288,479,"Self-disgust, shame and lowered posture"))));
        int retained=0;
        try(SketchProjectStore sketches=new SketchProjectStore(c);SketchBookStore books=new SketchBookStore(c)){
            for(int page=start;page<start+count;page++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Study retained for resume");String cel=id+"_p"+page;
                if(has(sketches,cel))retained++;
                else {int source=page<144?720+page:2016+page-144;double lip=mouths==null?SketchJungleFilm.mouth(source/24d):mouths[page];
                    try{sketches.importOwnedDocument(drawCel(cel,page,lip),"original_performance_v2");}
                    catch(IllegalStateException error){if(!"SKETCH_ALREADY_EXISTS".equals(error.getMessage())||!has(sketches,cel))throw error;retained++;}
                }
                books.attachImportedPage(id,page,cel);
            }
            JSONObject book=books.get(id);SketchSequenceTimeline time=new SketchSequenceTimeline(book,0,FRAMES);return new JSONObject().put("bookId",id).put("frameCount",time.frames()).put("fps",book.getInt("fps")).put("allocatedCels",book.getInt("createdPages")).put("preservedCels",retained).put("bookRevision",book.getLong("revision")).put("imageGenerationUsed",false).put("phoneExecutionVerified",false);
        }
    }
    private static JSONObject cue(String id,int first,int last,String label)throws Exception{return new JSONObject().put("id",id).put("type","shot").put("startFrame",first).put("endFrame",last).put("label",label);}
    private static boolean has(SketchProjectStore store,String id)throws Exception{try{store.get(id);return true;}catch(IllegalArgumentException e){if(e.getMessage()!=null&&e.getMessage().startsWith("UNKNOWN_SKETCH"))return false;throw e;}}
}
