package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Human-facing organization; every entry maps to a real existing editor action. */
public final class StudioToolCatalog {
    private static final Set<String> UNAVAILABLE=Set.of("Freeze","Reverse","Speed Ramp","Motion Blur",
            "Transitions","Fonts","Captions","Audio Duck");
    public static final String[] GROUPS={
        "Cut & Arrange","Motion","Color & Effects","Text & Audio","Animation & 3D"
    };
    public static final class Tool {
        public final String label,group,description;
        public final boolean previewRequiresExport,experimental,supported;
        Tool(String group,String label,String description,
             boolean previewRequiresExport,boolean experimental) {
            this.group=group;this.label=label;this.description=description;
            this.supported=isSupported(label);
            this.previewRequiresExport=previewRequiresExport&&!supported;this.experimental=experimental;
        }
    }
    private static final ArrayList<Tool> ITEMS=new ArrayList<>();
    static {
        add(0,"Split","Cut clip at playhead",false,false);
        add(0,"Trim","Choose source in/out",false,false);
        add(0,"Duplicate","Duplicate selected clip",false,false);
        add(0,"Delete Clip","Remove clip from timeline",false,false);
        add(0,"Move Left","Move clip one place earlier",false,false);
        add(0,"Move Right","Move clip one place later",false,false);
        add(0,"Freeze","Freeze at current moment",true,true);
        add(0,"Reverse","Reverse playback",true,true);

        add(1,"Slow Motion","Change playback speed",true,false);
        add(1,"Speed Ramp","Speed curve preset",true,true);
        add(1,"Motion","Pan or tracking motion",true,true);
        add(1,"Keyframes","Exact transform times and interpolation",false,false);
        add(1,"Motion Blur","Add directional blur",true,true);
        add(1,"Shake","Impact camera movement",true,true);
        add(1,"Transitions","Change between-clip transition",true,true);

        add(2,"Colour","Switch color-grading preset",true,false);
        add(2,"Adjust","Direct brightness, contrast, saturation and lightness",true,false);
        add(2,"Transform","Direct zoom and rotation sliders",true,false);
        add(2,"Exact Transform","Numeric position, axis scale, rotation and anchor",false,false);
        add(2,"Blur","Apply blur",true,false);
        add(2,"Opacity","Exact static or keyframed alpha",false,false);
        add(2,"Glow","Native thresholded nine-tap bloom",false,false);
        add(2,"Crop","Exact normalized source crop",false,false);
        add(2,"Reframe","Choose the source viewport",false,false);
        add(2,"Green Screen","Native chroma key with tolerance and feather",false,false);
        add(2,"Mask","Native feathered rectangle or ellipse",false,false);
        add(2,"Effects","Creative effect preset",true,true);
        add(2,"Overlay","Place a project-owned image on the timeline",false,false);

        add(3,"Text","Edit clip title",true,false);
        add(3,"Add Music Track","Insert synchronized mixed audio",true,false);
        add(3,"Add Caption Track","Insert timed text burned into MP4",true,false);
        add(3,"Fonts","Change title font",true,true);
        add(3,"Captions","Caption styling",true,true);
        add(3,"Volume","Toggle gain/mute",true,false);
        add(3,"Audio Duck","Dialogue ducking",true,true);

        add(4,"Animation Director","Sequence source poses",false,false);
        add(4,"Rig Builder","Trace actors and joints",false,false);
        add(4,"Render Rigged 2D","Render your traced actors",false,false);
        add(4,"3D Model Viewer","Preview imported GLB",false,false);
        add(4,"Render 3D Fight","Export two rigged GLBs",false,false);
        add(4,"Combat Benchmark","Procedural motion test",false,false);
        add(4,"Sketch Studio","Layered drawings and cel animation",false,false);
        add(4,"Open Pipeline","Self-hosted generation and verified Drive exchange",false,false);
    }
    private StudioToolCatalog(){}
    public static boolean isSupported(String label){
        return !UNAVAILABLE.contains(label);
    }
    private static void add(int i,String title,String description,boolean export,boolean experimental){
        ITEMS.add(new Tool(GROUPS[i],title,description,export,experimental));
    }
    public static List<Tool> group(int i) {
        if(i<0||i>=GROUPS.length)return Collections.emptyList();
        ArrayList<Tool> found=new ArrayList<>();
        for(Tool t:ITEMS)if(GROUPS[i].equals(t.group))found.add(t);
        return found;
    }
    public static List<Tool> find(String query) {
        String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        if(q.isEmpty())return new ArrayList<>(ITEMS);
        ArrayList<Tool> found=new ArrayList<>();
        for(Tool t:ITEMS)if(t.label.toLowerCase(Locale.ROOT).contains(q)||
                t.group.toLowerCase(Locale.ROOT).contains(q)||
                t.description.toLowerCase(Locale.ROOT).contains(q))
            found.add(t);
        return found;
    }
}
