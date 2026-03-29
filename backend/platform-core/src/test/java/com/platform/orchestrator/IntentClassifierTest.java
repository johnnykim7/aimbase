package com.platform.orchestrator;

import com.platform.orchestrator.IntentClassifier.Complexity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentClassifierTest {

    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new IntentClassifier();
    }

    @Test
    void simpleGreeting() {
        assertThat(classifier.classify("안녕")).isEqualTo(Complexity.SIMPLE);
        assertThat(classifier.classify("hello")).isEqualTo(Complexity.SIMPLE);
        assertThat(classifier.classify("감사합니다")).isEqualTo(Complexity.SIMPLE);
    }

    @Test
    void nullOrBlank_isSimple() {
        assertThat(classifier.classify(null)).isEqualTo(Complexity.SIMPLE);
        assertThat(classifier.classify("")).isEqualTo(Complexity.SIMPLE);
        assertThat(classifier.classify("   ")).isEqualTo(Complexity.SIMPLE);
    }

    @Test
    void moderateQuestion() {
        assertThat(classifier.classify("Spring Boot에서 Redis를 어떻게 설정하나요?"))
                .isEqualTo(Complexity.MODERATE);
    }

    @Test
    void complexWithCodeKeyword() {
        assertThat(classifier.classify("이 코드를 리팩토링 해줘"))
                .isEqualTo(Complexity.COMPLEX);
        assertThat(classifier.classify("알고리즘 시간복잡도를 분석해줘"))
                .isEqualTo(Complexity.COMPLEX);
    }

    @Test
    void complexWithCodeBlock() {
        String msg = "아래 코드를 수정해줘\n```java\npublic class Foo {}\n```";
        assertThat(classifier.classify(msg)).isEqualTo(Complexity.COMPLEX);
    }

    @Test
    void complexWithLongMessage() {
        String longMsg = "a".repeat(250);
        assertThat(classifier.classify(longMsg)).isEqualTo(Complexity.COMPLEX);
    }
}
