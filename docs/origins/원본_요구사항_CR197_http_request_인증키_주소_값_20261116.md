# 원본 요구사항: CR-197 http_request 연결 인증키 주소 값 싣기

- 받은 날: 2026-11-16
- 경로: 컨타 lexflow-88782(lexflow-b5f465c9) 의 브리핑 주입
- 아래는 브리핑 본문을 그대로 옮긴 것이다. 워커 진행 규약 부분은 뺐다.

---

[지시] http_request 가 HTTP 연결의 인증키를 주소 값(query)으로 싣게 하는 Aimbase CR (채번은 이 창에서)

배경
- lexflow CR-004(서면 초안 근거 조사)가 법제처 국가법령정보 공동활용 Open API 를 부른다. 이 API 는 인증키를 요청 주소의 값 OC 로만 받는다(헤더 불가).
- 사용자 승인: 2026-11-16 06:07 (lexflow 설계 T3-2 8장, 실행맵 lexflow docs/기록/실행맵_법제처_근거조사.md).

지금 문제(소스 확인)
- HttpRequestTool.injectAuth 에서 auth.type=API_KEY, in=query 이면 경고 로그만 남기고 값을 싣지 않는다(HttpRequestTool.java:328-331).
- 경고가 안내하는 Connection.config.defaultQuery 는 구현이 없다(buildUri 는 입력 query 만 쓴다, 271행).

요구
1. auth.type=API_KEY, in=query 이면 name=value(value_env 우선, 없으면 value)를 요청 주소에 싣는다. 값은 URL 인코딩한다.
2. 입력 query 에 같은 이름이 오면 연결 값이 이긴다(워크플로우가 인증키를 덮어쓰지 못한다).
3. 감사 기록, 로그, 도구 결과에 남는 요청 주소와 query 에서 그 값을 가린다. 연결 조회 API 응답의 auth.value 가 가려지는지도 확인하고, 안 가려지면 함께 고친다.
4. in=header 와 BEARER, BASIC 동작은 그대로다.
5. 단위시험: query 싣기, 같은 이름 덮어쓰기 막기, 가림, 기존 header 방식 회귀.

반영
- 로컬 확인 뒤 59 반영은 사용자 승인이 필요하다. [승인요청] 으로 올린다.
- 59 의 lexflow 테넌트 연결 등록과 실제 법제처 호출 확인은 lexflow 창(컨타)이 한다. 인증키 값은 이 창에 전하지 않는다.
- 완료 보고에 `시험항목:` 줄을 넣는다.
