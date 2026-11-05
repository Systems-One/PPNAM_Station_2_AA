import json, re, sys
from collections import Counter, defaultdict
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.worksheet.datavalidation import DataValidation
from openpyxl.formatting.rule import FormulaRule
from openpyxl.utils import get_column_letter
sys.path.insert(0, sys.argv[1])
from cases import C

jobs = json.load(open(sys.argv[2], encoding='utf-8'))
guide = open(sys.argv[3], encoding='utf-8').read()
out = sys.argv[4]

F = 'Arial'
H = PatternFill('solid', fgColor='1F3864'); HF = Font(name=F, bold=True, color='FFFFFF', size=10)
IN = PatternFill('solid', fgColor='FFF2CC')
BAND = PatternFill('solid', fgColor='D9E1F2')
thin = Side(style='thin', color='BFBFBF'); BOX = Border(left=thin, right=thin, top=thin, bottom=thin)
BODY = Font(name=F, size=9); WRAP = Alignment(wrap_text=True, vertical='top')
RESULTS = '"Not run,Pass,Fail,Blocked,N/A"'

def table(ws, headers, rows, widths, inputs=(), result_col=None, freeze='B2'):
    for i, h in enumerate(headers, 1):
        cell = ws.cell(1, i, h); cell.fill = H; cell.font = HF; cell.alignment = Alignment(wrap_text=True, vertical='center'); cell.border = BOX
    for r, row in enumerate(rows, 2):
        for i, v in enumerate(row, 1):
            cell = ws.cell(r, i, v); cell.font = BODY; cell.alignment = WRAP; cell.border = BOX
            if headers[i-1] in inputs: cell.fill = IN
    for i, w in enumerate(widths, 1): ws.column_dimensions[get_column_letter(i)].width = w
    ws.freeze_panes = freeze; ws.auto_filter.ref = f"A1:{get_column_letter(len(headers))}{len(rows)+1}"
    ws.row_dimensions[1].height = 30
    if result_col:
        col = get_column_letter(headers.index(result_col) + 1); rng = f"{col}2:{col}{len(rows)+1}"
        dv = DataValidation(type='list', formula1=RESULTS, allow_blank=True); ws.add_data_validation(dv); dv.add(rng)
        for word, color in (('Pass', 'C6EFCE'), ('Fail', 'FFC7CE'), ('Blocked', 'FFEB9C')):
            ws.conditional_formatting.add(rng, FormulaRule(formula=[f'{col}2="{word}"'], fill=PatternFill('solid', fgColor=color)))

wb = Workbook()

# ---------- Test Matrix ----------
counters = Counter(); ids = []; matrix_rows = []
for area, gref, prio, title, pre, data, steps, exp, ref in C:
    p = area.split()[0]; counters[p] += 1; tid = f"{p}-{counters[p]:02d}"; ids.append(tid)
    matrix_rows.append([tid, area, gref, prio, title, pre, data, steps, exp, ref, 'Not run', '', '', '', '', '', '', '', '', '', ''])
MH = ['Test ID', 'Area', 'Guide ref', 'Priority', 'Title', 'Preconditions', 'Test data', 'Steps', 'Expected result', 'App code ref',
      'Result', 'Tester', 'Date', 'Device ID', 'Operator', 'JC / PREP / RAJ session', 'Message ID', 'Request SHA-256', 'Stock before → after (kg)', 'Reply / error code', 'Notes & evidence']
ws = wb.active; ws.title = 'Test Matrix'
table(ws, MH, matrix_rows, [9, 16, 12, 7, 34, 22, 28, 44, 48, 22, 10, 10, 10, 14, 12, 18, 18, 18, 14, 16, 30],
      inputs=MH[10:], result_col='Result', freeze='F2')
NM = len(matrix_rows) + 1

# ---------- Job Coverage ----------
def tid_for(sub):
    return next(ids[i] for i, x in enumerate(C) if sub in x[3])
