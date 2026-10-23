"""Compare pure Kotlin encoders with TSV emitted by generate_oracle.mjs.
Usage: python verify_oracle.py --compiler-home PATH/kotlinc --java-home PATH/JDK --vectors FILE.tsv
"""
from pathlib import Path
import argparse
import os
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--compiler-home", type=Path, required=True)
parser.add_argument("--java-home", type=Path, required=True)
parser.add_argument("--vectors", type=Path, required=True)
arguments = parser.parse_args()
tests = Path(__file__).resolve().parents[1]
repo = next(path for path in tests.parents if (path / "settings.gradle.kts").exists())
main = repo / "shared/src/commonMain/kotlin/io/github/betterzslf/feature/water"
build = tests / "build/oracle"
build.mkdir(parents=True, exist_ok=True)
runner = build / "WaterOracleCheck.kt"
runner.write_text(r'''package io.github.betterzslf.feature.water
import java.io.File
private fun decode(value: String): ByteArray = ByteArray(value.length / 2) {
    value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
}
fun main(args: Array<String>) {
    var count = 0
    File(args[0]).useLines { lines -> lines.forEach { line ->
        val parts = line.split('\t')
        val actual = when(parts[0]) {
            "K" -> WaterProtocol.makeKey(decode(parts[1]))
            "A" -> WaterProtocol.makeUnlockResponse(decode(parts[1]), parts[2])
            "S" -> WaterProtocol.makeStartEpilogue(parts[1], parts[2] == "1",
                WaterEpilogueContext(parts[3].toInt(), WaterDateTime(2013, 1, 11, 12, 34, 56)))
            else -> error("Unknown oracle vector kind")
        }
        check(actual.contentEquals(decode(parts.last()))) { "Oracle mismatch #$count: $line" }
        count++
    } }
    println("Original WAT/TypeScript vs Kotlin differential vectors passed: $count")
}
''', encoding="utf-8")
lib = arguments.compiler_home.resolve() / "lib"
java = arguments.java_home.resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
output = build / "water-oracle.jar"
command = [str(java), "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-kotlin-home", str(arguments.compiler_home.resolve()), "-jvm-target", "17", "-include-runtime", "-d", str(output)]
command += [str(file) for file in sorted(main.glob("*.kt")) if not file.name.startswith("WaterBle")] + [str(runner)]
try:
    subprocess.run(command, check=True)
finally:
    runner.unlink(missing_ok=True)
subprocess.run([str(java), "-cp", str(output), "io.github.betterzslf.feature.water.WaterOracleCheckKt",
    str(arguments.vectors.resolve())], check=True)
