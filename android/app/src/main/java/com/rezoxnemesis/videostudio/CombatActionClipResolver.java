package com.rezoxnemesis.videostudio;

import java.util.Locale;

/**
 * Interprets authored glTF animation clip names conservatively.
 * It never claims that a generic "idle" clip is a proper sword attack.
 * Missing actions return an explicit fallback status for native QA.
 */
public final class CombatActionClipResolver {
    private CombatActionClipResolver(){}
    public static final class Choice {
        public final int index;
        public final boolean semanticMatch;
        public final String matchedName;
        Choice(int index,boolean matched,String name) {
            this.index=index;semanticMatch=matched;matchedName=name;
        }
    }

    public static Choice resolve(String[] clips,CombatActionPlan.Beat beat,boolean black) {
        if(clips==null||clips.length==0||beat==null)
            throw new IllegalArgumentException("An animated GLB and named action are required");
        String[] words=keywords(beat,black);
        for(String word:words){
            for(int i=0;i<clips.length;i++){
                String name=clips[i]==null?"":clips[i].toLowerCase(Locale.ROOT);
                if(name.contains(word))return new Choice(i,true,clips[i]);
            }
        }
        // A bounded honest fallback. Never invent an unavailable named clip.
        return new Choice(0,false,clips[0]==null?"unknown":clips[0]);
    }

    private static String[] keywords(CombatActionPlan.Beat beat,boolean black) {
        switch(beat) {
            case GUARD: return new String[]{"idle","ready","guard","stand","breath"};
            case APPROACH: return new String[]{"run","sprint","dash","walk","charge"};
            case LEAP: return new String[]{"leap","jump","flip","vault","air"};
            case STRIKE:
                return black?new String[]{"slash","attack","swing","cut","strike"}
                            :new String[]{"parry","block","defend","attack","slash"};
            case CONTACT:
                return black?new String[]{"strike","clash","attack","slash","parry"}
                            :new String[]{"block","parry","clash","defend","guard"};
            case COUNTER:
                return black?new String[]{"block","dodge","parry","slash","attack"}
                            :new String[]{"counter","slash","attack","swing","strike"};
            case KNOCKBACK:
                return black?new String[]{"follow","slash","attack","finish","recover"}
                            :new String[]{"hit","stagger","hurt","fall","recoil"};
            case LAND: return new String[]{"land","recover","stand","idle","ready"};
            default: return new String[]{"idle"};
        }
    }
}