CLS = {
 'cond':  ('General - conditional choice', 'Lookup loads; Prepare requires Include/Exclude for the conditional line before it enables.', ['Conditional PW60', 'Look up a qualified', 'Create a 1-mix']),
 'excl':  ('General - order exclusions', 'Lookup loads; off-recipe order lines are not collectable without an accepted, reasoned Add in this PREP.', ['Order-excluded lines shown', 'Order-excluded line refused', 'Reasoned Add']),
 'gen':   ('General - qualified', 'General: lookup loads with the listed per-mix kg; Prepare offers 1..availableToPrepareMixes. Rajoo: full-order targets.', ['Look up a qualified', 'Create a 1-mix', 'Capture one full bag']),
 'nomix': ('Refusal - no whole mix count', 'Prepare unavailable or refused with a readable reason; fresh server response decides.', ['Available but no whole mix']),
 'unav':  ('Refusal/recovery - catalogue unavailable', 'Fresh lookup decides. If still refused on General, the reason is shown; no kg target is invented. Can also be tried on Rajoo against the full order (non-kg lines never collected).', ['Unavailable: Each', 'Unavailable: recipe ingredient']),
}
jrows = []
for j in jobs:
    kg = [r for r in j['rows'] if r['perMix'].endswith('kg') or 'If included' in r['perMix']]
    ex = [r for r in j['rows'] if 'Not in General' in r['perMix']]
    nk = [r for r in j['rows'] if r['tag'] == 'No kg tag']
    cond = [r for r in j['rows'] if 'If included' in r['perMix']]
    if j['catalogue'] == 'Unavailable': k = 'unav'
    elif j['preview'] != 'Qualified': k = 'nomix'
    elif cond: k = 'cond'
    elif ex: k = 'excl'
    else: k = 'gen'
    name, exp, subs = CLS[k]
    flags = []
    if nk and k not in ('nokg',): flags.append('non-kg line')
    if ex and k != 'excl': flags.append('order-excluded line')
    if j.get('fullOrderReason'): flags.append('full-order: ' + j['fullOrderReason'])
    tags = ', '.join(dict.fromkeys(r['tag'] for r in j['rows'] if r['tag'].startswith('dummytag')))
    per = '; '.join(f"{r['tag']} {r['perMix']}" for r in kg) or '-'
    jrows.append([j['jc'], j['desc'], j['catalogue'], j['preview'], j['mixes'] if j['mixes'] else '-', j['output'], len(kg), len(ex), len(nk),
                  per, tags, j['reason'] or '-', name, exp + (' Also: ' + '; '.join(flags) if flags else ''), ', '.join(tid_for(s) for s in subs),
                  'Not run', '', '', '', ''])
JH = ['JC', 'Product', 'Catalogue', 'Cached preview', 'Saved mixes', 'Outstanding output', 'kg lines', 'Order-excluded lines', 'Non-kg lines',
      'General per mix (tag kg)', 'Tags', 'Recorded reason', 'Test class', 'Expected on scanner', 'Related tests',
      'Result', 'Lookup outcome seen', 'Mixes available (live)', 'PREP id', 'Notes']
wj = wb.create_sheet('Job Coverage')
table(wj, JH, jrows, [11, 34, 11, 11, 8, 16, 6, 8, 7, 40, 28, 36, 22, 42, 18, 10, 24, 10, 16, 24], inputs=JH[15:], result_col='Result', freeze='C2')
NJ = len(jrows) + 1

# ---------- Stock Register ----------
reg = re.findall(r'^\| (.+?) (\d{10}) \| dummytag\\_(\d+) \| ([\d,\.]+) \| ([\d\.]+) \|', guide, flags=re.M)
uses = Counter(); qual = Counter()
for j in jobs:
    for t in set(r['tag'] for r in j['rows']):
        uses[t] += 1
        if j['preview'] == 'Qualified': qual[t] += 1
srows = [[f"dummytag_{n}", m.strip(), code, float(kg.replace(',', '')), float(b), uses[f'dummytag_{n}'], qual[f'dummytag_{n}'], 'Not run', '', '', ''] for m, code, n, kg, b in reg]
SH = ['Tag', 'Material', 'Code', 'Stock at setup (kg)', 'Bag (kg)', 'Jobs listing tag', 'Qualified jobs listing tag', 'Result', 'Identify shows code/name/bag', 'Balance seen (kg)', 'Notes']
wsr = wb.create_sheet('Stock Register')
table(wsr, SH, srows, [12, 50, 12, 12, 8, 10, 10, 10, 22, 14, 24], inputs=SH[7:], result_col='Result', freeze='B2')
for r in range(2, len(srows) + 2):
    wsr.cell(r, 4).number_format = '#,##0.000'; wsr.cell(r, 5).number_format = '0.000'
n = len(srows) + 2
wsr.cell(n, 3, 'Total').font = Font(name=F, bold=True, size=9)
wsr.cell(n, 4, f'=SUM(D2:D{n-1})').number_format = '#,##0.000'; wsr.cell(n, 4).font = Font(name=F, bold=True, size=9)
wsr.cell(n + 1, 2, 'Source: guide "Dummy stock register", stock verified 27 Oct 2026 15:22:12 SAST. Balances fall as scans consume them.').font = Font(name=F, italic=True, size=8)

