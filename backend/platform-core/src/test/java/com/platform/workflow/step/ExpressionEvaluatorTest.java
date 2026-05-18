package com.platform.workflow.step;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-084 P1 — {@link ExpressionEvaluator} 단위 테스트.
 *
 * <p>이 테스트는 추출(ConditionStepExecutor → ExpressionEvaluator)이 평가 동작을
 * 바꾸지 않았음을 고정하는 회귀 안전망이다. 추출 전 ConditionStepExecutor.evaluate
 * 의 모든 분기(contains / equals / 숫자비교 / 불리언 / 빈문자열 / 엣지)를 커버한다.
 */
@DisplayName("ExpressionEvaluator — CONDITION 평가 로직 추출 회귀 안전망")
class ExpressionEvaluatorTest {

    private ExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ExpressionEvaluator();
    }

    @Nested
    @DisplayName("null / 공백")
    class NullOrBlank {
        @Test
        @DisplayName("null 은 true")
        void nullIsTrue() {
            assertThat(evaluator.evaluate(null)).isTrue();
        }

        @Test
        @DisplayName("빈 문자열은 true")
        void blankIsTrue() {
            assertThat(evaluator.evaluate("")).isTrue();
            assertThat(evaluator.evaluate("   ")).isTrue();
        }
    }

    @Nested
    @DisplayName("contains (대소문자 무시)")
    class Contains {
        @Test
        void matchCaseInsensitive() {
            assertThat(evaluator.evaluate("작업 완료되었습니다 contains '완료'")).isTrue();
            assertThat(evaluator.evaluate("HELLO WORLD contains 'world'")).isTrue();
        }

        @Test
        void noMatch() {
            assertThat(evaluator.evaluate("진행중 contains '완료'")).isFalse();
        }

        @Test
        @DisplayName("쌍따옴표 right 도 지원")
        void doubleQuoted() {
            assertThat(evaluator.evaluate("foobar contains \"bar\"")).isTrue();
        }
    }

    @Nested
    @DisplayName("equals (대소문자 무시)")
    class Equals {
        @Test
        void equalIgnoreCase() {
            assertThat(evaluator.evaluate("APPROVED equals 'approved'")).isTrue();
        }

        @Test
        void notEqual() {
            assertThat(evaluator.evaluate("rejected equals 'approved'")).isFalse();
        }
    }

    @Nested
    @DisplayName("숫자 비교")
    class Numeric {
        @Test
        void gte() {
            assertThat(evaluator.evaluate("5 >= 5")).isTrue();
            assertThat(evaluator.evaluate("4 >= 5")).isFalse();
        }

        @Test
        void lte() {
            assertThat(evaluator.evaluate("3 <= 5")).isTrue();
            assertThat(evaluator.evaluate("6 <= 5")).isFalse();
        }

        @Test
        void gt() {
            assertThat(evaluator.evaluate("9 > 2")).isTrue();
            assertThat(evaluator.evaluate("2 > 9")).isFalse();
        }

        @Test
        void lt() {
            assertThat(evaluator.evaluate("1 < 8")).isTrue();
            assertThat(evaluator.evaluate("8 < 1")).isFalse();
        }

        @Test
        @DisplayName("소수점 비교")
        void decimal() {
            assertThat(evaluator.evaluate("0.95 >= 0.9")).isTrue();
        }

        @Test
        @DisplayName("파싱 불가 숫자 비교는 false (기존 동작 보존)")
        void unparsable() {
            assertThat(evaluator.evaluate("abc > 5")).isFalse();
        }
    }

    @Nested
    @DisplayName("불리언 리터럴 / 폴백")
    class BooleanAndFallback {
        @Test
        void booleanLiterals() {
            assertThat(evaluator.evaluate("true")).isTrue();
            assertThat(evaluator.evaluate("TRUE")).isTrue();
            assertThat(evaluator.evaluate("false")).isFalse();
            assertThat(evaluator.evaluate("FALSE")).isFalse();
        }

        @Test
        @DisplayName("그 외 비어있지 않은 문자열은 true (기존 폴백 동작)")
        void nonEmptyFallbackTrue() {
            assertThat(evaluator.evaluate("some arbitrary value")).isTrue();
        }
    }

    @Nested
    @DisplayName("extractQuoted")
    class ExtractQuoted {
        @Test
        void singleQuote() {
            assertThat(evaluator.extractQuoted("'hello'")).isEqualTo("hello");
        }

        @Test
        void doubleQuote() {
            assertThat(evaluator.extractQuoted("\"world\"")).isEqualTo("world");
        }

        @Test
        void unquotedReturnedAsIs() {
            assertThat(evaluator.extractQuoted("plain")).isEqualTo("plain");
        }

        @Test
        void nullReturnsEmpty() {
            assertThat(evaluator.extractQuoted(null)).isEqualTo("");
        }
    }
}
