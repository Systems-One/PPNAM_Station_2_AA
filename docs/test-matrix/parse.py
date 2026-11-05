import re, json, sys
src, out = sys.argv[1], sys.argv[2]
t = open(src, encoding='utf-8').read()
parts = re.split(r'^## \*\*Job card (\d{9})\*\*', t, flags=re.M)
jobs = []
for i in range(1, len(parts), 2):
    jc, body = parts[i], parts[i+1].split('\n# ')[0]
    lines = [l for l in body.split('\n') if l.strip()]
    desc = lines[0].strip('* ')
    cat = re.search(r'General catalogue: (\w+)\. Detached cached preview: ([\w ]+?)(?: from saved evidence)?\.', body)
    mixes = re.search(r'Required mixes from the saved check: (\d+)', body)
    reason = re.search(r'(?:Recorded catalogue|Saved check) reason: (.*)', body); freason = re.search(r'Full-order reason: (.*)', body)
    outp = re.search(r'Saved outstanding output: (.*?)\. Full-order reference: (.*?)\.', body)
    rows = []
    for l in body.split('\n'):
        if l.startswith('| ') and not l.startswith('| **') and not l.startswith('| ---'):
            c = [x.strip().replace('\_', '_') for x in l.strip('|').split('|')]
            m = re.search(r'(\d{5,10})$', c[0]); code = m.group(1) if m else ''
            rows.append(dict(material=c[0][:len(c[0])-len(code)].strip(), code=code, tag=c[1], perMix=c[2], fullOrder=c[3]))
    jobs.append(dict(jc=jc, desc=desc, catalogue=cat.group(1), preview=cat.group(2), mixes=int(mixes.group(1)) if mixes else None,
        reason=(reason.group(1).replace(f'JC {jc}: ','') if reason else ''), output=outp.group(1) if outp else '', fullOrderReason=freason.group(1) if freason else '', orderRef=outp.group(2) if outp else '', rows=rows,
        notes=' '.join(l for l in lines if 'Source lines' in l or 'unavailable' in l.lower() and 'Recipe' not in l)[:400]))
json.dump(jobs, open(out,'w',encoding='utf-8'), indent=1)
from collections import Counter
print(len(jobs))
print(Counter((j['catalogue'], j['preview']) for j in jobs))
print('cond', [j['jc'] for j in jobs if any('If included' in r['perMix'] for r in j['rows'])])
print('notInGen', sum(1 for j in jobs if any('Not in General' in r['perMix'] for r in j['rows'])))
print('noKg', sum(1 for j in jobs if any(r['tag']=='No kg tag' for r in j['rows'])))
print('fraction-per-mix', sum(1 for j in jobs if any(re.match(r'[\d.]+ kg', r['perMix']) and float(r['perMix'].split()[0])%25 for r in j['rows'])))
print('mix distribution', sorted(Counter(j['mixes'] for j in jobs if j['mixes']).items()))
for j in jobs:
    if j['catalogue']=='Available' and j['preview']!='Qualified': print('AV-UNQ', j['jc'], j['reason'][:150], '|', j['orderRef'])
print(Counter(r['perMix'] for j in jobs for r in j['rows'] if not re.match(r'[\d.]+ kg$', r['perMix'])))
print(Counter(j['orderRef'] for j in jobs))
