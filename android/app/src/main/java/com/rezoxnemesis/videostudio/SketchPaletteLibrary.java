package com.rezoxnemesis.videostudio;


/** Hand-picked opaque ARGB pigments for painting; exact hex values are kept in the sketch. */
public final class SketchPaletteLibrary {
    private SketchPaletteLibrary(){}
    private static final String[] NAMES={
        "Essentials · original primary colours",
        "Portrait · light and undertones","Portrait · medium and olive",
        "Portrait · deep skin and shadows","Hair · ink and reflected light",
        "Clothing · fabric and leather","Landscape · forest and water",
        "City · night and rain","Film lighting · dawn and sunset",
        "Action · fire and energy","Natural world · stone and metal"
    };
    private static final String[][] COLORS={
        {"#FFFFFFFF","#FF161C2B","#FF000000","#FF3F3F46","#FFEF4444","#FFFFB020","#FFFDE68A","#FF22C55E","#FF0EA5E9","#FF3B82F6","#FF8B5CF6","#FFEC4899","#FF14B8A6","#FFA16207","#FFE5E7EB","#FF94A3B8"},
        {"#FFFFE9D7","#FFFFDCC8","#FFFBD4BE","#FFF5C5B4","#FFEFBBA0","#FFE3A888","#FFD6A08A","#FFC88878","#FFAC7768","#FF996859","#FF744D46","#FFEBCDC8","#FFD7AFA8","#FFB78672","#FFF7E4D8","#FFCFA595"},
        {"#FFF0CC9F","#FFE7B985","#FFDCA56E","#FFD29B77","#FFC58960","#FFB47755","#FFA57250","#FF97785A","#FF93724F","#FF827050","#FF735845","#FF634938","#FFAB9472","#FFBD8977","#FFBE9A79","#FF8B5D45"},
        {"#FFC88D73","#FFBC8065","#FFAC735B","#FF965F4B","#FF875641","#FF7D4D3C","#FF714936","#FF654336","#FF563828","#FF4C3028","#FF3C2927","#FF312321","#FFCB9A82","#FF9F6E58","#FFB88065","#FF664C48"},
        {"#FF060A14","#FF111623","#FF202536","#FF292C32","#FF3A393C","#FF4B4342","#FF66544C","#FF7B6255","#FF54465C","#FF3B5364","#FF6A7389","#FF8C8C99","#FF9EAAA9","#FFD4CCB9","#FFF1E8D8","#FFFEFEFD"},
        {"#FF161C29","#FF26334E","#FF30425B","#FF4A6076","#FF698298","#FF8FA5B2","#FFC1C5C4","#FFEEE4D3","#FF693F43","#FF8C5260","#FFB17278","#FF614A36","#FF987451","#FFBC9D81","#FF4A6545","#FF849D7B"},
        {"#FF0D242B","#FF143641","#FF1E4D47","#FF286957","#FF3A815C","#FF689F70","#FF91BB75","#FFC6D8A5","#FFE8E1A8","#FF324964","#FF437AA0","#FF5BA8C5","#FF9DD0CE","#FFD6E4DB","#FF6E6048","#FFB7A17B"},
        {"#FF0A1020","#FF101C30","#FF1F2D45","#FF2B3C55","#FF384B67","#FF506B87","#FF6D8096","#FF9BB3C0","#FFCAD7DC","#FFE4ECE8","#FF66A4C8","#FF7CCFE3","#FF5C729B","#FFFFCE8D","#FFF4B86B","#FF526578"},
        {"#FF182A49","#FF344F73","#FF5D7190","#FF8A94A9","#FFC6B6B3","#FFFFDDA8","#FFF4BA80","#FFE88E68","#FFC96B5B","#FFA24F68","#FF704A6A","#FF4D476D","#FFFFF0D0","#FFDBD0D0","#FF9BA8C0","#FF425974"},
        {"#FF1B1633","#FF37264E","#FF6A325C","#FFA94561","#FFF27157","#FFFFA75B","#FFFFCE75","#FFFFF2AF","#FFE84E54","#FFFF6464","#FF4AADD1","#FF65DEF0","#FF8E79F3","#FF9D52E2","#FF61B7A0","#FFB2EC95"},
        {"#FF181F2A","#FF303940","#FF4B5758","#FF6D7775","#FF929999","#FFB1B5B0","#FFD1D3CE","#FFF4F0E2","#FF46586B","#FF5B6E80","#FF91A6B0","#FFB0A18D","#FF8D735B","#FF6A5140","#FF41454D","#FFFCFAF5"}
    };
    public static int groupCount(){return NAMES.length;}
    public static String name(int group){if(group<0||group>=NAMES.length)throw new IllegalArgumentException("Unknown palette");return NAMES[group];}
    public static String[] colors(int group){if(group<0||group>=COLORS.length)throw new IllegalArgumentException("Unknown palette");return COLORS[group].clone();}
    public static String nearest(String color,String[] candidates) {
        int target=SketchDocument.color(color),best=0;double lowest=Double.POSITIVE_INFINITY;
        if(candidates==null||candidates.length==0)throw new IllegalArgumentException("Missing swatches");
        // Compare sRGB in perceptual-ish linear light to avoid oversaturating close skin shades.
        for(int i=0;i<candidates.length;i++){
            int c=SketchDocument.color(candidates[i]);
            double dr=linear((target>>>16)&255)-linear((c>>>16)&255);
            double dg=linear((target>>>8)&255)-linear((c>>>8)&255);
            double db=linear(target&255)-linear(c&255);
            double distance=.2126*dr*dr+.7152*dg*dg+.0722*db*db;
            if(distance<lowest){lowest=distance;best=i;}
        }
        return candidates[best];
    }
    private static double linear(int value){
        double v=value/255.0;return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);
    }
}
