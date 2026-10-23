"""Run only the offline water tests with a standalone official Kotlin compiler.

Usage: python verify_kotlin.py --compiler-home PATH/kotlinc --java-home PATH/JDK
The temporary JVM copies remove only kotlin.test.Test annotations; assertions and
all test method bodies are unchanged. No project Gradle task is invoked.
"""
from pathlib import Path
import argparse
import os
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--compiler-home", type=Path, required=True)
parser.add_argument("--java-home", type=Path, required=True)
arguments = parser.parse_args()
tests = Path(__file__).resolve().parents[1]
repo = next(path for path in tests.parents if (path / "settings.gradle.kts").exists())
main = repo / "shared/src/commonMain/kotlin/io/github/betterzslf/feature/water"
build = tests / "build/standalone"
build.mkdir(parents=True, exist_ok=True)
methods = []
patched = []
for test in sorted(tests.glob("*Test.kt")):
    if test.name.startswith("WaterBle"):
        continue  # Adapter tests use Kable/coroutines; this runner is pure protocol only.
    source = test.read_text(encoding="utf-8")
    name = re.search(r"class\s+(\w+Test)\s*\{", source).group(1)
    methods.extend((name, method) for method in re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source))
    source = source.replace("import kotlin.test.Test\n", "").replace("    @Test\n", "")
    target = build / test.name
    target.write_text(source, encoding="utf-8")
    patched.append(str(target))
runner = build / "WaterOfflineCheck.kt"
runner.write_text("package io.github.betterzslf.feature.water\n\nfun main() {\n" +
    "\n".join('    %s().%s(); println("PASS %s.%s")' % (cls, method, cls, method)
              for cls, method in methods) +
    '\n    println("Offline water tests passed: %d")\n}\n' % len(methods), encoding="utf-8")
lib = arguments.compiler_home.resolve() / "lib"
java = arguments.java_home.resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
output = build / "water-tests.jar"
command = [str(java), "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-kotlin-home", str(arguments.compiler_home.resolve()), "-classpath", str(lib / "kotlin-test.jar"),
    "-jvm-target", "17", "-include-runtime", "-d", str(output)]
command += [str(file) for file in sorted(main.glob("*.kt")) if not file.name.startswith("WaterBle")] + patched + [str(runner)]
try:
    subprocess.run(command, check=True)
finally:
    # These temporary files sit under a commonTest source root: leave no .kt
    # copies for a later project Gradle test invocation to discover recursively.
    for temporary in patched + [str(runner)]:
        Path(temporary).unlink(missing_ok=True)
subprocess.run([str(java), "-cp", str(output) + os.pathsep + str(lib / "kotlin-test.jar"),
    "io.github.betterzslf.feature.water.WaterOfflineCheckKt"], check=True)
