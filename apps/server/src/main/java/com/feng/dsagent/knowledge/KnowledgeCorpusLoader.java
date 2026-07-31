package com.feng.dsagent.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class KnowledgeCorpusLoader {

    private static final Pattern LESSON_FILE = Pattern.compile("^(\\d{2})-\\d{2}-.+\\.md$");
    private static final Pattern TITLE = Pattern.compile("(?m)^#\\s*课时标题[：:]\\s*(.+?)\\s*$");
    private static final Pattern PAGE = Pattern.compile("(?m)^-\\s*教材页码[：:]\\s*(.+?)\\s*$");
    private static final Map<String, String> CHAPTER_IDS = Map.ofEntries(
        Map.entry("01", "01-introduction"),
        Map.entry("02", "02-linear-list"),
        Map.entry("03", "03-stack-queue"),
        Map.entry("04", "04-string"),
        Map.entry("05", "05-array-generalized-list"),
        Map.entry("06", "06-tree"),
        Map.entry("07", "07-graph"),
        Map.entry("08", "08-search"),
        Map.entry("09", "09-internal-sort"),
        Map.entry("10", "10-external-sort")
    );

    public KnowledgeCorpus load(Path textbookDirectory, int chunkSize) {
        if (textbookDirectory == null) {
            return KnowledgeCorpus.empty();
        }
        Path lessonsDirectory = textbookDirectory.toAbsolutePath().normalize().resolve("lessons");
        if (!Files.isDirectory(lessonsDirectory)) {
            return KnowledgeCorpus.empty();
        }

        List<Path> lessonFiles;
        try (Stream<Path> files = Files.list(lessonsDirectory)) {
            lessonFiles = files
                .filter(Files::isRegularFile)
                .filter(path -> LESSON_FILE.matcher(path.getFileName().toString()).matches())
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to list textbook lessons", error);
        }

        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (Path lessonFile : lessonFiles) {
            chunks.addAll(loadLesson(lessonFile, chunkSize));
        }
        return new KnowledgeCorpus(
            chunks,
            new KnowledgeCorpusStats(!lessonFiles.isEmpty(), lessonFiles.size(), chunks.size())
        );
    }

    private List<KnowledgeChunk> loadLesson(Path lessonFile, int chunkSize) {
        String markdown;
        try {
            markdown = Files.readString(lessonFile, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read textbook lesson " + lessonFile.getFileName(), error);
        }

        String filename = lessonFile.getFileName().toString();
        String chapterId = chapterId(filename);
        if (chapterId == null) {
            return List.of();
        }
        String title = match(TITLE, markdown, filename.substring(0, filename.length() - 3));
        String pageLabel = match(PAGE, markdown, null);
        String source = "textbook/lessons/" + filename;
        List<String> parts = split(markdown, Math.max(80, chunkSize));

        List<KnowledgeChunk> chunks = new ArrayList<>(parts.size());
        for (int index = 0; index < parts.size(); index++) {
            chunks.add(new KnowledgeChunk(
                chunkId(source, index),
                chapterId,
                title,
                parts.get(index),
                source,
                pageLabel,
                "CLASSROOM_ONLY"
            ));
        }
        return chunks;
    }

    private List<String> split(String markdown, int chunkSize) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : normalized.split("\\n\\s*\\n")) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + 2 + trimmed.length() > chunkSize) {
                result.add(current.toString());
                current.setLength(0);
            }
            if (trimmed.length() > chunkSize) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                splitLongBlock(trimmed, chunkSize, result);
                continue;
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private void splitLongBlock(String block, int chunkSize, List<String> output) {
        int start = 0;
        while (start < block.length()) {
            int end = Math.min(block.length(), start + chunkSize);
            if (end < block.length()) {
                int lineBreak = block.lastIndexOf('\n', end);
                int sentenceBreak = Math.max(block.lastIndexOf('。', end), block.lastIndexOf('；', end));
                int preferred = Math.max(lineBreak, sentenceBreak);
                if (preferred > start + chunkSize / 2) {
                    end = preferred + 1;
                }
            }
            output.add(block.substring(start, end).trim());
            start = end;
        }
    }

    private String chapterId(String filename) {
        Matcher matcher = LESSON_FILE.matcher(filename);
        return matcher.matches() ? CHAPTER_IDS.get(matcher.group(1)) : null;
    }

    private String match(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : fallback;
    }

    private String chunkId(String source, int index) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((source + "#" + index).getBytes(StandardCharsets.UTF_8));
            return "textbook-" + HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
