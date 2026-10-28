# JobNations

농부 / 광부 / 어부 / 사냥꾼 / 건축가 직업 시스템 (Paper 플러그인)

## jar 만드는 법 (GitHub 자동 빌드)
1. github.com 에서 새 저장소(repository) 생성
2. 이 폴더의 모든 파일을 저장소에 업로드 (`.github` 폴더 포함!)
3. 업로드(push)하면 자동 빌드 시작 → 저장소의 Actions 탭에서 진행 확인 (1~2분)
4. 저장소 오른쪽 Releases 에서 `JobNations.jar` 다운로드
5. 서버의 `plugins` 폴더에 넣고 재시작

## 명령어
- `/직업 목록`, `/직업 정보`, `/직업 농부` (영문 별칭 `/job`)
- 관리자: `/직업 reload`, `/직업 reset <플레이어>`

## 설정
`plugins/JobNations/config.yml` 에서 쿨타임(기본 48시간)과 모든 확률/배율 수정 → `/직업 reload`

## 빌드 오류 시
`pom.xml` 의 `paper.version`, `java.version` 을 서버 버전에 맞게 수정
(Paper 문서: https://docs.papermc.io 에서 paper-api 버전 확인)

## v1.1.0 변경점
- 직업별 레벨/경험치 (Lv.20, 레벨당 메리트 +5%)
- 건축가: 블록 설치 시 돈 수익 (다이아 48개 = 25,000원 시세 기준, 돈.sk 연동 → 패치된 돈.sk 필요)
- 관리자: `/직업 초기화 <닉> [전체]`, `/직업 전체초기화 [전체] 확인`
