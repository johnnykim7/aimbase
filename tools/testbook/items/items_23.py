# 영역 23 검사 반복 코드 대조 시험항목. 근거는 CR-193 문서 3절, 4절 결정, 5절 규약과 T3-7 16절, BIZ-195 다.
# 시험은 로컬이나 별도 워크플로우에서 한다. 59 의 lexflow-draft 운영 워크플로우는 쓰지 않는다.

LOCAL = ('로컬 Aimbase api(bootRun)와 실행 컨테이너(skill-sandbox), 시험 테넌트에 LLM 연결 하나. '
         '시험 스킬 묶음 cr193-check 에 scripts/check_laws.py(「○○법 제N조」를 뽑아 input.case 와 input.guide 글자에 없으면 위반), '
         'scripts/fail.py(stderr 에 한 줄 쓰고 종료 코드 1), scripts/slow.py(10초 잠듦)를 올린 상태. '
         '시험 워크플로우 cr193-test 는 guide(입력을 그대로 내는 단계) 다음 final(EVALUATOR_LOOP, max_iterations 2, '
         'pass_criteria JSONPATH_MATCH $.passed true, code_check skill cr193-check)')

ITEMS = [

dict(id='IT-23-001', flow='코드 대조 위반', kind='정상', title='자료 밖 조문을 쓴 첫 초안은 코드 대조가 잡아 통과하지 못하고, 지적을 받은 두 번째 초안이 통과한다',
     pre=LOCAL + '. 작성 지시문은 첫 회차에 「민법 제598조」를 넣고, 지적이 있으면 지적대로 고치게 한다. 사건자료에는 민법 제750조만 있다',
     steps='1. cr193-test 를 사건자료와 함께 실행하고 끝날 때까지 기다린다\n'
           '2. run 의 stepResults.final.iterations[0] 을 본다\n'
           '3. iterations[1] 과 final_iteration, loop_exhausted 를 본다',
     expect='1. run 이 completed 다\n'
            '2. code_check.passed 가 false, feedback 에 「민법 제598조」가 있고, 검사 결과(evaluator_verdict)도 있다(코드가 위반을 찾아도 LLM 검사가 돌았다)\n'
            '3. 두 번째 초안에 「민법 제598조」가 없고 code_check.passed 가 true 다. final_iteration 1, loop_exhausted false',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-377', 'BIZ-195'], src=['CR-193'], pri='High'),

dict(id='IT-23-002', flow='코드 대조 위반 없음', kind='정상', title='자료 안 조문만 쓴 초안은 첫 회차에 통과한다',
     pre=LOCAL + '. 작성 지시문은 사건자료에 있는 민법 제750조만 쓰게 한다',
     steps='1. cr193-test 를 실행하고 끝날 때까지 기다린다\n'
           '2. stepResults.final 의 iterations 와 final_iteration 을 본다',
     expect='1. run 이 completed 다\n'
            '2. 회차 기록이 1개이고 code_check.passed 가 true, issues 가 빈 배열이다. final_iteration 0, loop_exhausted false',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['PRD-377', 'BIZ-195'], src=['CR-193'], pri='High'),

dict(id='IT-23-003', flow='코드 대조 실패', kind='예외', title='대조 스크립트가 실패하면 그 회차는 통과가 아니고 단계는 끝까지 돌아 마지막 초안을 낸다',
     pre=LOCAL + '. final 의 code_check.script 를 scripts/fail.py 로 바꾼 별도 워크플로우',
     steps='1. 워크플로우를 실행하고 끝날 때까지 기다린다\n'
           '2. stepResults.final.iterations 두 회차의 code_check 를 본다\n'
           '3. final 의 structured_data, loop_exhausted 와 두 번째 작성 요청에 넘어간 지적을 본다',
     expect='1. run 이 completed 다(단계 실패가 아니다)\n'
            '2. 두 회차 모두 code_check.passed 가 null, error 가 「exit code 1」, stderr 에 스크립트가 쓴 줄이 있다\n'
            '3. 마지막 초안이 structured_data 에 있고 loop_exhausted 가 true 다. 두 번째 작성에 넘어간 지적은 검사 지적뿐이다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['BIZ-195'], src=['CR-193'], pri='High'),

dict(id='IT-23-004', flow='코드 대조 실패', kind='예외', title='대조 스크립트가 시간 한도를 넘으면 그 회차는 통과가 아니고 단계는 계속된다',
     pre=LOCAL + '. final 의 code_check.script 를 scripts/slow.py, timeout_seconds 를 2 로 바꾼 별도 워크플로우',
     steps='1. 워크플로우를 실행하고 끝날 때까지 기다린다\n'
           '2. stepResults.final.iterations[0].code_check 를 본다',
     expect='1. run 이 completed 다\n'
            '2. timed_out 이 true, error 가 「timed out after 2s」, passed 가 null 이다. duration_ms 가 10초보다 작다',
     judge=['서버', '서버'],
     pair={1: [1], 2: [2]},
     req=['BIZ-195'], src=['CR-193'], pri='High'),

dict(id='IT-23-005', flow='코드 대조 화면', kind='정상', title='실행 상세의 반복 이력에서 회차마다 코드 대조 결과를 본다',
     pre='IT-23-001, IT-23-003 을 돌린 로컬 화면, 테넌트 관리자 로그인',
     steps='1. 워크플로우 실행 상세에서 IT-23-001 run 의 final 단계를 열고 반복 이력을 본다\n'
           '2. 첫 회차를 펼친다\n'
           '3. IT-23-003 run 의 반복 이력에서 첫 회차를 펼친다',
     expect='1. 첫 회차 머리에 「코드 대조 위반」, 두 번째 회차에 「코드 대조 통과」가 보인다\n'
            '2. 「코드 대조」 묶음에 지적(민법 제598조)과 issues 가 보인다\n'
            '3. 머리에 「코드 대조 오류」, 펼친 묶음에 오류 문구와 stderr 가 보인다',
     judge=['화면', '화면', '화면'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-377'], src=['CR-193'], pri='Medium'),

dict(id='IT-23-006', flow='코드 대조 화면', kind='정상', title='편집 화면에서 검사 반복을 열었다 저장해도 코드 대조 설정이 객체로 남는다',
     pre='로컬 화면, 테넌트 관리자 로그인, code_check 가 있는 cr193-test',
     steps='1. 워크플로우 편집에서 final 단계 속성을 연다\n'
           '2. 아무것도 바꾸지 않고 저장한 뒤 워크플로우 상세 조회(GET /api/v1/workflows/{id})의 final config 를 본다\n'
           '3. 「코드 대조」 칸을 비우고 저장한 뒤 다시 조회한다',
     expect='1. 「코드 대조 (JSON, 선택)」 칸에 설정이 JSON 으로 보인다\n'
            '2. 저장이 성공하고 code_check 가 객체(글이 아님)로 그대로 있다\n'
            '3. 저장이 성공하고 config 에 code_check 칸이 없다',
     judge=['화면', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['PRD-377'], src=['CR-193'], pri='Medium'),

dict(id='IT-23-007', flow='코드 대조 설정 오류', kind='예외', title='없는 스킬이나 잘못된 설정은 저장이나 단계 시작에서 막히고 LLM 을 부르지 않는다',
     pre=LOCAL,
     steps='1. code_check.skill 을 빈 글로 둔 워크플로우를 저장한다\n'
           '2. code_check.skill 을 없는 이름 no-such-skill 로 둔 워크플로우를 저장하고 실행한다\n'
           '3. 2 의 run 단계 기록과 LLM 호출 기록을 본다',
     expect='1. 400, 문구에 code_check.skill 이 있다\n'
            '2. 저장은 성공하고 run 이 failed 다. 단계 오류에 스킬 이름이 있다\n'
            '3. final 단계의 작성, 검사 LLM 호출이 없다',
     judge=['서버', '서버', '서버'],
     pair={1: [1], 2: [2], 3: [3]},
     req=['BIZ-195'], src=['CR-193'], pri='Medium'),

]
