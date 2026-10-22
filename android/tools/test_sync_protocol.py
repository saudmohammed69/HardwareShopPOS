"""Run pure Kotlin transport contract tests using cached project dependencies."""
import argparse
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--java', required=True)
parser.add_argument('--gradle-cache', required=True)
args = parser.parse_args()
modules = Path(args.gradle_cache) / 'caches/modules-2/files-2.1'
def jar(path, name):
    return next((modules/path).rglob(name))
jars = [
    jar('org.jetbrains.kotlin/kotlin-stdlib/2.0.20','kotlin-stdlib-2.0.20.jar'),
    jar('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.20','kotlin-compiler-embeddable-2.0.20.jar'),
    jar('org.jetbrains.kotlin/kotlin-script-runtime/2.0.20','kotlin-script-runtime-2.0.20.jar'),
    jar('org.jetbrains.kotlin/kotlin-reflect','kotlin-reflect-1.6.10.jar'),
    jar('org.jetbrains.intellij.deps/trove4j','trove4j-1.0.20200330.jar'),
    jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm','kotlinx-coroutines-core-jvm-1.6.4.jar'),
    jar('org.jetbrains/annotations','annotations-13.0.jar'),
    jar('com.google.code.gson/gson','gson-2.11.0.jar'),
    jar('junit/junit','junit-4.13.2.jar'),
    jar('org.hamcrest/hamcrest-core','hamcrest-core-1.3.jar')]
cp = os.pathsep.join(str(x) for x in jars)
out = root/'build/sync-protocol-tests'
out.mkdir(parents=True, exist_ok=True)
source = root/'app/src/main/java/com/hardwareshop/pos/data/sync'
files = [source/'SyncProtocol.kt',source/'SyncWireSchema.kt',source/'SyncWireJson.kt',
         source/'SyncMergePlan.kt',
         source/'SyncFinancialBundle.kt',
         source/'SyncBundleJson.kt',
         root/'tools/ExportSyncWireSchema.kt',
         root/'app/src/test/java/com/hardwareshop/pos/data/sync/SyncProtocolTest.kt',
         root/'app/src/test/java/com/hardwareshop/pos/data/sync/SyncMergePlanTest.kt',
         root/'app/src/test/java/com/hardwareshop/pos/data/sync/SyncFinancialBundleTest.kt']
subprocess.run([args.java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect',
                '-classpath',cp,'-d',str(out),*(str(p) for p in files)],check=True)
subprocess.run([args.java,'-cp',str(out)+os.pathsep+cp,'org.junit.runner.JUnitCore',
                'com.hardwareshop.pos.data.sync.SyncProtocolTest',
                'com.hardwareshop.pos.data.sync.SyncMergePlanTest',
                'com.hardwareshop.pos.data.sync.SyncFinancialBundleTest'],check=True)
schema = subprocess.check_output([args.java,'-cp',str(out)+os.pathsep+cp,'ExportSyncWireSchemaKt'],text=True)
(root/'tools/rules-tests/wire-schema.generated.json').write_text(schema,encoding='utf-8')