# ---------- Guide Traceability ----------
gt_full = re.findall(r'^\| (T\d\d) (.+?) \| (.+?) \|$', guide, flags=re.M)
seq = re.findall(r'^(\d+)\. (.+)$', guide.split('# **Topics and transport**')[0].split('# **Scanner test sequence**')[1], flags=re.M)
def covering(token):
    pat = re.compile(r'(^|[;, ])' + re.escape(token) + r'($|[;, ])')
    out = []
    for i, x in enumerate(C):
        for part in re.split(r';\s*', x[1]):
            if part.startswith('Seq ') and token.startswith('Seq '):
                nums = part[4:].replace(' ', '')
                rng = []
                for chunk in nums.split(','):
                    a, _, b = chunk.partition('-'); rng += list(range(int(a), int(b or a) + 1))
                if int(token[4:]) in rng: out.append(ids[i]); break
            elif part == token: out.append(ids[i]); break
    return out
trows = []
for t, title, exp in gt_full:
    cov = covering(t); trows.append([t, 'Test case', title.replace('\\_', '_'), exp.replace('\\_', '_'), len(cov), ', '.join(cov)])
for num, text in seq:
    cov = covering(f'Seq {num}'); trows.append([f'Seq {num}', 'Test sequence step', re.sub(r'\\_', '_', text)[:300], '-', len(cov), ', '.join(cov)])
for sec in ('Read first', 'Topics', 'Retries', 'Errors', 'Register', 'Job sweep'):
    cov = covering(sec); trows.append([sec, 'Guide section', f'Rules in the guide section "{sec}"', '-', len(cov), ', '.join(cov)])
TH = ['Guide item', 'Type', 'Guide text', 'Guide expected result', 'Matrix tests', 'Covered by']
wt = wb.create_sheet('Guide Traceability')
table(wt, TH, trows, [11, 16, 60, 50, 8, 50])
for r in range(2, len(trows) + 2):
    wt.cell(r, 5).value = f'=IF(F{r}="",0,LEN(F{r})-LEN(SUBSTITUTE(F{r},",",""))+1)'
    wt.conditional_formatting.add(f'E{r}', FormulaRule(formula=[f'E{r}=0'], fill=PatternFill('solid', fgColor='FFC7CE')))

# ---------- Error Codes ----------
errs = re.findall(r'^\| ([a-z_0-9]+(?: or [a-z_0-9]+)?) \| (.+?) \|$', guide.replace('\\_', '_'), flags=re.M)
erows = []
for code, action in errs:
    codes = code.split(' or ')
    cov = [ids[i] for i, x in enumerate(C) if any(cd in ' '.join(x[3:9]) for cd in codes)]
    erows.append([code, action, len(cov), ', '.join(cov)])
for code in ['badge_rejected', 'authentication_failed', 'client_upgrade_required', 'pallet_not_found']:
    if not any(code in e[0] for e in erows):
        cov = [ids[i] for i, x in enumerate(C) if code in ' '.join(x[3:9])]
        erows.append([code, 'App-specific handling (not in the guide error table)', len(cov), ', '.join(cov)])
EH = ['Error code', 'Developer action (guide)', 'Matrix tests', 'Covered by']
we = wb.create_sheet('Error Codes')
table(we, EH, erows, [36, 70, 8, 40])
for r in range(2, len(erows) + 2):
    we.cell(r, 3).value = f'=IF(D{r}="",0,LEN(D{r})-LEN(SUBSTITUTE(D{r},",",""))+1)'
    we.conditional_formatting.add(f'C{r}', FormulaRule(formula=[f'C{r}=0'], fill=PatternFill('solid', fgColor='FFC7CE')))

# ---------- Summary ----------
wsum = wb.create_sheet('Summary', 0)
wsum.column_dimensions['A'].width = 34
for c_ in 'BCDEFGH': wsum.column_dimensions[c_].width = 11
wsum['A1'] = 'Station 2 Android scanner — test matrix'; wsum['A1'].font = Font(name=F, bold=True, size=14)
lines = [
 'Built 28 Oct 2026 from "Station2_Android_Scanner_Test_Guide_All_Jobs_20261027.docx" (stock verified 27 Oct 2026 15:22:12 SAST; catalogue snapshot 15:13:42 SAST) and the app source at v1.4.1.',
 'How to use: fill the yellow columns only. Set Result to Not run / Pass / Fail / Blocked / N/A. Record device, operator, JC/PREP/RAJ, message id, request SHA-256, stock change and reply code for each test (guide step 10).',
 'The live server response always wins over the catalogue snapshot: balances, mix counts and qualification can differ from the guide.',
 'Rajoo: any job card can run on Rajoo, against full-order targets. RAJ tests use JC 510023047 and the commissioned Rajoo machine code; use a JC with no other open Rajoo work.',
 'SAP posting is enabled on the workstation profile. Get agreement before running finish/close tests (ENV-02).',
 'Simulator / crafted-request tests need the backend simulator or sniffer publishing; mark Blocked if unavailable.',
]
for i, l in enumerate(lines, 3):
    wsum.cell(i, 1, l).font = Font(name=F, size=9); wsum.merge_cells(start_row=i, start_column=1, end_row=i, end_column=8)
    wsum.cell(i, 1).alignment = Alignment(wrap_text=True, vertical='top'); wsum.row_dimensions[i].height = 26
