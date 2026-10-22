"""Binary-safe collection from the authorized disposable debug emulator only."""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,sys
parser=argparse.ArgumentParser()
parser.add_argument('--label',required=True)
parser.add_argument('--failed',action='store_true')
args=parser.parse_args()
assert re.fullmatch(r'phase4[ij]-[a-z0-9-]+',args.label)
root=Path(__file__).resolve().parent
adb=Path('C:/Users/mohammed saud/AppData/Local/Android/Sdk/platform-tools/adb.exe')
command=[str(adb),'-s','emulator-5598'];package='com.hardwareshop.pos.debug'
def text(parts):return subprocess.run(command+parts,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15).stdout.decode().strip()
assert text(['shell','getprop','ro.kernel.qemu'])=='1','Disposable emulator only'
out=root/'evidence/final-engineering'/(args.label+'-collected')
out.mkdir(parents=True,exist_ok=False)
records=[];errors=[]
def binary(parts,target):
    with target.open('xb') as stream:
        result=subprocess.run(command+parts,stdout=stream,stderr=subprocess.PIPE,timeout=30)
    if result.returncode:raise RuntimeError(result.stderr.decode(errors='replace'))
    return hashlib.sha256(target.read_bytes()).hexdigest()
try:
    parent='/sdcard/Android/data/'+package+'/files'
    directories=text(['shell','run-as',package,'find',parent,'-maxdepth','1','-type','d','-name','phase4j-failure-*']).splitlines()
    for directory in directories:
        if not re.fullmatch(re.escape(parent)+r'/phase4j-failure-\d+',directory):continue
        local=out/Path(directory).name;local.mkdir()
        for guest in text(['shell','run-as',package,'find',directory,'-maxdepth','1','-type','f']).splitlines():
            name=Path(guest).name
            if not re.fullmatch(r'[a-z0-9.-]+',name):raise ValueError('Unexpected evidence filename')
            try:
                digest=binary(['exec-out','run-as',package,'cat',guest],local/name)
                original=text(['shell','run-as',package,'sha256sum',guest]).split()[0]
                if digest!=original:raise ValueError('Transfer hash mismatch')
                records.append({'path':str((local/name).relative_to(out)),'bytes':(local/name).stat().st_size,'sha256':digest,'guestSha256':original,'capturedBeforeTeardown':True})
            except Exception as error:errors.append({'path':guest,'error':str(error)})
    if args.failed:
        # Stop writes before copying DB/WAL/SHM and pending-operation journals.
        text(['shell','am','force-stop',package])
        digest=binary(['exec-out','run-as',package,'tar','-cf','-','databases','no_backup'],out/'failed-process-image.tar')
        records.append({'path':'failed-process-image.tar','sha256':digest,'capturedBeforeTeardown':False,'processStopped':True})
except Exception as error:errors.append({'error':str(error)})
finally:
    result={'status':'PASS' if not errors else 'FAILED_EVIDENCE_COLLECTION','syntheticOnly':True,'files':records,'errors':errors,'testPassed':not args.failed}
    (out/'manifest.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result))
sys.exit(2 if errors else 0)
