package com.example.tailorresume;

import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocxTemplateService {

    private static final Pattern EDITED_PARAGRAPH = Pattern.compile("\\[\\[\\s*P(\\d+)\\s*]]\\s*");
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("(?m)^\\s*```[^\\r\\n]*\\s*$");
    private static final Pattern SENTENCE_PUNCTUATION = Pattern.compile("[.!?]");
    private static final int MIN_NARRATIVE_LENGTH = 180;

    public String extractText(byte[] template) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(template))) {
            return textOf(paragraphs(document));
        }
    }

    public String createTaggedPromptText(byte[] template) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(template))) {
            List<XWPFParagraph> allParagraphs = paragraphs(document).stream()
                    .filter(paragraph -> !paragraph.getText().isBlank())
                    .toList();
            List<XWPFParagraph> editable = allParagraphs.stream()
                    .filter(this::isEditableNarrative)
                    .toList();
            StringBuilder taggedText = new StringBuilder();
            taggedText.append("READ-ONLY RESUME CONTEXT (do not edit or return):\n");
            for (XWPFParagraph paragraph : allParagraphs) {
                if (!isEditableNarrative(paragraph)) {
                    taggedText.append(paragraph.getText().strip()).append('\n');
                }
            }
            taggedText.append("\nEDITABLE NARRATIVE PARAGRAPHS (only edit these):\n");
            for (int index = 0; index < editable.size(); index++) {
                taggedText.append(tag(index, editable.get(index).getText())).append('\n');
            }
            return taggedText.toString().stripTrailing();
        }
    }

    public String createTaggedPlainText(String text) {
        StringBuilder taggedText = new StringBuilder();
        int index = 0;
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                taggedText.append(tag(index++, line.strip())).append('\n');
            }
        }
        return taggedText.toString().stripTrailing();
    }

    public byte[] applyEdits(byte[] template, String tailoredText) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(template))) {
            List<XWPFParagraph> paragraphs = paragraphs(document);
            List<XWPFParagraph> editable = paragraphs.stream()
                    .filter(this::isEditableNarrative)
                    .toList();
            Map<Integer, String> edits = parseEdits(tailoredText, editable.size());
            for (int i = 0; i < editable.size(); i++) {
                String replacement = edits.get(i);
                if (replacement != null && !replacement.isBlank()) {
                    replaceParagraphText(editable.get(i), replacement);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }

    public String applyPlainTextEdits(String sourceText, String tailoredText) {
        List<String> lines = new ArrayList<>(Arrays.asList(sourceText.split("\\R", -1)));
        List<Integer> paragraphLines = new ArrayList<>();
        for (int line = 0; line < lines.size(); line++) {
            if (!lines.get(line).isBlank()) {
                paragraphLines.add(line);
            }
        }
        Map<Integer, String> edits = parseEdits(tailoredText, paragraphLines.size());
        for (int paragraph = 0; paragraph < paragraphLines.size(); paragraph++) {
            String replacement = edits.get(paragraph);
            if (replacement != null && !replacement.isBlank()) {
                lines.set(paragraphLines.get(paragraph), replacement);
            }
        }
        return String.join("\n", lines);
    }

    private Map<Integer, String> parseEdits(String tailoredText, int expectedParagraphs) {
        Map<Integer, String> edits = new TreeMap<>();
        Matcher matcher = EDITED_PARAGRAPH.matcher(tailoredText);
        int previousEnd = 0;
        int currentIndex = -1;
        StringBuilder currentParagraph = new StringBuilder();
        while (matcher.find()) {
            if (currentIndex >= 0) {
                currentParagraph.append(tailoredText, previousEnd, matcher.start());
                edits.put(currentIndex, cleanParagraph(currentParagraph.toString()));
            } else {
                String prefix = tailoredText.substring(previousEnd, matcher.start()).strip();
                prefix = MARKDOWN_FENCE.matcher(prefix).replaceAll("").strip();
                if (!prefix.isBlank()) {
                    throw new IllegalArgumentException("The AI response contained text outside the original resume paragraphs. Tailor it again before exporting.");
                }
            }
            currentIndex = Integer.parseInt(matcher.group(1));
            if (currentIndex >= expectedParagraphs || edits.containsKey(currentIndex)) {
                throw new IllegalArgumentException("The AI response referenced an invalid or repeated resume paragraph. Tailor it again before exporting.");
            }
            currentParagraph = new StringBuilder(matcher.group().substring(matcher.group().indexOf("]]") + 2));
            previousEnd = matcher.end();
        }
        if (currentIndex < 0) {
            throw new IllegalArgumentException("The AI returned no paragraph-linked resume changes. Tailor it again before exporting.");
        }
        currentParagraph.append(tailoredText.substring(previousEnd));
        edits.put(currentIndex, cleanParagraph(currentParagraph.toString()));
        if (edits.values().stream().allMatch(String::isBlank)) {
            throw new IllegalArgumentException("The AI returned no resume content. Tailor it again before exporting.");
        }
        return edits;
    }

    private String cleanParagraph(String text) {
        return MARKDOWN_FENCE.matcher(text).replaceAll("").replaceAll("\\s*\\R\\s*", " ").strip();
    }

    private void replaceParagraphText(XWPFParagraph paragraph, String replacement) {
        if (paragraph.getRuns().isEmpty()) {
            paragraph.createRun().setText(replacement);
            return;
        }
        List<XWPFRun> textRuns = paragraph.getRuns().stream()
                .filter(run -> run.getText(0) != null)
                .toList();
        if (textRuns.isEmpty()) {
            paragraph.createRun().setText(replacement);
            return;
        }
        int originalLength = textRuns.stream().mapToInt(run -> run.getText(0).length()).sum();
        if (originalLength == 0) {
            textRuns.get(0).setText(replacement, 0);
            return;
        }

        int replacementStart = 0;
        int allocatedOriginalLength = 0;
        for (int i = 0; i < textRuns.size(); i++) {
            XWPFRun run = textRuns.get(i);
            allocatedOriginalLength += run.getText(0).length();
            int replacementEnd;
            if (i == textRuns.size() - 1) {
                replacementEnd = replacement.length();
            } else {
                int targetEnd = Math.max(replacementStart, Math.min(
                        replacement.length(),
                        (int) Math.round((double) allocatedOriginalLength * replacement.length() / originalLength)));
                replacementEnd = targetEnd;
            }
            if (i < textRuns.size() - 1 && replacementEnd > replacementStart
                    && replacementEnd < replacement.length()) {
                int previousSpace = replacement.lastIndexOf(' ', replacementEnd);
                int nextSpace = replacement.indexOf(' ', replacementEnd);
                int previousBoundary = previousSpace + 1;
                int nextBoundary = nextSpace + 1;
                boolean previousIsClose = previousBoundary >= replacementStart
                        && replacementEnd - previousSpace <= 12;
                boolean nextIsClose = nextSpace >= replacementStart
                        && nextSpace >= 0
                        && nextBoundary - replacementEnd <= 12;
                if (previousIsClose && (!nextIsClose
                        || replacementEnd - previousSpace <= nextSpace - replacementEnd)) {
                    replacementEnd = previousBoundary;
                } else if (nextIsClose) {
                    replacementEnd = nextBoundary;
                }
            }
            replacementEnd = Math.max(replacementStart, Math.min(replacementEnd, replacement.length()));
            run.setText(replacement.substring(replacementStart, replacementEnd), 0);
            replacementStart = replacementEnd;
        }
    }

    private String tag(int index, String text) {
        return "[[P" + String.format(Locale.ROOT, "%04d", index) + "]] " + text;
    }

    private boolean isEditableNarrative(XWPFParagraph paragraph) {
        if (paragraph.getNumID() != null) {
            return true;
        }
        String text = paragraph.getText().strip();
        return text.length() >= MIN_NARRATIVE_LENGTH && SENTENCE_PUNCTUATION.matcher(text).find();
    }

    private String textOf(List<XWPFParagraph> paragraphs) {
        return paragraphs.stream()
                .map(XWPFParagraph::getText)
                .filter(text -> !text.isBlank())
                .reduce((first, second) -> first + "\n" + second)
                .orElse("");
    }

    private List<XWPFParagraph> paragraphs(IBody body) {
        List<XWPFParagraph> paragraphs = new ArrayList<>();
        for (IBodyElement element : body.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                paragraphs.add(paragraph);
            } else if (element instanceof XWPFTable table) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        paragraphs.addAll(paragraphs(cell));
                    }
                }
            }
        }
        return paragraphs;
    }
}
