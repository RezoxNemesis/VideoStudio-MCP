package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

/**
 * Read-only multi-column Xsheet must agree with every SketchFrameRenderer
 * exposure, including hidden layers, frame-page seams, real holds and blanks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchXsheetProjectionTest {
    private JSONObject rectangle(String id,int frame,int exposure,double x)throws Exception {
        return SketchEditOps.completeObject(new JSONObject().put("id",id)
                .put("kind","rect").put("frame",frame).put("exposure",exposure)
                .put("color","#FF3495B0").put("fill",true)
                .put("bounds",new JSONObject().put("x",x).put("y",.10)
                        .put("width",.04).put("height",.10)));
    }
    private JSONObject twentyLevels()throws Exception{
        JSONObject doc=SketchDocument.create(new JSONObject()
                .put("sketchId","xsheet_twenty").put("width",256).put("height",256)
                .put("frameCount",240));
        JSONArray layers=new JSONArray();
        for(int level=0;level<20;level++){
            JSONObject layer=new JSONObject().put("id","level_"+level)
                    .put("name","Level "+level).put("visible",level!=3)
                    .put("locked",level==4).put("opacity",1)
                    .put("transform",SketchDocument.transform())
                    .put("keys",new JSONArray()).put("objects",new JSONArray());
            // Two original cels per layer with blank gaps and intentional holds.
            layer.getJSONArray("objects").put(rectangle("ink_"+level+"_a",0,120,level*.04))
                    .put(rectangle("ink_"+level+"_b",160,80,level*.04));
            if(level==0)layer.getJSONArray("keys").put(new JSONObject()
                    .put("frame",50).put("interpolation","hold")
                    .put("transform",SketchDocument.transform()));
            layers.put(layer);
        }
        doc.put("layers",layers);SketchDocument.validate(doc);
        return doc;
    }
    @Test public void twentyLayerTwoHundredFortyFramePagesHaveExactHoldAndBlankParity()throws Exception {
        JSONObject source=twentyLevels(),frozen=SketchDocument.canonical(source);
        int exposed=0,blank=0,held=0,drawing=0;
        JSONArray layers=source.getJSONArray("layers");
        for(int offset=0;offset<240;offset+=48){
            JSONObject page=SketchXsheetProjection.project(source,offset,48);
            assertEquals("videostudio.sketch-xsheet.v1",page.getString("schema"));
            assertEquals(20,page.getInt("levelCount"));
            assertEquals(offset,page.getInt("frameOffset"));
            assertEquals(48,page.getInt("returnedFrames"));
            assertTrue(page.getBoolean("readOnly"));
            JSONArray columns=page.getJSONArray("columns");
            for(int level=0;level<20;level++){
                JSONObject column=columns.getJSONObject(level);
                assertEquals("level_"+level,column.getString("layerId"));
                assertEquals(level!=3,column.getBoolean("visible"));
                JSONArray cells=column.getJSONArray("cells");
                Map<String,Set<String>> drawings=new HashMap<>();
                JSONArray catalog=column.getJSONArray("drawings");
                for(int i=0;i<catalog.length();i++){
                    JSONObject entry=catalog.getJSONObject(i);
                    Set<String> seen=new HashSet<>();
                    JSONArray ids=entry.getJSONArray("objectIds");
                    for(int j=0;j<ids.length();j++)seen.add(ids.getString(j));
                    drawings.put(entry.getString("drawingId"),seen);
                }
                for(int r=0;r<cells.length();r++){
                    int frame=offset+r;
                    JSONObject cell=cells.getJSONObject(r);
                    assertEquals(frame,cell.getInt("frame"));
                    Set<String> expected=new HashSet<>();
                    JSONArray objs=layers.getJSONObject(level).getJSONArray("objects");
                    for(int j=0;j<objs.length();j++){
                        JSONObject obj=objs.getJSONObject(j);
                        if(SketchFrameRenderer.exposed(obj,frame))expected.add(obj.getString("id"));
                    }
                    Set<String> actual=cell.isNull("drawingId")?
                            new HashSet<>():drawings.get(cell.getString("drawingId"));
                    assertNotNull(actual);
                    assertEquals("Layer "+level+" frame "+frame,expected,actual);
                    if(expected.isEmpty()){assertEquals("blank",cell.getString("state"));blank++;}
                    else{
                        exposed++;
                        if(frame==0||frame==160){assertEquals("drawing",cell.getString("state"));drawing++;}
                        else{assertEquals("hold",cell.getString("state"));held++;}
                    }
                    if(level==0&&frame==50)
                        assertTrue("Transform motion keys are separate from held artwork",cell.getBoolean("transformKey"));
                }
            }
        }
        assertEquals(240*20,exposed+blank);
        assertEquals(20*200,exposed);
        assertEquals(20*40,blank);
        assertEquals(20*2,drawing);
        assertEquals(20*198,held);
        assertEquals(frozen,SketchDocument.canonical(source));
    }
    @Test public void paginationDoesNotInventNewCelsAtBeginningOfNextPage()throws Exception{
        JSONObject source=twentyLevels();
        JSONObject sheet=SketchXsheetProjection.project(source,48,12);
        JSONObject first=sheet.getJSONArray("columns").getJSONObject(0)
                .getJSONArray("cells").getJSONObject(0);
        assertEquals("hold",first.getString("state"));
        assertEquals(48,first.getInt("frame"));
        assertEquals(60,sheet.getInt("nextFrameOffset"));
        assertEquals(20,sheet.getInt("dictionaryDrawings"));
    }
    @Test public void xsheetRejectsOutOfRangeAndOversizedPagesWithoutMutation()throws Exception {
        JSONObject source=twentyLevels(),old=SketchDocument.canonical(source);
        for(int[] invalid:new int[][]{{-1,3},{240,1},{0,0},{0,49}}){
            try{SketchXsheetProjection.project(source,invalid[0],invalid[1]);fail();}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Xsheet"));}
        }
        assertEquals(old,SketchDocument.canonical(source));
    }
}
