"""Summarize the latest Gradle XML results."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
result = {'tasks': {}}
valid = True
for task in ('test', 'integrationTest'):
    files = list((root / 'build/test-results' / task).glob('TEST-*.xml'))
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    timestamps = []
    for file in files:
        suite = ET.parse(file).getroot()
        for key in counts:
            counts[key] += int(suite.attrib.get(key, 0))
        timestamps.append(suite.attrib.get('timestamp', ''))
    counts['latest_suite_timestamp'] = max(timestamps, default=None)
    result['tasks'][task] = counts
    valid &= bool(files) and counts['tests'] > 0 and all(counts[k] == 0 for k in ('failures', 'errors', 'skipped'))
print(json.dumps(result, ensure_ascii=False, indent=2))
raise SystemExit(0 if valid else 1)
