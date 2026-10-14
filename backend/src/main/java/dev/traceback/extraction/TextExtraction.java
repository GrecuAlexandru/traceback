package dev.traceback.extraction;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.ArrayList;
import java.util.List;

public final class TextExtraction {
    private TextExtraction() {}
    public static List<SourceBlock> blocks(String text, boolean markdown, Integer page, int maximum) {
        if (text.codePointCount(0, text.length()) > maximum) throw new ExtractionFailure("TEXT_LIMIT");
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
            throw new ExtractionFailure("INVALID_TEXT");
        var blocks = new ArrayList<SourceBlock>();
        String section = null;
        boolean fence = false;
        var paragraph = new StringBuilder();
        int offset = 0, start = 0;
        String[] lines = text.split("\n", -1);
        for (int lineIndex=0;lineIndex<lines.length;lineIndex++) {
            String line=lines[lineIndex];
            boolean heading = markdown && !fence && line.matches("#{1,6}\\s+.+");
            if (line.isBlank() || heading) {
                if (!paragraph.isEmpty()) {
                    if(blocks.size()>=8192)throw new ExtractionFailure("STRUCTURE_LIMIT");
                    blocks.add(new SourceBlock(paragraph.toString().strip(), section, page, start, offset));
                    paragraph.setLength(0);
                }
                if (heading) {
                    section = line.replaceFirst("^#{1,6}\\s+", "").strip();
                    if(section.codePointCount(0,section.length())>1600)throw new ExtractionFailure("STRUCTURE_LIMIT");
                }
            }
            if (!line.isBlank()) {
                if (paragraph.isEmpty()) start = offset;
                else paragraph.append('\n');
                paragraph.append(line);
            }
            if (markdown && line.stripLeading().matches("(```|~~~).*")) fence = !fence;
            offset += line.codePointCount(0, line.length()) + (lineIndex<lines.length-1?1:0);
        }
        if (!paragraph.isEmpty()) {
            if(blocks.size()>=8192)throw new ExtractionFailure("STRUCTURE_LIMIT");
            blocks.add(new SourceBlock(paragraph.toString().strip(), section, page, start, offset));
        }
        if (blocks.isEmpty()) throw new ExtractionFailure("NO_TEXT");
        return List.copyOf(blocks);
    }
    public static List<SourceBlock> extract(byte[] bytes, boolean markdown, int maximum) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return blocks(text, markdown, null, maximum);
        } catch (CharacterCodingException ex) { throw new ExtractionFailure("INVALID_UTF8"); }
    }
}
