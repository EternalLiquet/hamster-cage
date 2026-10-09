import sys
name, part = sys.argv[1], int(sys.argv[2])
parts, total = {}, 0
for line in open('proof.txt', encoding='utf-8', errors='ignore'):
    line = line.strip()
    if line.startswith(name + '|'):
        _, i, n, data = line.split('|', 3)
        parts[int(i)] = data
        total = int(n)
blob = ''.join(parts[i] for i in range(total)) if total and len(parts) == total else ''
size = 4000
pieces = [blob[i:i + size] for i in range(0, len(blob), size)]
print(f'{name}: {len(blob)} chars, {len(pieces)} pieces')
if len(pieces) > 16:
    print(f'::error title=TODAYPROOF {name}::too large ({len(pieces)} pieces)')
for k in range(part * 8, min(len(pieces), part * 8 + 8)):
    print(f'::notice title=TODAYPROOF {name} {k}/{len(pieces)}::{pieces[k]}')
