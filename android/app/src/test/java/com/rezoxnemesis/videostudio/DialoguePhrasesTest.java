package com.rezoxnemesis.videostudio;

import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Dedicated phrase API, manual editing, and untrusted result-clock regressions. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class DialoguePhrasesTest {
    static JSONObject controls()throws Exception {
        JSONObject c=DialogueWorkflowTest.controls();JSONObject l=c.getJSONArray("lines").getJSONObject(0);
        l.put("text","Wait. Listen.").put("speed",1.1).put("phrases",new JSONArray()
            .put(new JSONObject().put("text","Wait.").put("speed",.8).put("pauseAfterMs",125))
            .put(new JSONObject().put("text","Listen.").put("pauseAfterMs",250)));
        return c;
    }
    @Test public void validatesBoundedPhrasesWithoutChangingOptionalFields()throws Exception {
        JSONObject c=controls();assertEquals(c.toString(),DialogueControls.validate(c).toString());
        JSONObject l=c.getJSONArray("lines").getJSONObject(0);JSONArray p=new JSONArray();StringJoiner text=new StringJoiner(" ");
        for(int i=0;i<8;i++){p.put(new JSONObject().put("text","a").put("speed",1.25).put("pauseAfterMs",1500));text.add("a");}
        l.put("text",text.toString()).put("phrases",p);DialogueControls.validate(c);
        p.put(new JSONObject().put("text","a"));assertThrows(Exception.class,()->DialogueControls.validate(c));
    }
    @Test public void rejectsLostWordsInvalidNumbersAndPhraseVoiceOverrides()throws Exception {
        for(String bad:new String[]{"[]","null","{}","[{\"text\":\"changed\"}]","[{\"text\":\" \"}]",
            "[{\"text\":\"Wait. Listen.\",\"speed\":true}]","[{\"text\":\"Wait. Listen.\",\"speed\":1.251}]",
            "[{\"text\":\"Wait. Listen.\",\"pauseAfterMs\":1.5}]","[{\"text\":\"Wait. Listen.\",\"pauseAfterMs\":-1}]",
            "[{\"text\":\"Wait. Listen.\",\"pauseAfterMs\":1.0}]","[{\"text\":\"Wait. Listen.\",\"pauseAfterMs\":true}]",
            "[{\"text\":\"Wait. Listen.\",\"pauseAfterMs\":1501}]","[{\"text\":\"Wait. Listen.\",\"voice\":\"hf_alpha\"}]"}){
            JSONObject c=controls();c.getJSONArray("lines").getJSONObject(0).put("phrases",new JSONTokener(bad).nextValue());
            assertThrows(bad,Exception.class,()->DialogueControls.validate(c));
        }
        JSONObject c=controls();c.getJSONArray("lines").put(new JSONObject(c.getJSONArray("lines").getJSONObject(0).toString()).put("lineId","two").put("startMs",7000).put("voice","hf_alpha"));
        assertThrows(IllegalArgumentException.class,()->DialogueControls.validate(c));
    }
    @Test public void shippedOriginalStoryControlsCanBeLoadedAndEditedManually()throws Exception {
        DialogueControlsView form=new DialogueControlsView(RuntimeEnvironment.getApplication());List<Button> buttons=new ArrayList<>();collect(form,Button.class,buttons);buttons.stream().filter(v->v.getText().toString().equals("Use Last Lantern dialogue")).findFirst().get().performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();JSONObject c=form.controls();assertEquals(124000,c.getInt("durationMs"));assertEquals(13,c.getJSONArray("lines").length());int phrases=0;for(int i=0;i<13;i++)phrases+=c.getJSONArray("lines").getJSONObject(i).getJSONArray("phrases").length();assertEquals(25,phrases);assertEquals("am_adam",c.getJSONArray("lines").getJSONObject(0).getString("voice"));assertEquals("af_heart",c.getJSONArray("lines").getJSONObject(1).getString("voice"));
    }
    @Test public void manualFormReloadsEditsAndRemovesPhrasesUsingNumericFields()throws Exception {
        DialogueControlsView form=new DialogueControlsView(RuntimeEnvironment.getApplication());form.loadControls(controls());
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        JSONObject line=form.controls().getJSONArray("lines").getJSONObject(0);
        assertEquals("hm_omega",line.getString("voice"));assertEquals(125,line.getJSONArray("phrases").getJSONObject(0).getInt("pauseAfterMs"));
        List<EditText> fields=new ArrayList<>();collect(form,EditText.class,fields);
        fields.stream().filter(v->v.getText().toString().equals("125")).findFirst().get().setText("300");
        fields.stream().filter(v->v.getText().toString().equals("0.8")).findFirst().get().setText("0.9");
        fields.stream().filter(v->v.getText().toString().equals("Listen.")).findFirst().get().setText("Listen carefully.");
        assertThrows(Exception.class,form::controls); // The full line must still match exactly.
        fields.stream().filter(v->v.getText().toString().equals("Wait. Listen.")).findFirst().get().setText("Wait. Listen carefully.");
        JSONArray phrases=form.controls().getJSONArray("lines").getJSONObject(0).getJSONArray("phrases");
        assertEquals(300,phrases.getJSONObject(0).getInt("pauseAfterMs"));assertEquals(.9,phrases.getJSONObject(0).getDouble("speed"),0);
        assertFalse(phrases.getJSONObject(1).has("speed"));
        List<Button> buttons=new ArrayList<>();collect(form,Button.class,buttons);
        buttons.stream().filter(v->v.getText().toString().equals("Use whole line pacing")).findFirst().get().performClick();
        assertFalse(form.controls().getJSONArray("lines").getJSONObject(0).has("phrases"));
        buttons.clear();collect(form,Button.class,buttons);buttons.stream().filter(v->v.getText().toString().equals("Add phrase")).findFirst().get().performClick();
        assertEquals("Wait. Listen carefully.",form.controls().getJSONArray("lines").getJSONObject(0).getJSONArray("phrases").getJSONObject(0).getString("text"));
    }
    private static <T> void collect(View view,Class<T> type,List<T> found){if(type.isInstance(view))found.add(type.cast(view));if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),type,found);}}

    @Test public void acceptsActualPcmGapsAndTrailingPauseAndNativeTimingUsesTheSameClock()throws Exception {
        try(Fixture f=new Fixture()){
            f.verify();JSONObject speech=SketchSpeechTiming.fromDialogue(f.timing,0);
            JSONObject resolved=SketchSpeechTiming.resolve(speech,12000);
            assertEquals("rest",resolved.getJSONArray("cues").getJSONObject(0).getString("shape"));
            assertArrayEquals(SketchSpeechTiming.sample(resolved,900),SketchSpeechTiming.sample(resolved,1150),0);
            assertArrayEquals(SketchSpeechTiming.sample(resolved,900),SketchSpeechTiming.sample(resolved,1400),0);
        }
    }
    @Test public void rejectsChangedMissingOrInjectedPhrasesAndCastInOutput()throws Exception {
        for(String change:new String[]{"missing","text","speed","pause","cast","injected","noTiming"})try(Fixture f=new Fixture()){
            JSONObject received=f.timing.getJSONArray("lines").getJSONObject(0);
            if(change.equals("missing"))received.remove("phrases");
            if(change.equals("text"))received.getJSONArray("phrases").getJSONObject(0).put("text","Lost");
            if(change.equals("speed"))received.getJSONArray("phrases").getJSONObject(0).put("speed",1.2);
            if(change.equals("pause"))received.getJSONArray("phrases").getJSONObject(0).put("pauseAfterMs",126);
            if(change.equals("cast"))f.timing.getJSONObject("cast").put("kael","hf_alpha");
            if(change.equals("injected"))f.request.getJSONObject("controls").getJSONArray("lines").getJSONObject(0).remove("phrases");
            if(change.equals("noTiming"))received.remove("timing");
            assertThrows(change,Exception.class,f::verify);
        }
    }
    @Test public void rejectsOverlappingWrongPauseNonzeroPauseAndMoreThanEightPredictions()throws Exception {
        for(String change:new String[]{"overlap","gap","trailing","nonzero","nine"})try(Fixture f=new Fixture()){
            JSONObject line=f.timing.getJSONArray("lines").getJSONObject(0);JSONArray segments=line.getJSONArray("timing");
            if(change.equals("overlap"))segments.getJSONObject(1).put("startSample",1000);
            if(change.equals("gap"))segments.getJSONObject(1).put("startSample",5401);
            if(change.equals("trailing"))line.getJSONArray("phrases").getJSONObject(1).put("pauseAfterMs",249);
            if(change.equals("nonzero"))ByteBuffer.wrap(f.stem).order(ByteOrder.LITTLE_ENDIAN).putShort(44+2400*2,(short)1);
            if(change.equals("nine")){f.request.getJSONObject("controls").getJSONArray("lines").getJSONObject(0).remove("phrases");line.remove("phrases");f.stem=DialogueWorkflowTest.wave(900);segments=new JSONArray();for(int i=0;i<9;i++)segments.put(segment(i*2400,2400));line.put("timing",segments);}
            assertThrows(change,Exception.class,f::verify);
        }
    }
    static JSONObject segment(long start,long count)throws Exception{return new JSONObject().put("phonemes","a").put("startSample",start).put("sampleCount",count).put("modelDurationUnits",new JSONArray().put(1).put(2).put(1)).put("unitsPerSecond",40).put("forcedAlignmentVerified",false);}
    private static final class Fixture implements AutoCloseable {
        final File root=Files.createTempDirectory("phrases_").toFile();final JSONObject request=new JSONObject().put("controls",controls());
        final JSONObject timing;byte[] stem=DialogueWorkflowTest.wave(575);
        Fixture()throws Exception {
            ByteBuffer b=ByteBuffer.wrap(stem).order(ByteOrder.LITTLE_ENDIAN);for(int i=2400;i<5400;i++)b.putShort(44+i*2,(short)0);for(int i=7800;i<13800;i++)b.putShort(44+i*2,(short)0);
            JSONObject line=new JSONObject(controls().getJSONArray("lines").getJSONObject(0).toString()).put("path","line_00.wav").put("timing",new JSONArray().put(segment(0,2400)).put(segment(5400,2400)));
            timing=new JSONObject().put("schema","videostudio.dialogue.v1").put("language","hi-IN").put("durationMs",12000).put("cast",new JSONObject().put("kael","hm_omega")).put("lines",new JSONArray().put(line));
        }
        void verify()throws Exception {
            JSONArray files=new JSONArray();String[] names={"dialogue.wav","line_00.wav","dialogue.json"},roles={"audio","dialogue_stem","dialogue_timing"};byte[][] data={DialogueWorkflowTest.wave(12000),stem,timing.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)};
            for(int i=0;i<3;i++){File file=new File(root,names[i]);Files.write(file.toPath(),data[i]);files.put(new JSONObject().put("path",names[i]).put("role",roles[i]).put("mime",i==2?"application/json":"audio/wav").put("bytes",file.length()).put("sha256",OpenPipelineArtifacts.hash(file)));}
            DialogueOutput.verify(root,request,files);
        }
        public void close(){File[] files=root.listFiles();if(files!=null)for(File f:files)f.delete();root.delete();}
    }
}
