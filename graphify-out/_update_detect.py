import json
from pathlib import Path
from graphify.detect import detect_incremental

result = detect_incremental(Path('app/src/main/java'))
Path('graphify-out/.graphify_incremental.json').write_text(
    json.dumps(result, default=str), encoding='utf-8'
)

new_total = result.get('new_total', 0)
deleted = list(result.get('deleted_files', []))
print(f"Changed files: {new_total}")
if deleted:
    print(f"Deleted files: {len(deleted)}")
new_files = result.get('new_files', {})
for cat, files in new_files.items():
    if files:
        print(f"  {cat}:")
        for f in files:
            print(f"    {f}")
