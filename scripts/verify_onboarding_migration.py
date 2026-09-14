"""Synthetic migration checks called only inside verify_auth.py's disposable MySQL."""
import subprocess


def check_migration(mysql, connection, database, server, flags):
    assert database.startswith('safecall_auth_test_')

    def query(sql):
        result = subprocess.run([mysql, *connection, '--default-character-set=utf8mb4', '-N', '-B', database],
                                input=("SET SESSION time_zone='+00:00';\n"+sql).encode(), capture_output=True,
                                creationflags=flags)
        assert result.returncode == 0, result.stderr.decode(errors='replace')
        return result.stdout.decode().strip()

    query("""
INSERT INTO appUser (id,kakaoSubjectHash,kakaoSubjectCipher,keyRef,status,nameCipher,phoneCipher,phoneHash)
 VALUES (UNHEX(REPEAT('01',16)),UNHEX(REPEAT('01',32)),X'01','synthetic-key','ONBOARDING',X'1234',X'5678',UNHEX(REPEAT('02',32))),
 (UNHEX(REPEAT('02',16)),UNHEX(REPEAT('03',32)),X'02','synthetic-delete-key','DELETION_PENDING',NULL,NULL,NULL);
INSERT INTO webSession (id,sessionHash,csrfHash,userId,kind,onboardingStep,expiresAt)
 VALUES (UNHEX(REPEAT('10',16)),UNHEX(REPEAT('10',32)),UNHEX(REPEAT('11',32)),UNHEX(REPEAT('01',16)),'KAKAO','CONTACTS',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR));
INSERT INTO consentEvent (id,userId,documentCode,documentVersion,action)
 VALUES (UNHEX(REPEAT('20',16)),UNHEX(REPEAT('01',16)),'PRIVACY_PROCESSING',1,'GRANTED');
INSERT INTO emergencyContact (id,userId,slot,nameCipher,relationshipCipher,phoneCipher,phoneHash)
 VALUES (UNHEX(REPEAT('30',16)),UNHEX(REPEAT('01',16)),1,X'01',X'02',X'03',UNHEX(REPEAT('30',32)));
INSERT INTO oauthAttempt (id,sessionId,stateHash,redirectUri,expiresAt)
 VALUES (UNHEX(REPEAT('40',16)),UNHEX(REPEAT('10',16)),UNHEX(REPEAT('40',32)),'https://synthetic.test/callback',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR));
INSERT INTO oauthConsent (attemptId,documentCode,documentVersion,action)
 VALUES (UNHEX(REPEAT('40',16)),'AI_CALL',1,'GRANTED');
INSERT INTO deletionJob (id,userId,scope,accountSubjectHash,receiptHash,cleanupCipher,cleanupKeyRef,cutoffAt,dueAt,receiptExpiresAt)
 VALUES (UNHEX(REPEAT('50',16)),UNHEX(REPEAT('02',16)),'ACCOUNT',UNHEX(REPEAT('03',32)),UNHEX(REPEAT('50',32)),X'1234','synthetic-rollback-key',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 DAY));
""")
    def snapshot(table):
        return query('SELECT * FROM `' + table + '` ORDER BY id')
    saved = {name: snapshot(name) for name in ('consentEvent', 'emergencyContact', 'deletionJob')}
    profile = query("SELECT HEX(nameCipher),HEX(phoneCipher),HEX(phoneHash),keyRef FROM appUser WHERE id=UNHEX(REPEAT('01',16))")
    query((server / 'db/migrations/20260915-client-onboarding.sql').read_text(encoding='utf-8').replace('`safecall`', '`' + database + '`'))
    for name, value in saved.items():
        assert snapshot(name) == value, name
    assert query("SELECT HEX(nameCipher),HEX(phoneCipher),HEX(phoneHash),keyRef FROM appUser WHERE id=UNHEX(REPEAT('01',16))") == profile
    assert query("SELECT status,version,profileConfirmedAt IS NULL FROM appUser WHERE id=UNHEX(REPEAT('01',16))") == 'ACTIVE\t2\t1'
    assert query("SELECT status FROM appUser WHERE id=UNHEX(REPEAT('02',16))") == 'DELETION_PENDING'
    assert query('SELECT status,completedAt IS NOT NULL FROM oauthAttempt') == 'EXPIRED\t1'
    assert query('SELECT COUNT(*) FROM webSession') == '1'
    assert query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='oauthConsent'") == '0'
    assert query("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='webSession' AND column_name IN ('onboardingStep','version')") == '0'
    assert query("SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='appUser' AND column_name='status'") == 'ACTIVE'
    assert query("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE()") == '155'
    print('Onboarding migration passed: profile/contact/consent preserved; account states mapped; sessions preserved; pending OAuth expired; obsolete table/columns absent.', flush=True)
