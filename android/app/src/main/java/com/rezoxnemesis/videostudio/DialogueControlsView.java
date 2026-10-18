package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Manual dialogue form emits the same controls as the MCP facade. */
final class DialogueControlsView extends LinearLayout {
    final Spinner language;final EditText duration;
    private final LinearLayout rows;private final List<Line> lines=new ArrayList<>();
    DialogueControlsView(Context c){
        super(c);setOrientation(VERTICAL);label(this,"Character dialogue");label(this,"Keep one voice per character. Set each line's start and available time before generating.");
        language=new Spinner(c);language.setAdapter(new ArrayAdapter<>(c,android.R.layout.simple_spinner_dropdown_item,new String[]{"English","हिन्दी"}));addView(language);
        duration=field(this,"Scene length in milliseconds","120000",true);rows=new LinearLayout(c);rows.setOrientation(VERTICAL);addView(rows);addLine();
        language.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){for(Line line:lines)line.updateVoices();}});
        Button add=new Button(c);add.setText("Add dialogue line");add.setOnClickListener(v->{if(lines.size()<32)addLine();else Toast.makeText(c,"Create another dialogue request for more lines",Toast.LENGTH_SHORT).show();});addView(add);
        Button story=new Button(c);story.setText("Use jungle film dialogue");story.setOnClickListener(v->{try{loadControls(SketchJungleFilm.dialogueControls(DialogueControls.LANGUAGES[language.getSelectedItemPosition()]));}catch(Exception error){Toast.makeText(c,error.getMessage(),Toast.LENGTH_LONG).show();}});addView(story);
        Button lantern=new Button(c);lantern.setText("Use Last Lantern dialogue");lantern.setOnClickListener(v->{try(java.io.InputStream in=c.getAssets().open("animation_stories/the-last-lantern-dialogue-controls.json");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);loadControls(new JSONObject(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception error){Toast.makeText(c,error.getMessage(),Toast.LENGTH_LONG).show();}});addView(lantern);
    }
    private void label(LinearLayout parent,String text){TextView v=new TextView(getContext());v.setText(text);v.setPadding(0,10,0,4);parent.addView(v);}
    private EditText field(LinearLayout parent,String caption,String value,boolean number){label(parent,caption);EditText v=new EditText(getContext());v.setText(value);v.setSingleLine(number);v.setInputType(number?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);parent.addView(v);return v;}
    private void addLine(){Line line=new Line(lines.size());lines.add(line);rows.addView(line.view);}
    private final class Line {
        final LinearLayout view=new LinearLayout(getContext());final EditText actor,text,start,slot,speed;final Spinner voice;final String id=UUID.randomUUID().toString();String restoredId;
        final LinearLayout phraseRows=new LinearLayout(getContext());final List<Phrase> phrases=new ArrayList<>();
        Line(int index){view.setOrientation(VERTICAL);label(view,"Dialogue line "+(index+1));actor=field(view,"Character ID","character_1",false);voice=new Spinner(getContext());view.addView(voice);updateVoices();text=field(view,"Dialogue",index==0?"Wait. I have something to tell you.":"",false);start=field(view,"Start time in milliseconds",String.valueOf(index*6000),true);slot=field(view,"Available time in milliseconds","6000",true);speed=field(view,"Speaking speed (0.75–1.25)","1.0",true);
            label(view,"Optional phrases: 1–8. Join their text with single spaces to match Dialogue exactly. Pauses include the last phrase and must fit the available time.");
            phraseRows.setOrientation(VERTICAL);view.addView(phraseRows);
            Button add=new Button(getContext());add.setText("Add phrase");add.setOnClickListener(v->{if(phrases.size()<8)addPhrase(phrases.isEmpty()?text.getText().toString():"","","0");else Toast.makeText(getContext(),"Use at most 8 phrases per line",Toast.LENGTH_SHORT).show();});view.addView(add);
            Button whole=new Button(getContext());whole.setText("Use whole line pacing");whole.setOnClickListener(v->{phrases.clear();phraseRows.removeAllViews();});view.addView(whole);
            Button remove=new Button(getContext());remove.setText("Remove this line");remove.setOnClickListener(v->{if(lines.size()>1){lines.remove(this);rows.removeView(view);}});view.addView(remove);}
        void addPhrase(String text,String speed,String pause){Phrase phrase=new Phrase(text,speed,pause);phrases.add(phrase);phraseRows.addView(phrase.view);}
        final class Phrase {
            final LinearLayout view=new LinearLayout(getContext());final EditText text,speed,pause;
            Phrase(String value,String pace,String gap){view.setOrientation(VERTICAL);text=field(view,"Phrase text",value,false);speed=field(view,"Phrase speed (0.75–1.25; blank inherits line speed)",pace,true);pause=field(view,"Pause after phrase in milliseconds (0–1500; blank = 0)",gap,true);
                Button remove=new Button(getContext());remove.setText("Remove phrase");remove.setOnClickListener(v->{phrases.remove(this);phraseRows.removeView(view);});view.addView(remove);}
            JSONObject json()throws Exception {JSONObject p=new JSONObject().put("text",text.getText().toString());String pace=speed.getText().toString().trim(),gap=pause.getText().toString().trim();if(!pace.isEmpty())p.put("speed",Double.parseDouble(pace));if(!gap.isEmpty())p.put("pauseAfterMs",Long.parseLong(gap));return p;}
        }
        void updateVoices(){int chosen=Math.max(0,voice.getSelectedItemPosition());String[] names=language.getSelectedItemPosition()==1?new String[]{"Female • Alpha","Male • Omega"}:new String[]{"Female • Heart","Male • Adam"};voice.setAdapter(new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_dropdown_item,names));voice.setSelection(Math.min(chosen,1));}
        JSONObject json()throws Exception {JSONObject line=new JSONObject().put("lineId",restoredId==null?id:restoredId).put("characterId",actor.getText().toString().trim()).put("text",text.getText().toString()).put("voice",DialogueControls.VOICES[language.getSelectedItemPosition()][voice.getSelectedItemPosition()]).put("startMs",Long.parseLong(start.getText().toString())).put("slotMs",Long.parseLong(slot.getText().toString())).put("speed",Double.parseDouble(speed.getText().toString()));if(!phrases.isEmpty()){JSONArray parts=new JSONArray();for(Phrase phrase:phrases)parts.put(phrase.json());line.put("phrases",parts);}return line;}
    }
    JSONObject controls()throws Exception {JSONArray values=new JSONArray();for(Line line:lines)values.put(line.json());return DialogueControls.validate(new JSONObject().put("language",DialogueControls.LANGUAGES[language.getSelectedItemPosition()]).put("durationMs",Long.parseLong(duration.getText().toString())).put("lines",values));}
    void loadControls(JSONObject value)throws Exception {
        JSONObject c=DialogueControls.validate(value);language.setSelection(Arrays.asList(DialogueControls.LANGUAGES).indexOf(c.getString("language")));duration.setText(String.valueOf(c.getLong("durationMs")));rows.removeAllViews();lines.clear();
        JSONArray entries=c.getJSONArray("lines");for(int i=0;i<entries.length();i++){addLine();Line line=lines.get(i);JSONObject entry=entries.getJSONObject(i);line.restoredId=entry.getString("lineId");line.actor.setText(entry.getString("characterId"));line.text.setText(entry.getString("text"));line.start.setText(String.valueOf(entry.getLong("startMs")));line.slot.setText(String.valueOf(entry.getLong("slotMs")));line.speed.setText(String.valueOf(entry.optDouble("speed",1)));line.updateVoices();line.voice.setSelection(Arrays.asList(DialogueControls.VOICES[language.getSelectedItemPosition()]).indexOf(entry.getString("voice")));
            if(entry.has("phrases")){JSONArray phrases=entry.getJSONArray("phrases");for(int p=0;p<phrases.length();p++){JSONObject phrase=phrases.getJSONObject(p);line.addPhrase(phrase.getString("text"),phrase.has("speed")?String.valueOf(phrase.getDouble("speed")):"",phrase.has("pauseAfterMs")?String.valueOf(phrase.getLong("pauseAfterMs")):"");}}
        }
    }
}
