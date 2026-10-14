package dev.traceback.rag;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** CommonMark code is literal data; only answer prose can declare source references. */
final class CitationPolicy {
    private static final Parser MARKDOWN = Parser.builder().build();
    private static final Pattern RESERVED = Pattern.compile(
            "(?is)(?:[sh]\\s*[0-9]|(?:doc(?:ument)?|src|ref(?:erence)?|source|citation|cite)\\s*[:#0-9]).*");
    private static final Pattern EXTERNAL_LABEL = Pattern.compile(
            "(?i)(?:[0-9]+|\\^[^\\s]+|(?:source|citation|cite|reference|ref)(?:\\s*#?\\d+)?)");

    private CitationPolicy() {}

    static Set<String> inline(String answer, Set<String> allowed) {
        var prose = new StringBuilder();
        collect(MARKDOWN.parse(answer), prose);
        String text = prose.toString();
        var references = new LinkedHashSet<String>();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\uE200' && ch <= '\uE2FF') invalid();
            if (ch == '【') {
                int end = text.indexOf('】', i + 1);
                String label = text.substring(i + 1, end < 0 ? text.length() : end);
                if (label.matches("(?is).*(?:†|turn[0-9]|source|cite).*")) invalid();
            }
            if (ch != '[') continue;
            int end = i + 1;
            while (end < text.length() && text.charAt(end) != ']' && text.charAt(end) != '\n') end++;
            String label = text.substring(i + 1, end).strip();
            boolean closed = end < text.length() && text.charAt(end) == ']';
            boolean linked = closed && end + 1 < text.length() && "([:".indexOf(text.charAt(end + 1)) >= 0;
            if (RESERVED.matcher(label).matches()) {
                if (!closed || !label.matches("S[1-9][0-9]{0,2}") || linked || !allowed.contains(label)) invalid();
                references.add(label);
            } else if (label.startsWith("^") || (linked && EXTERNAL_LABEL.matcher(label).matches())) invalid();
        }
        return references;
    }

    private static void collect(Node node, StringBuilder prose) {
        if (node instanceof Code || node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
            prose.append(' '); return;
        }
        if (node instanceof Text text) { prose.append(text.getLiteral()); return; }
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak) { prose.append('\n'); return; }
        // Inline tags contribute no visible label characters: [D<strong>OC42</strong>]
        // must be checked as [DOC42]. Raw HTML blocks are outside the answer format.
        if (node instanceof HtmlInline) return;
        if (node instanceof HtmlBlock) { invalid(); return; }
        if (node instanceof LinkReferenceDefinition definition) {
            rejectLinkLabel(definition.getLabel()); return;
        }
        if (node instanceof Link || node instanceof Image) {
            var label = new StringBuilder();
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) collect(child, label);
            rejectLinkLabel(label.toString());
            prose.append(label); return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) collect(child, prose);
        if (node instanceof Block) prose.append('\n');
    }

    private static void rejectLinkLabel(String label) {
        if (RESERVED.matcher(label.strip()).matches() || EXTERNAL_LABEL.matcher(label.strip()).matches()) invalid();
    }

    static String append(String answer, Set<String> missing) {
        if (missing.isEmpty()) return answer;
        String markers = missing.stream().map(ref -> "[" + ref + "]").collect(java.util.stream.Collectors.joining(" "));
        Node last = MARKDOWN.parse(answer).getLastChild();
        while (last != null && last.getLastChild() != null) last = last.getLastChild();
        // A trailing code block, including an unclosed fence, cannot consume rendered citations.
        if (last instanceof FencedCodeBlock || last instanceof IndentedCodeBlock) return markers + "\n\n" + answer;
        return answer + " " + markers;
    }

    private static void invalid() { throw ChatFailure.provider("INVALID_CITATION"); }
}
