#!/usr/bin/env python3
"""Fail closed if a public Android build snapshot contains private design assets."""
from pathlib import Path
import sys
root=Path(__file__).resolve().parents[1]
blocked_extensions={'.cdr','.cdt','.pdf','.psd','.ai','.key','.keystore'}
blocked_names={'local.properties','.env','gradle.properties.private'}
violations=[]
for p in root.rglob('*'):
    if not p.is_file() or '.git' in p.parts or 'build' in p.parts:
        continue
    rel=p.relative_to(root)
    if p.suffix.lower() in blocked_extensions or p.name.lower() in blocked_names:
        violations.append(str(rel))
    if rel.parts and rel.parts[0] in {'docs','private','handoff','audit','cdr_probe'}:
        violations.append(str(rel))
if violations:
    print('PUBLIC MIRROR SAFETY FAILURE:',*sorted(set(violations)),sep='\n - ',file=sys.stderr)
    sys.exit(3)
print('Public mirror safety gate passed')
