# 원본 요구사항: bidding_system 테넌트 API 키 교체(우진비드 AWS)

- 접수: 2026-10-25 15:50 KST
- 요청: 입찰대행 플랫폼(bidding-agency-platform) CR-169 창(입찰대행 CR-177 후속). 결재는 사용자 15:48 "1"
- CR 번호: Aimbase 쪽에서 채번한다
- 대상: AWS 우진 전용 Aimbase(54.116.150.247), 테넌트 bidding_system

## 1. 문제

입찰대행 앱이 Aimbase 를 부를 때 싣는 X-API-Key(앞자리 `plat-20cf`)가 입찰대행 레포 여러 곳(application.yml 기본값, CLAUDE.md, 설계서, 스크립트)에 그대로 적혀 있다. 레포를 볼 수 있는 사람은 누구나 이 키로 우진비드 Aimbase 의 bidding_system 테넌트를 부를 수 있다. git 기록에 남아 있어 레포에서 지워도 노출은 풀리지 않는다. 키를 새로 받고 옛 키를 폐기해야 한다.

입찰대행 쪽 실측(10/25):
- 상용 앱(/opt/wjbid/app/.env)에 AIMBASE_API_KEY 가 없어 application.yml 기본값(위 키)으로 돈다
- 입찰대행 고객 화면과 관리자 화면 소스에는 Aimbase 키가 없다. 키를 쓰는 곳은 서버 앱 하나다
- 테스트 서버(59) 의 입찰대행 매일 밤 점검 스크립트에는 이 키가 없다
- 입찰대행 쪽에서 `GET /api/v1/platform/api-keys?tenantId=bidding_system` 은 403 이다. 키 목록, 발급, 폐기는 Aimbase 관리 권한으로만 된다

Aimbase 가 입찰대행 앱 /api/mcp 를 부를 때 싣는 열쇠(입찰대행 MCP_API_KEY)는 별개이고 이번 대상이 아니다.

## 2. 요구

순서가 중요하다. 옛 키를 먼저 막으면 상용 AI 작업이 모두 멈춘다.

1. AWS Aimbase 에서 bidding_system 테넌트 키를 하나 새로 발급한다. 옛 키는 아직 살려 둔다
2. 새 키 값은 대화창, 메시지, 레포, 로그에 쓰지 않는다. AWS 서버에 ubuntu 만 읽을 수 있는 파일로 둔다: `/home/ubuntu/aimbase-key-bidding_system.new` (chmod 600, 값 한 줄)
3. 입찰대행 CR-169 창에 `[회신]` 으로 새 키 id 와 앞자리만 알린다
4. 입찰대행 쪽이 상용 .env 에 새 키를 넣고 앱을 재기동해 Aimbase 호출(워크플로우 실행, MCP 다시 맺기)이 되는지 확인한 뒤 `[회신]` 으로 알린다
5. 그 회신을 받으면 옛 키(앞자리 `plat-20cf`)를 폐기한다. 폐기 전에 옛 키의 마지막 사용 시각을 보고, 입찰대행 재기동 뒤에도 쓰이고 있으면 폐기하지 않고 알린다
6. 옛 키를 폐기한 뒤 옛 키로 부르면 401 또는 403 인지 확인한다
7. 옛 서버(59.8.160.12) Aimbase 에도 같은 키가 있는지 보고, 있으면 따로 승인요청

## 3. 완료 확인

- 새 키로 `GET /api/v1/workflows/proposal-design` 이 200
- 옛 키로 같은 호출이 401 또는 403
- 입찰대행 상용 앱 로그에 Aimbase 인증 실패가 없다(입찰대행 쪽이 본다)
- `/home/ubuntu/aimbase-key-bidding_system.new` 는 입찰대행 쪽이 .env 로 옮긴 뒤 사용자에게 삭제를 맡긴다
