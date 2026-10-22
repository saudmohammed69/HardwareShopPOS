"""Compile and run the offline QR raster tests using cached Kotlin dependencies."""
from pathlib import Path
import os, subprocess

root=Path(__file__).resolve().parents[1]
cache=Path(os.environ['USERPROFILE'])/'.gradle/caches/modules-2/files-2.1'
def jar(group, name): return next((cache/group).rglob(name))
paths=[jar('org.jetbrains.kotlin/kotlin-compiler-embeddable','kotlin-compiler-embeddable-2.0.20.jar'),
       jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm','kotlinx-coroutines-core-jvm-1.8.1.jar'),
       jar('org.jetbrains.kotlin/kotlin-stdlib','kotlin-stdlib-2.0.20.jar'),
       jar('org.jetbrains.kotlin/kotlin-script-runtime','kotlin-script-runtime-2.0.20.jar'),
       jar('org.jetbrains.kotlin/kotlin-reflect','kotlin-reflect-1.6.10.jar'),
       jar('org.jetbrains.intellij.deps/trove4j','trove4j-1.0.20200330.jar'),
       jar('org.jetbrains/annotations','annotations-13.0.jar'),
       jar('junit/junit','junit-4.13.2.jar'),jar('org.hamcrest/hamcrest-core','hamcrest-core-1.3.jar'),
       root/'build/upi-check/zxing.jar',
       Path(os.environ['USERPROFILE'])/'AppData/Local/Android/Sdk/platforms/android-35/android.jar']
cp=os.pathsep.join(map(str,paths))
java=r'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
out=root/'build/upi-check/classes'
out.mkdir(parents=True,exist_ok=True)
files=[root/'app/src/main/java/com/hardwareshop/pos/util/UpiPaymentQr.kt',
       root/'app/src/test/java/com/hardwareshop/pos/util/UpiPaymentQrTest.kt']
subprocess.run([java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
               '-no-stdlib','-no-reflect','-classpath',cp,'-d',str(out),*map(str,files)],check=True)
subprocess.run([java,'-cp',str(out)+os.pathsep+cp,'org.junit.runner.JUnitCore',
               'com.hardwareshop.pos.util.UpiPaymentQrTest'],check=True)
