#!/usr/bin/env python3
"""AMT-05D durable stale receipt capacity recovery + bounded user-scoped history.
Source-only; no Android/Gradle/network calls. Requires previous AMT05C gates intact.
"""
from pathlib import Path
import argparse
ROOT=Path(__file__).resolve().parents[2]
FILES={'journal':Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerApkBackupJournal.java'),
       'wire':Path('app/src/main/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerPackageQueryWire.java'),
       'unit':Path('app/src/test/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerApkBackupJournalTest.java'),
       'device':Path('app/src/androidTest/java/io/github/muntashirakon/AppManager/automation/tasker/TaskerJournalInstrumentedTest.java'),
       'workflow':Path('.github/workflows/devtool-tasker-ci.yml'),
       'runner':Path('scripts/devtool/amt03_hosted_instrumentation.sh')}
GUARDS={
'journal':['HISTORY_LIMIT = 12','expireStalePending(records, recoveryNow);',
           'record.put("status", "unknown")','record.put("error", "backup_completion_timeout")',
           'if (pending(status) && now >= updated && now - updated > STALE_MS)','!pending(item.getString("status"))',
           'if (oldest < 0) throw new IOException(',
           'history.length() < HISTORY_LIMIT','if (!pkg.equals(rec.getString("package")) || user != rec.getInt("user"))\n                        continue;',
           '"%am_backup_request_pending_count"','"%am_backup_request_history_json"',
           'out.putString("%am_backup_request_history_json", "[]")',
           'latest == null || rec.getLong("created_ms") > latest.getLong("created_ms")',
           'observedError = "backup_completion_timeout";',
           'Boolean.toString("succeeded".equals(status))',
           'if (!pending(current)) return;',
           'if (pending(status) && now >= timestamp && now - timestamp > STALE_MS)'],
'wire':['%am_backup_request_pending_count', 'Non-expired queued or running APK requests for this package',
        '%am_backup_request_history_json', 'Latest 12 current-user APK backup receipts (no file paths)'],
'unit':['expiredPendingReceiptsReclaimCapacityButNeverClaimSuccess',
        'boundedHistoryDoesNotLeakOtherPackagesOrUsers',
        'clockRollbackCannotPrematurelyExpireRunningRequest',
        'equalTimestampKeepsMostRecentInsertedRequest'],
'device':['testApkReceiptRecoveryOfStalePendingOnDevice',
          'testApkReceiptHistoryNeverLeaksOtherUserOnDevice'],
'workflow':['python3 scripts/devtool/check_amt05d_contract.py --check',
            'python3 scripts/devtool/check_amt05c_contract.py --check'],
'runner':["assert int(r.get('passed', 0)) >= 19, ('AMT05D_RECEIPT_RECOVERY_DEVICE_COVERAGE_INCOMPLETE', r)",
          "assert int(r.get('passed', 0)) >= 17, ('AMT05C_RECEIPT_DEVICE_COVERAGE_INCOMPLETE', r)"]}
def inspect(root=ROOT):
 errors=[];source={}
 for key,p in FILES.items():
  f=root/p
  source[key]=f.read_text(encoding='utf-8') if f.is_file() else ''
  if not f.is_file():errors.append('missing '+str(p))
 for key,markers in GUARDS.items():
  for marker in markers:
   if marker not in source[key]:errors.append(key+' missing '+marker)
 j=source['journal']
 if j.count('expireStalePending(records, recoveryNow);')!=1:errors.append('one stale-recovery entry point')
 if j.count('record.put("status", "unknown")')!=1:errors.append('only unknown for timeouts')
 if 'expireStalePending(records, recoveryNow);' in j and 'if (records.length() >= LIMIT)' in j:
  if j.index('expireStalePending(records, recoveryNow);')>j.index('if (records.length() >= LIMIT)'):
   errors.append('expiry must precede capacity eviction')
 if 'expireStalePending(records, recoveryNow);' in j and 'write(records);' in j and 'void enqueue(' in j:
  if j.index('expireStalePending(records, recoveryNow);')>j.index('write(records);',j.index('void enqueue(')):
   errors.append('stale state must persist atomically with enqueue')
 if '.restore(' in j or 'Runtime.getRuntime(' in j or 'ProcessBuilder(' in j:
  errors.append('background restore/shell forbidden')
 if j.count('if (!pending(current)) return;')!=1:errors.append('terminal receipt may be rewritten')
 return {'status':'FAIL' if errors else 'PASS','errors':errors}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');p.parse_args()
 v=inspect();print('AMT05D_RECEIPT_RECOVERY_SOURCE_'+v['status'])
 for err in v['errors']:print('FAIL '+err)
 raise SystemExit(0 if v['status']=='PASS' else 1)
