import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";

// 전역 테스트 함수를 켜지 않아 Testing Library 가 스스로 정리를 걸지 못한다. 시험마다 그린 화면을 지운다.
afterEach(() => cleanup());
