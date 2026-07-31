package com.feng.dsagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeCorpusLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsLessonMetadataAndSplitsContentWithoutIncludingTheIndex() throws Exception {
        Path lessons = Files.createDirectories(temporaryDirectory.resolve("lessons"));
        Files.writeString(lessons.resolve("00-lesson-index.md"), "# 目录\n不应进入知识库", StandardCharsets.UTF_8);
        Files.writeString(lessons.resolve("02-03-线性表-单链表.md"), """
            # 课时编号：02-03
            # 课时标题：线性表-单链表及基本运算

            ## 1. 来源信息
            - 教材页码：第 35-42 页

            ## 6. 本课时知识点清单
            单链表头插法先让新结点的 next 指向原头结点，再更新头指针。

            ## 7. 容易混淆或易错点
            如果先覆盖头指针，可能失去原链表的入口。操作顺序必须保持可达性。
            """, StandardCharsets.UTF_8);

        KnowledgeCorpus corpus = new KnowledgeCorpusLoader().load(temporaryDirectory, 90);

        assertThat(corpus.stats().lessonFiles()).isEqualTo(1);
        assertThat(corpus.chunks()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(corpus.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.chapterId()).isEqualTo("02-linear-list");
            assertThat(chunk.title()).isEqualTo("线性表-单链表及基本运算");
            assertThat(chunk.source()).isEqualTo("textbook/lessons/02-03-线性表-单链表.md");
            assertThat(chunk.pageLabel()).isEqualTo("第 35-42 页");
        });
        assertThat(corpus.chunks()).anySatisfy(chunk -> assertThat(chunk.content()).contains("头插法"));
    }

    @Test
    void returnsAnEmptyCorpusWhenPrivateMaterialIsUnavailable() {
        KnowledgeCorpus corpus = new KnowledgeCorpusLoader().load(temporaryDirectory.resolve("missing"), 200);

        assertThat(corpus.chunks()).isEmpty();
        assertThat(corpus.stats().lessonFiles()).isZero();
        assertThat(corpus.stats().available()).isFalse();
    }

    @Test
    void treatsAnEmptyLessonsDirectoryAsUnavailableSoPersistedKnowledgeIsPreserved() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("lessons"));

        KnowledgeCorpus corpus = new KnowledgeCorpusLoader().load(temporaryDirectory, 200);

        assertThat(corpus.chunks()).isEmpty();
        assertThat(corpus.stats().available()).isFalse();
    }
}
