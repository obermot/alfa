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

# A public build must be tied to one exact private commit and file snapshot.
import hashlib
import re
provenance=root/'PUBLIC_MIRROR_PROVENANCE.txt'
if not provenance.is_file():
    sys.exit('PUBLIC MIRROR SAFETY FAILURE: provenance missing')
lines=provenance.read_text().splitlines()
if len(lines)<4 or lines[0]!='source_repository=obermot/Nezabudka':
    sys.exit('PUBLIC MIRROR SAFETY FAILURE: invalid provenance header')
if not re.fullmatch(r'source_sha=[0-9a-f]{40}',lines[1]):
    sys.exit('PUBLIC MIRROR SAFETY FAILURE: invalid source SHA')
entries={}
for line in lines[3:]:
    match=re.fullmatch(r'([0-9a-f]{64})  ([^\\n]+)',line)
    if not match:
        sys.exit('PUBLIC MIRROR SAFETY FAILURE: invalid file digest entry')
    digest, rel=match.groups()
    if rel in entries or rel.startswith('/') or '..' in Path(rel).parts:
        sys.exit('PUBLIC MIRROR SAFETY FAILURE: unsafe or duplicate file path')
    entries[rel]=digest
if lines[2] != f'source_file_count={len(entries)}':
    sys.exit('PUBLIC MIRROR SAFETY FAILURE: incorrect file count')
for rel,digest in entries.items():
    p=root/rel
    if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest()!=digest:
        sys.exit('PUBLIC MIRROR SAFETY FAILURE: source digest mismatch: '+rel)
print('Pinned source SHA and file hashes verified')
