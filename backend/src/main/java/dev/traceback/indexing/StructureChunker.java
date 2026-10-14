package dev.traceback.indexing;

import dev.traceback.extraction.*;
import dev.traceback.knowledge.DocumentUploadValidator;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class StructureChunker {
    private final IndexSettings settings;
    public StructureChunker(IndexSettings settings) { this.settings=settings; }
    public List<Chunk> chunks(UUID revision,List<SourceBlock> blocks) {
        var result=new ArrayList<Chunk>();
        StringBuilder text=new StringBuilder();String section=null;Integer firstPage=null,lastPage=null;int start=0,end=0;
        for (var block:blocks) {
            if (!text.isEmpty() && (!Objects.equals(section,block.section()) || points(text.toString())+2+points(block.text())>settings.chunkSize())) {
                String previous=text.toString();
                boolean carry=Objects.equals(section,block.section()) && settings.overlap()>0 && points(block.text())+2+settings.overlap()<=settings.chunkSize();
                append(result,revision,previous,section,firstPage,lastPage,start,end);text.setLength(0);
                if(carry){int length=Math.min(settings.overlap(),points(previous));text.append(slice(previous,points(previous)-length,points(previous)));start=Math.max(start,end-length);}
            }
            if (points(block.text())>settings.chunkSize()) {
                int pos=0;
                while(pos<points(block.text())) {
                    int next=Math.min(points(block.text()),pos+settings.chunkSize());
                    append(result,revision,slice(block.text(),pos,next),block.section(),block.page(),block.page(),block.start()+pos,block.start()+next);
                    if(next==points(block.text())) break;
                    pos=next-settings.overlap();
                }
            } else {
                if(text.isEmpty()) {section=block.section();firstPage=block.page();start=block.start();}
                else text.append("\n\n");
                text.append(block.text());lastPage=block.page();end=block.end();
            }
        }
        if(!text.isEmpty()) append(result,revision,text.toString(),section,firstPage,lastPage,start,end);
        if(result.isEmpty()) throw new ExtractionFailure("NO_TEXT");
        return List.copyOf(result);
    }
    private void append(List<Chunk> result,UUID revision,String content,String section,Integer first,Integer last,int start,int end) {
        if(result.size()>=settings.maxChunks()) throw new ExtractionFailure("CHUNK_LIMIT");
        String checksum=DocumentUploadValidator.checksum(content.getBytes(StandardCharsets.UTF_8));int ordinal=result.size();
        UUID id=UUID.nameUUIDFromBytes((revision+"|"+settings.configuration()+"|"+ordinal+"|"+checksum).getBytes(StandardCharsets.UTF_8));
        result.add(new Chunk(id,ordinal,content,section==null?null:slice(section,0,Math.min(points(section),1600)),first,last,start,end,checksum));
    }
    public static int points(String s) {return s.codePointCount(0,s.length());}
    public static String slice(String s,int start,int end) {return s.substring(s.offsetByCodePoints(0,start),s.offsetByCodePoints(0,end));}
    public record Chunk(UUID id,int ordinal,String text,String section,Integer pageStart,Integer pageEnd,int start,int end,String checksum) {}
}
