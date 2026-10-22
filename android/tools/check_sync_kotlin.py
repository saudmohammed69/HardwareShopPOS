"""Compile changed non-UI Kotlin against cached Android APIs and existing app classes.
This is a source/API check, NOT an APK build or a Room/Hilt annotation-processor run.
"""
import argparse
import os
from pathlib import Path
import subprocess

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--java',required=True)
parser.add_argument('--gradle-cache',required=True)
parser.add_argument('--existing-project',required=True)
parser.add_argument('--android-jar',required=True)
parser.add_argument('--include-tests',action='store_true')
args=parser.parse_args()
cache=Path(args.gradle_cache)
modules=cache/'caches/modules-2/files-2.1'
def jar(path,name):
    return next((modules/path).rglob(name))
compiler=[
    jar('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.20','kotlin-compiler-embeddable-2.0.20.jar'),
    jar('org.jetbrains.kotlin/kotlin-stdlib/2.0.20','kotlin-stdlib-2.0.20.jar'),
    jar('org.jetbrains.kotlin/kotlin-script-runtime/2.0.20','kotlin-script-runtime-2.0.20.jar'),
    jar('org.jetbrains.kotlin/kotlin-reflect','kotlin-reflect-1.6.10.jar'),
    jar('org.jetbrains.intellij.deps/trove4j','trove4j-1.0.20200330.jar'),
    jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm','kotlinx-coroutines-core-jvm-1.8.1.jar'),
    jar('org.jetbrains/annotations','annotations-13.0.jar')]
api={}
for path in (cache/'caches/transforms-4').rglob('*-api.jar'):
    key=path.name.replace('jetified-','')
    api.setdefault(key,path)
support=[jar('javax.inject/javax.inject','javax.inject-1.jar'),
    root/'build/upi-check/zxing.jar',
    jar('androidx.annotation/annotation-jvm/1.8.1','annotation-jvm-1.8.1.jar'),
    jar('androidx.room/room-common/2.6.1','room-common-2.6.1.jar'),
    jar('androidx.hilt/hilt-common/1.2.0','hilt-common-1.2.0.jar'),
    jar('com.google.dagger/dagger/2.52','dagger-2.52.jar'),
    jar('org.jetbrains.kotlinx/kotlinx-coroutines-play-services','kotlinx-coroutines-play-services-1.8.1.jar'),
    jar('com.google.code.gson/gson','gson-2.11.0.jar')]
if args.include_tests:
    support += [jar('junit/junit/4.13.2','junit-4.13.2.jar'),
                jar('org.hamcrest/hamcrest-core','hamcrest-core-1.3.jar')]
classes=Path(args.existing_project)/'app/build/tmp/kotlin-classes/debug'
assert classes.is_dir(), 'Previously compiled app classes are needed for this limited check'
classpath=os.pathsep.join(str(p) for p in [*compiler,*support,*api.values(),classes,Path(args.android_jar)])
base=root/'app/src/main/java/com/hardwareshop/pos'
sources=list((base/'data/sync').glob('*.kt'))+[
    base/'data/local/database/AppDatabase.kt',
    base/'data/local/database/migrations/Migration5To6.kt',
    base/'data/local/database/migrations/Migration6To7.kt',
    base/'data/local/database/migrations/Migration7To8.kt',
    base/'data/local/entity/SyncEntities.kt',base/'data/local/dao/SyncDao.kt',
    base/'data/remote/CloudSyncRepository.kt',base/'service/CloudSyncWorker.kt',base/'service/CloudSyncScheduler.kt']
sources += [base/'data/repository/Repositories.kt']
sources += [base/'util/BackupManager.kt']
sources += [base/'util/UpiPaymentQr.kt',base/'util/ReceiptBuilder.kt',base/'util/PrinterTransport.kt',base/'data/local/dao/Daos.kt']
if args.include_tests:
    sources += list((root/'app/src/androidTest/java/com/hardwareshop/pos/data/sync').glob('*.kt'))
    sources += list((root/'app/src/test/java/com/hardwareshop/pos/data/sync').glob('*.kt'))
out=root/'build/sync-kotlin-check'
out.mkdir(parents=True,exist_ok=True)
# Kotlin argfiles accept quoted paths with forward slashes on Windows.
def quoted(value):
    return '"'+str(value).replace('\\','/').replace('"','\\"')+'"'
argfile=out/'compiler.args'
argfile.write_text('\n'.join(['-no-stdlib','-no-reflect','-jvm-target','17','-classpath',quoted(classpath),'-d',quoted(out),
                            *(quoted(p) for p in sources)]),encoding='utf-8')
subprocess.run([args.java,'-cp',os.pathsep.join(str(p) for p in compiler),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],check=True)
print(f'Kotlin source/API check passed for {len(sources)} files. APK/Room/Hilt validation is still required.')
