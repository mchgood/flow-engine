#!/usr/bin/env python3
"""检查聚合覆盖率；缺失报告、缺失模块或低于门槛时使 CI 失败。"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

report = Path(__file__).resolve().parents[1] / "flow-engine-coverage/target/site/jacoco-aggregate/jacoco.xml"
if not report.is_file():
    sys.exit("Missing coverage report: run mvn verify first")
root = ET.parse(report).getroot()
expected = {"flow-engine-core", "flow-engine-spring", "flow-engine-spring-boot-starter"}
actual = {group.attrib["name"] for group in root.findall("group")}
if not expected.issubset(actual):
    sys.exit(f"Missing coverage modules: {sorted(expected - actual)}")
failed = False
for metric, minimum in (("LINE", 0.95), ("BRANCH", 0.88)):
    counter = root.find(f"counter[@type='{metric}']")
    if counter is None:
        sys.exit(f"Missing counter: {metric}")
    covered, missed = int(counter.attrib["covered"]), int(counter.attrib["missed"])
    total = covered + missed
    ratio = covered / total if total else 0
    print(f"{metric}: {covered}/{total} = {ratio:.2%}; minimum {minimum:.0%}")
    failed |= ratio < minimum
if failed:
    sys.exit("Coverage is below the regression threshold")
