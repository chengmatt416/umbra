#!/usr/bin/env python3
"""Summarise JUnit XML results of every module (run after `./gradlew test`).

Prints one line per module and a total. Exits non-zero if

* any test failed or errored,
* a module that has test sources produced no results (a silent "0 tests" is a failure), or
* a test source contains an expression-bodied `@Test` function that does not return Unit.
  JUnit 5 silently skips non-void test methods, e.g.
      @Test fun x() = runBlocking { ...; assertFailsWith<E> { ... } }   // returns the exception: NEVER RUNS
  Declare them `fun x(): Unit = runBlocking { ... }` (runTest is fine as written).
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def up(path, levels):
    for _ in range(levels):
        path = os.path.dirname(path)
    return path


# <module>/build/test-results/<task>/<Class>.xml -> <module>
modules = sorted({up(p, 4)
                  for p in glob.glob(os.path.join(root, "**", "build", "test-results", "*", "*.xml"), recursive=True)})

total = failed = errors = skipped = 0
bad = False
for module in modules:
    t = f = e = s = 0
    for xml in glob.glob(os.path.join(module, "build", "test-results", "*", "*.xml")):
        r = ET.parse(xml).getroot()
        t += int(r.get("tests", 0)); f += int(r.get("failures", 0))
        e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
    name = os.path.relpath(module, root)
    flag = "" if (f == 0 and e == 0) else "  <-- FAILING"
    print(f"{name:34s} tests={t:4d} failures={f} errors={e} skipped={s}{flag}")
    total += t; failed += f; errors += e; skipped += s
    bad |= (f > 0 or e > 0)

# Modules with test sources but no results.
for src in glob.glob(os.path.join(root, "**", "src", "test", "kotlin"), recursive=True):
    module = up(src, 3)  # <module>/src/test/kotlin -> <module>
    if "build-logic" in module or "/build/" in module:
        continue
    if module not in modules:
        print(f"{os.path.relpath(module, root):34s} HAS TEST SOURCES BUT NO RESULTS (not run?)")
        bad = True

# Expression-bodied @Test functions that are not Unit-returning get skipped silently by JUnit.
EXPR_TEST = re.compile(r"@Test\s+(?:@\w+(?:\([^)]*\))?\s+)*fun\s+(\w+)\s*\(\)\s*=\s*(\w+)(<Unit>)?")
for path in glob.glob(os.path.join(root, "**", "src", "*", "kotlin", "**", "*.kt"), recursive=True):
    if "/src/test/" not in path and "/src/androidTest/" not in path:
        continue
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    for m in EXPR_TEST.finditer(text):
        fn, builder = m.group(1), m.group(2)
        if builder == "runTest" or m.group(3):
            continue  # runTest returns Unit on the JVM; runBlocking<Unit> is explicit
        print(f"SILENTLY SKIPPED BY JUNIT: {os.path.relpath(path, root)}: {fn}() = {builder}{{...}} must declare ': Unit'")
        bad = True

print(f"{'TOTAL':34s} tests={total:4d} failures={failed} errors={errors} skipped={skipped}")
sys.exit(1 if bad else 0)
