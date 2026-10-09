import sys
name = sys.argv[1]
parts = {}
total = 0
for line in open('proof.txt', encoding='utf-8', errors='ignore'):
    line = line.strip()
    if not line.startswith(name + '|'):
        continue
    _, i, n, data = line.split('|', 3)
    parts[int(i)] = data
    total = int(n)
blob = ''.join(parts[i] for i in range(total)) if total and len(parts) == total else ''
print(f'{name}: {len(parts)}/{total} chunks, {len(blob)} chars')
size = 60000
pieces = [blob[i:i + size] for i in range(0, len(blob), size)] or ['MISSING']
for k, piece in enumerate(pieces):
    print(f'::notice title=TODAYPROOF {name} {k}/{len(pieces)}::{piece}')
