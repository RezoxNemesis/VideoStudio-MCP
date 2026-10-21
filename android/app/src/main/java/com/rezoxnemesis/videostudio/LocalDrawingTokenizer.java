package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** CLIP byte-pair tokenizer for the explicitly installed SD1.5 vocabulary. */
final class LocalDrawingTokenizer {
    static final int TOKENS=77, BOS=49406, EOS=49407;
    private static final Pattern WORDS=Pattern.compile("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private final Map<String,Integer> vocabulary=new HashMap<>(),ranks=new HashMap<>();
    private final String[] bytes=new String[256];
    LocalDrawingTokenizer(File root)throws Exception {
        File vocab=new File(root,"tokenizer/vocab.json"),merges=new File(root,"tokenizer/merges.txt");
        if(!vocab.isFile()||vocab.length()>2*1024*1024||!merges.isFile()||merges.length()>1024*1024)throw new IllegalArgumentException("LOCAL_DRAWING_TOKENIZER_INVALID");
        JSONObject json=new JSONObject(new String(Files.readAllBytes(vocab.toPath()),StandardCharsets.UTF_8));
        if(json.length()!=49408)throw new IllegalArgumentException("LOCAL_DRAWING_VOCABULARY_MISMATCH");
        Iterator<String> keys=json.keys();while(keys.hasNext()){String key=keys.next();Object value=json.get(key);if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue()||((Number)value).intValue()<0||((Number)value).intValue()>49407)throw new IllegalArgumentException("LOCAL_DRAWING_VOCABULARY_MISMATCH");vocabulary.put(key,((Number)value).intValue());}
        if(!Objects.equals(vocabulary.get("<|startoftext|>"),BOS)||!Objects.equals(vocabulary.get("<|endoftext|>"),EOS))throw new IllegalArgumentException("LOCAL_DRAWING_SPECIAL_TOKENS_MISMATCH");
        List<Integer> order=new ArrayList<>();for(int i=33;i<=126;i++)order.add(i);for(int i=161;i<=172;i++)order.add(i);for(int i=174;i<=255;i++)order.add(i);
        int extra=256;for(int b:order)bytes[b]=String.valueOf((char)b);for(int b=0;b<256;b++)if(bytes[b]==null)bytes[b]=String.valueOf((char)extra++);
        List<String> rules=Files.readAllLines(merges.toPath(),StandardCharsets.UTF_8);int n=0;
        for(String rule:rules){if(rule.startsWith("#version:")||rule.trim().isEmpty())continue;if(n>=48894)break;String[] pair=rule.split(" ");if(pair.length!=2||ranks.put(pair[0]+"\u0000"+pair[1],n++)!=null)throw new IllegalArgumentException("LOCAL_DRAWING_MERGES_INVALID");}
        if(n!=48894)throw new IllegalArgumentException("LOCAL_DRAWING_MERGES_INVALID");
    }
    long[] encode(String prompt)throws Exception {
        if(prompt==null||prompt.length()>1200)throw new IllegalArgumentException("Drawing prompt must contain at most1200 characters");
        String normalized=Normalizer.normalize(prompt,Normalizer.Form.NFC).trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
        long[] result=new long[TOKENS];Arrays.fill(result,EOS);result[0]=BOS;int index=1;
        Matcher words=WORDS.matcher(normalized);
        while(words.find()){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Local drawing tokenization cancelled");
            String word=words.group();List<String> pieces;
            if(word.equals("<|startoftext|>")||word.equals("<|endoftext|>"))throw new IllegalArgumentException("Do not embed CLIP control tokens in a drawing prompt");
            StringBuilder encoded=new StringBuilder();for(byte b:word.getBytes(StandardCharsets.UTF_8))encoded.append(bytes[b&255]);
            pieces=new ArrayList<>();for(int i=0;i<encoded.length();i++)pieces.add(encoded.substring(i,i+1)+(i==encoded.length()-1?"</w>":""));
            while(pieces.size()>1){
                int best=-1,bestRank=Integer.MAX_VALUE;for(int i=0;i<pieces.size()-1;i++){Integer rank=ranks.get(pieces.get(i)+"\u0000"+pieces.get(i+1));if(rank!=null&&rank<bestRank){best=i;bestRank=rank;}}
                if(best<0)break;String a=pieces.get(best),b=pieces.get(best+1);List<String> merged=new ArrayList<>();
                for(int i=0;i<pieces.size();i++){if(i+1<pieces.size()&&pieces.get(i).equals(a)&&pieces.get(i+1).equals(b)){merged.add(a+b);i++;}else merged.add(pieces.get(i));}pieces=merged;
            }
            for(String piece:pieces){Integer id=vocabulary.get(piece);if(id==null)throw new IllegalArgumentException("LOCAL_DRAWING_TOKEN_UNKNOWN");if(index>=TOKENS-1)throw new IllegalArgumentException("Drawing prompt exceeds75 CLIP tokens; shorten the selected study instructions");result[index++]=id;}
        }
        return result;
    }
}