r0 = len(lines) + 4
hdr = ['Area', 'Tests', 'P1', 'Pass', 'Fail', 'Blocked', 'N/A', 'Not run']
for i, h in enumerate(hdr, 1):
    cell = wsum.cell(r0, i, h); cell.fill = H; cell.font = HF; cell.border = BOX
areas = list(dict.fromkeys(x[0] for x in C))
M = "'Test Matrix'"
for k, a in enumerate(areas, r0 + 1):
    wsum.cell(k, 1, a)
    wsum.cell(k, 2, f'=COUNTIF({M}!$B$2:$B${NM},$A{k})')
    wsum.cell(k, 3, f'=COUNTIFS({M}!$B$2:$B${NM},$A{k},{M}!$D$2:$D${NM},"P1")')
    for col, word in zip('DEFGH', ['Pass', 'Fail', 'Blocked', 'N/A', 'Not run']):
        wsum[f'{col}{k}'] = f'=COUNTIFS({M}!$B$2:$B${NM},$A{k},{M}!$K$2:$K${NM},"{word}")'
tot = r0 + len(areas) + 1
wsum.cell(tot, 1, 'Total')
for col in 'BCDEFGH': wsum[f'{col}{tot}'] = f'=SUM({col}{r0+1}:{col}{tot-1})'
for rr in range(r0 + 1, tot + 1):
    for cc in range(1, 9):
        cell = wsum.cell(rr, cc); cell.font = Font(name=F, size=9, bold=(rr == tot)); cell.border = BOX
        if rr == tot: cell.fill = BAND
wsum.cell(tot + 1, 1, 'Pass rate of executed tests').font = Font(name=F, size=9)
wsum.cell(tot + 1, 2, f'=IF((D{tot}+E{tot})=0,"-",D{tot}/(D{tot}+E{tot}))').number_format = '0.0%'
r1 = tot + 3
for i, h in enumerate(['Coverage sheet', 'Rows', 'Pass', 'Fail', 'Blocked', 'N/A', 'Not run'], 1):
    cell = wsum.cell(r1, i, h); cell.fill = H; cell.font = HF; cell.border = BOX
for k, (name, sheet, last, col) in enumerate([('Job Coverage (189 job cards)', "'Job Coverage'", NJ, 'P'), ('Stock Register (dummy tags)', "'Stock Register'", len(srows) + 1, 'H')], r1 + 1):
    wsum.cell(k, 1, name); wsum.cell(k, 2, f'=COUNTA({sheet}!A2:A{last})')
    for cc, word in zip('CDEFG', ['Pass', 'Fail', 'Blocked', 'N/A', 'Not run']):
        wsum[f'{cc}{k}'] = f'=COUNTIF({sheet}!{col}2:{col}{last},"{word}")'
    for cc in range(1, 8): wsum.cell(k, cc).font = BODY; wsum.cell(k, cc).border = BOX
r2 = r1 + 4
for i, h in enumerate(['Job test class', 'Jobs'], 1):
    cell = wsum.cell(r2, i, h); cell.fill = H; cell.font = HF; cell.border = BOX
for k, (name, *_ ) in enumerate(CLS.values(), r2 + 1):
    wsum.cell(k, 1, name); wsum.cell(k, 2, f"=COUNTIF('Job Coverage'!M2:M{NJ},A{k})")
    for cc in (1, 2): wsum.cell(k, cc).font = BODY; wsum.cell(k, cc).border = BOX
k += 1; wsum.cell(k, 1, 'Total').font = Font(name=F, bold=True, size=9); wsum.cell(k, 2, f'=SUM(B{r2+1}:B{k-1})').font = Font(name=F, bold=True, size=9)
r3 = k + 2
wsum.cell(r3, 1, 'Legend').font = Font(name=F, bold=True, size=10)
wsum.cell(r3 + 1, 1, 'Yellow cells = tester input').fill = IN
wsum.cell(r3 + 2, 1, 'Red count on Traceability / Error Codes = guide item with no test')
wsum.cell(r3 + 3, 1, 'P1 = must pass for release; P2 = should pass; P3 = cosmetic / defensive')
for rr in range(r3 + 1, r3 + 4): wsum.cell(rr, 1).font = BODY

wb.save(out)
print('cases', len(C), 'jobs', len(jrows), 'tags', len(srows), 'trace', len(trows), 'errors', len(erows))
print([ (t[0], t[4]) for t in trows if t[4] == 0], [(e[0], e[2]) for e in erows if e[2] == 0])
