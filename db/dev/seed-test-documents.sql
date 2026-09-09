-- 로컬 Swagger 검수 전용 합성 문서. 운영 DB에 실행하지 않는다.
-- Workbench에서 로컬 safecall 스키마를 선택한 뒤 문서가 없는 상태에서 한 번 실행한다.
-- 기존 문서를 덮어쓰지 않는다. 현재 버전과 충돌하면 INSERT 전체가 실패한다.
INSERT INTO `serviceDocument` (`code`,`version`,`title`,`body`,`isConsent`,`isRequired`,`isCurrent`,`publishedAt`) VALUES
('PRIVACY_PROCESSING',1,'[로컬 테스트] 개인정보 처리 동의','기능 검수용 합성 문서이며 실제 개인정보 처리 고지가 아닙니다.',1,1,1,UTC_TIMESTAMP(6)),
('AI_CALL',1,'[로컬 테스트] AI 통화 동의','기능 검수용 합성 문서이며 실제 동의 고지가 아닙니다.',1,1,1,UTC_TIMESTAMP(6)),
('LOCATION_PROCESSING',1,'[로컬 테스트] 위치 처리 동의','기능 검수용 합성 문서이며 실제 동의 고지가 아닙니다.',1,0,1,UTC_TIMESTAMP(6)),
('PRIVACY_NOTICE',1,'[로컬 테스트] 개인정보 안내','기능 검수용 합성 안내입니다.',0,0,1,UTC_TIMESTAMP(6)),
('AI_POLICY',1,'[로컬 테스트] AI 정책','기능 검수용 합성 안내입니다.',0,0,1,UTC_TIMESTAMP(6)),
('HELP',1,'[로컬 테스트] 도움말','기능 검수용 합성 안내입니다.',0,0,1,UTC_TIMESTAMP(6)),
('SOS_GUIDE',1,'[로컬 테스트] SOS 설정 안내','기능 검수용 합성 안내입니다.',0,0,1,UTC_TIMESTAMP(6)),
('PRE_CALL_NOTICE',1,'[로컬 테스트] 통화 전 안내','기능 검수용 합성 안내입니다.',0,0,1,UTC_TIMESTAMP(6));
