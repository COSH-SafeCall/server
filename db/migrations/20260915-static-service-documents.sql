-- 서비스/안내 문서를 프론트엔드 정적 콘텐츠로 전환한다.
-- 앱 배포 전에 한 번 적용한다. 동의 사건은 고정 버전 1로 유지한다.
USE `safecall`;

ALTER TABLE `oauthConsent`
	DROP FOREIGN KEY `fkOauthConsentDocument`,
	ADD CONSTRAINT `ckOauthConsentVersion` CHECK (`documentVersion`=1);

ALTER TABLE `consentEvent`
	DROP FOREIGN KEY `fkConsentEvent2`,
	ADD CONSTRAINT `ckConsentDocumentVersion` CHECK (`documentVersion`=1);

DROP TABLE `serviceDocument`;
