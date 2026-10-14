package dev.traceback.extraction;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ExtractionTest {
    DocumentExtractor extractor = new DocumentExtractor("traceback-pdf:m12",200,1000000,30);
    static byte[] pdf(int pages, boolean text) throws Exception {
        try (var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(int i=0;i<pages;i++) {
                var page=new PDPage();doc.addPage(page);
                if (text) {
                    var font=new COSDictionary();font.setItem(COSName.TYPE,COSName.FONT);font.setItem(COSName.SUBTYPE,COSName.TYPE1);
                    font.setItem(COSName.BASE_FONT,COSName.getPDFName("Helvetica"));
                    var fonts=new COSDictionary();fonts.setItem(COSName.getPDFName("F1"),font);
                    var resources=new COSDictionary();resources.setItem(COSName.FONT,fonts);page.setResources(new PDResources(resources));
                    var stream=new PDStream(doc);
                    try(var content=stream.createOutputStream()) { content.write(("BT /F1 12 Tf 20 700 Td (HTTP 503 page "+(i+1)+") Tj ET").getBytes(StandardCharsets.US_ASCII)); }
                    page.setContents(stream);
                }
            }
            doc.save(out);return out.toByteArray();
        }
    }
    @Test void utf8MarkdownPreservesHeadingsParagraphsAndInertInstructions() {
        var result=extractor.extract("# Recovery\n\nHTTP 503 π\n\n```sh\nrm -rf /\n```".getBytes(StandardCharsets.UTF_8),"text/markdown");
        assertEquals("Recovery",result.get(1).section());assertTrue(result.get(1).text().contains("π"));
        assertTrue(result.getLast().text().contains("rm -rf"));assertEquals(result,extractor.extract("# Recovery\n\nHTTP 503 π\n\n```sh\nrm -rf /\n```".getBytes(StandardCharsets.UTF_8),"text/markdown"));
    }
    @Test void textRejectsInvalidEmptyControlAndOversizedInput() {
        assertTrue(extractor.extract("AUTH_TOKEN_EXPIRED π".getBytes(StandardCharsets.UTF_8),"text/plain").getFirst().text().contains("π"));
        assertEquals("FILE_LIMIT",assertThrows(ExtractionFailure.class,()->new DocumentExtractor("traceback-pdf:m12",200,1000000,30,5).extract("longer".getBytes(),"text/plain")).code());
        for(byte[] data:new byte[][]{new byte[]{(byte)255},new byte[]{0}," \n".getBytes()})
            assertThrows(ExtractionFailure.class,()->extractor.extract(data,"text/plain"));
        assertEquals("TEXT_LIMIT",assertThrows(ExtractionFailure.class,()->new DocumentExtractor("traceback-pdf:m12",200,3,30)
                .extract("Hello".getBytes(),"text/plain")).code());
    }
    @Test void isolatedPdfReturnsRealTextAndPageReferences() throws Exception {
        var blocks=extractor.extract(pdf(2,true),"application/pdf");
        assertEquals(2,blocks.size());assertEquals(1,blocks.getFirst().page());assertEquals(2,blocks.getLast().page());
        assertTrue(blocks.getLast().text().contains("HTTP 503 page 2"));
    }
    @Test void malformedScannedAndPageLimitedPdfHaveExplicitOutcomes() throws Exception {
        assertEquals("INVALID_PDF",assertThrows(ExtractionFailure.class,()->extractor.extract("%PDF-1.7\nbroken".getBytes(),"application/pdf")).code());
        assertEquals("NO_TEXT",assertThrows(ExtractionFailure.class,()->extractor.extract(pdf(1,false),"application/pdf")).code());
        assertEquals("PAGE_LIMIT",assertThrows(ExtractionFailure.class,()->new DocumentExtractor("traceback-pdf:m12",1,1000000,30).extract(pdf(2,true),"application/pdf")).code());
    }
    @Test void missingSandboxFailsClosed() throws Exception {
        assertEquals("PARSER_UNAVAILABLE",assertThrows(ExtractionFailure.class,()->new DocumentExtractor("traceback-pdf:missing-m12",200,1000000,30).extract(pdf(1,true),"application/pdf")).code());
    }
    @Test void stalledParserIsKilledAtDeadlineAndContainerRemoved() throws Exception {
        var directory=java.nio.file.Files.createTempDirectory("traceback-stalled-parser-");
        java.nio.file.Files.writeString(directory.resolve("Dockerfile"),"FROM traceback-pdf:m12\nENTRYPOINT [\"sh\",\"-c\",\"sleep 30\"]\n");
        var build=new ProcessBuilder("docker","build","-t","traceback-pdf:stalled-m12",directory.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {assertTrue(build.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,build.exitValue());}
        finally {build.destroyForcibly();java.nio.file.Files.deleteIfExists(directory.resolve("Dockerfile"));java.nio.file.Files.deleteIfExists(directory);}
        long started=System.nanoTime();
        assertEquals("EXTRACTION_TIMEOUT",assertThrows(ExtractionFailure.class,()->new DocumentExtractor("traceback-pdf:stalled-m12",200,1000000,1).extract(pdf(1,true),"application/pdf")).code());
        assertTrue(java.time.Duration.ofNanos(System.nanoTime()-started).toSeconds()<10);
        var ps=new ProcessBuilder("docker","ps","--filter","ancestor=traceback-pdf:stalled-m12","--format","{{.ID}}")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(ps.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(new String(ps.getInputStream().readAllBytes()).isBlank());
    }
    @Test void pdfOutputIsBoundedDuringExtraction() throws Exception {
        assertEquals("TEXT_LIMIT",assertThrows(ExtractionFailure.class,()->new DocumentExtractor("traceback-pdf:m12",200,20,30).extract(pdf(2,true),"application/pdf")).code());
    }
    @Test void oversizedMarkdownHeadingCannotAmplifyArtifactMetadata(){
        assertEquals("STRUCTURE_LIMIT",assertThrows(ExtractionFailure.class,()->extractor.extract(("# "+"x".repeat(1601)+"\n\nparagraph").getBytes(StandardCharsets.UTF_8),"text/markdown")).code());
    }
}
