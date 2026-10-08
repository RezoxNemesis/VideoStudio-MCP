package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** CLIP byte-level BPE. Vocabulary and merge rules are verified files in the model pack. */
public final class ClipBpeTokenizer {
    private final JSONObject vocabulary;
    private final Map<String,Integer> ranks=new HashMap<>();
    private final String[] byteAlphabet=new String[256];
    private final int bos,eos,pad;
    private static final Pattern WORDS=Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",Pattern.UNICODE_CHARACTER_CLASS);
    public ClipBpeTokenizer(JSONObject vocabulary,String merges,int bos,int eos,int pad) {
        this.vocabulary=vocabulary;this.bos=bos;this.eos=eos;this.pad=pad;
        if(vocabulary.length()>60000||merges.length()>1024*1024) throw new IllegalArgumentException("Tokenizer exceeds CLIP bounds");
        int extra=0;
        for(int b=0;b<256;b++) {boolean visible=b>=33&&b<=126||b>=161&&b<=172||b>=174;byteAlphabet[b]=new String(Character.toChars(visible?b:256+extra++));}
        int rank=0;for(String row:merges.split("\\r?\\n")) {String text=row.trim();if(text.isEmpty()||text.startsWith("#")) continue;if(text.split(" ").length!=2) throw new IllegalArgumentException("Invalid BPE merge");ranks.put(text,rank++);}
    }
    public long[] encode(String prompt) throws Exception {
        if(prompt==null||prompt.length()>4000) throw new IllegalArgumentException("Prompt requires at most 4000 characters");
        long[] ids=new long[77];Arrays.fill(ids,pad);ids[0]=bos;int at=1;
        String text=Normalizer.normalize(prompt,Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
        Matcher matcher=WORDS.matcher(text);
        outer:while(matcher.find()) {
            ArrayList<String> symbols=new ArrayList<>();byte[] utf8=matcher.group().getBytes(StandardCharsets.UTF_8);
            for(byte b:utf8) symbols.add(byteAlphabet[b&255]);
            symbols.set(symbols.size()-1,symbols.get(symbols.size()-1)+"</w>");
            while(symbols.size()>1) {
                int best=-1,bestRank=Integer.MAX_VALUE;
                for(int i=0;i<symbols.size()-1;i++) {Integer rank=ranks.get(symbols.get(i)+" "+symbols.get(i+1));if(rank!=null&&rank<bestRank) {best=i;bestRank=rank;}}
                if(best<0) break;symbols.set(best,symbols.get(best)+symbols.remove(best+1));
            }
            for(String token:symbols) {if(at>=76) break outer;if(!vocabulary.has(token)) throw new IllegalArgumentException("Tokenizer vocabulary does not cover prompt token");int id=vocabulary.getInt(token);if(id<0||id>60000) throw new IllegalArgumentException("Invalid token ID");ids[at++]=id;}
        }
        ids[at]=eos;return ids;
    }
}
