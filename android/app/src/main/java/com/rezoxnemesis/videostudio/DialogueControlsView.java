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
    }
    private void label(LinearLayout parent,String text){TextView v=new TextView(getContext());v.setText(text);v.setPadding(0,10,0,4);parent.addView(v);}
    private EditText field(LinearLayout parent,String caption,String value,boolean number){label(parent,caption);EditText v=new EditText(getContext());v.setText(value);v.setSingleLine(number);v.setInputType(number?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);parent.addView(v);return v;}
    private void addLine(){Line line=new Line(lines.size());lines.add(line);rows.addView(line.view);}
    private final class Line {
        final LinearLayout view=new LinearLayout(getContext());final EditText actor,text,start,slot,speed;final Spinner voice;final String id=UUID.randomUUID().toString();String restoredId;
        Line(int index){view.setOrientation(VERTICAL);label(view,"Dialogue line "+(index+1));actor=field(view,"Character ID","character_1",false);voice=new Spinner(getContext());view.addView(voice);updateVoices();text=field(view,"Dialogue",index==0?"Wait. I have something to tell you.":"",false);start=field(view,"Start time in milliseconds",String.valueOf(index*6000),true);slot=field(view,"Available time in milliseconds","6000",true);speed=field(view,"Speaking speed (0.75–1.25)","1.0",true);Button remove=new Button(getContext());remove.setText("Remove this line");remove.setOnClickListener(v->{if(lines.size()>1){lines.remove(this);rows.removeView(view);}});view.addView(remove);}
        void updateVoices(){int chosen=Math.max(0,voice.getSelectedItemPosition());String[] names=language.getSelectedItemPosition()==1?new String[]{"Female • Alpha","Male • Omega"}:new String[]{"Female • Heart","Male • Adam"};voice.setAdapter(new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_dropdown_item,names));voice.setSelection(Math.min(chosen,1));}
        JSONObject json()throws Exception {return new JSONObject().put("lineId",restoredId==null?id:restoredId).put("characterId",actor.getText().toString().trim()).put("text",text.getText().toString()).put("voice",DialogueControls.VOICES[language.getSelectedItemPosition()][voice.getSelectedItemPosition()]).put("startMs",Long.parseLong(start.getText().toString())).put("slotMs",Long.parseLong(slot.getText().toString())).put("speed",Double.parseDouble(speed.getText().toString()));}
    }
    JSONObject controls()throws Exception {JSONArray values=new JSONArray();for(Line line:lines)values.put(line.json());return DialogueControls.validate(new JSONObject().put("language",DialogueControls.LANGUAGES[language.getSelectedItemPosition()]).put("durationMs",Long.parseLong(duration.getText().toString())).put("lines",values));}
    void loadControls(JSONObject value)throws Exception {
        JSONObject c=DialogueControls.validate(value);language.setSelection(Arrays.asList(DialogueControls.LANGUAGES).indexOf(c.getString("language")));duration.setText(String.valueOf(c.getLong("durationMs")));rows.removeAllViews();lines.clear();
        JSONArray entries=c.getJSONArray("lines");for(int i=0;i<entries.length();i++){addLine();Line line=lines.get(i);JSONObject entry=entries.getJSONObject(i);line.restoredId=entry.getString("lineId");line.actor.setText(entry.getString("characterId"));line.text.setText(entry.getString("text"));line.start.setText(String.valueOf(entry.getLong("startMs")));line.slot.setText(String.valueOf(entry.getLong("slotMs")));line.speed.setText(String.valueOf(entry.optDouble("speed",1)));line.updateVoices();line.voice.setSelection(Arrays.asList(DialogueControls.VOICES[language.getSelectedItemPosition()]).indexOf(entry.getString("voice")));}
    }
}
