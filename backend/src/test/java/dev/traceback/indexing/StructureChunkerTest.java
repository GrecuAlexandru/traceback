package dev.traceback.indexing;
import dev.traceback.extraction.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StructureChunkerTest {
    IndexSettings settings(int size,int overlap,int max) {return new IndexSettings(false,"nomic-embed-text:v1.5","sha256:"+"1".repeat(64),768,size,overlap,max,1000000,200,90,30,600,5);}
    @Test void deterministicIdentityOrderUnicodeOverlapAndPages() {
        var chunker=new StructureChunker(settings(200,20,512));var revision=UUID.randomUUID();
        var blocks=List.of(new SourceBlock("π😀".repeat(250),"Heading",3,0,500));
        var result=chunker.chunks(revision,blocks);assertEquals(result,chunker.chunks(revision,blocks));assertEquals(3,result.size());
        assertEquals(3,result.getFirst().pageStart());assertEquals(200,StructureChunker.points(result.getFirst().text()));
        assertEquals(StructureChunker.slice(result.get(0).text(),180,200),StructureChunker.slice(result.get(1).text(),0,20));
        assertNotEquals(result.getFirst().id(),chunker.chunks(UUID.randomUUID(),blocks).getFirst().id());
        assertNotEquals(result.getFirst().id(),new StructureChunker(settings(200,10,512)).chunks(revision,blocks).getFirst().id());
    }
    @Test void sectionsSplitAndParagraphsCombineWithinLimit() {
        var result=new StructureChunker(settings(200,20,512)).chunks(UUID.randomUUID(),List.of(
            new SourceBlock("First","A",null,0,5),new SourceBlock("Second","A",null,7,13),new SourceBlock("Third","B",null,15,20)));
        assertEquals(2,result.size());assertEquals("First\n\nSecond",result.getFirst().text());assertEquals("B",result.getLast().section());
    }
    @Test void excessiveChunksAreRejected() {assertEquals("CHUNK_LIMIT",assertThrows(ExtractionFailure.class,()->new StructureChunker(settings(200,20,1))
            .chunks(UUID.randomUUID(),List.of(new SourceBlock("x".repeat(500),null,null,0,500)))).code());}
}
