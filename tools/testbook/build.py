"""Aimbase 통합시험 절차서(엑셀) 생성.

items/items_{영역}.py 가 정본이고 엑셀은 출력물이다. 엑셀을 직접 고치지 않는다.
발주사가 준 서식이 없어 시트를 처음부터 만든다.

시험 순위와 항목 읽기는 joint-test 플러그인 lib 를 쓴다. 합동시험 콘솔과 같은 순위를 보게
하려는 것이다. lib 위치는 TESTBOOK_LIB 환경변수가 이긴다.

실행: python3 tools/testbook/build.py            출력 단위 전부
      python3 tools/testbook/build.py all        하나만
"""
import os
import sys
from collections import Counter
from datetime import datetime
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
LIB = os.environ.get('TESTBOOK_LIB') or os.path.expanduser(
    '~/Documents/GitHub/ai-lifecycle/ai-sdlc/plugins/ai-sdlc-team/lib')
if LIB not in sys.path:
    sys.path.insert(0, LIB)

from openpyxl import Workbook  # noqa: E402
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side  # noqa: E402
from openpyxl.utils import get_column_letter  # noqa: E402
from openpyxl.worksheet.datavalidation import DataValidation  # noqa: E402

OUT_DIR = os.path.join(ROOT, 'docs', '테스트')
VERSION = 'v1.0'
KST = ZoneInfo('Asia/Seoul')

# 출력 단위. 영역이 하나뿐이라 전체 하나로 낸다.
PARTS = {
    'all': ('', ('01', '02')),
}

RANK_LABEL = {0: '0순위', 1: '1순위', 2: '2순위', 3: '3순위'}
RESULTS = ('OK', 'NG', 'N/T', 'N/A')

# (머리글, 행에서 꺼낼 값, 너비)
COLS = [
    ('No', None, 6),
    ('TEST ID', 'tid', 14),
    ('영역', 's1', 14),
    ('주요 흐름', 's2', 16),
    ('구분', 'cls', 7),
    ('시험항목', 's3', 30),
    ('사전조건', 'pre', 26),
    ('시험절차', 'tc', 48),
    ('기대결과', 'exp', 52),
    ('요구사항 ID', 'req', 16),
    ('화면', 'screen', 18),
    ('시험 순위', 'rank', 9),
    ('Priority', 'pri', 9),
    ('결과', 'result', 8),
    ('시험일', None, 11),
    ('시험자', None, 9),
    ('비고', 'comment', 30),
]

THIN = Side(style='thin', color='999999')
BOX = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)
HEAD_FILL = PatternFill('solid', fgColor='D9E1F2')
HEAD_FONT = Font(name='맑은 고딕', bold=True, size=10)
BODY_FONT = Font(name='맑은 고딕', size=10)
WRAP = Alignment(wrap_text=True, vertical='top')
CENTER = Alignment(horizontal='center', vertical='top', wrap_text=True)


def out_path(part, day):
    tag = f'_{PARTS[part][0]}' if PARTS[part][0] else ''
    return os.path.join(OUT_DIR, f'Aimbase_통합시험절차서{tag}_{day}_{VERSION}.xlsx')


def head(ws, names, widths):
    for c, (name, width) in enumerate(zip(names, widths), 1):
        cell = ws.cell(row=1, column=c, value=name)
        cell.font, cell.fill, cell.border, cell.alignment = HEAD_FONT, HEAD_FILL, BOX, CENTER
        ws.column_dimensions[get_column_letter(c)].width = width
    ws.freeze_panes = 'C2'


def build_procedure(wb, rows, judged):
    ws = wb.create_sheet('시험절차')
    head(ws, [c[0] for c in COLS], [c[2] for c in COLS])
    centered = {'No', 'TEST ID', '구분', '시험 순위', 'Priority', '결과', '시험일', '시험자'}
    for n, r in enumerate(rows, 1):
        for c, (name, key, _) in enumerate(COLS, 1):
            if name == 'No':
                value = n
            elif key == 'rank':
                value = RANK_LABEL.get(r['rank'], '')
            elif key == 'result':
                # 판정 기록이 없는 항목은 칸을 비워 시험자가 적게 한다
                value = r['result'] if r['na'] or r['tid'] in judged else ''
            else:
                value = r.get(key, '') if key else ''
            cell = ws.cell(row=n + 1, column=c, value=value)
            cell.font, cell.border = BODY_FONT, BOX
            cell.alignment = CENTER if name in centered else WRAP
    last = len(rows) + 1
    ws.auto_filter.ref = f'A1:{get_column_letter(len(COLS))}{last}'
    col = get_column_letter([c[0] for c in COLS].index('결과') + 1)
    dv = DataValidation(type='list', formula1='"' + ','.join(RESULTS) + '"', allow_blank=True)
    ws.add_data_validation(dv)
    dv.add(f'{col}2:{col}{max(last, 2)}')


def build_summary(wb, rows, label, day):
    ws = wb.active
    ws.title = '요약'
    ws['A1'] = f'Aimbase 통합시험 절차서 {label}'.rstrip()
    ws['A1'].font = Font(name='맑은 고딕', bold=True, size=14)
    ws['A2'] = f'작성일 {day}  판 {VERSION}  전체 {len(rows)}건'
    ws['A2'].font = BODY_FONT
    names = ['영역', '정상', '예외', '계', '0순위', '1순위', '2순위', '3순위']
    for c, name in enumerate(names, 1):
        cell = ws.cell(row=4, column=c, value=name)
        cell.font, cell.fill, cell.border, cell.alignment = HEAD_FONT, HEAD_FILL, BOX, CENTER
        ws.column_dimensions[get_column_letter(c)].width = 22 if c == 1 else 9
    by_area = {}
    for r in rows:
        by_area.setdefault((r['area'], r['s1']), []).append(r)
    line = 5
    for (code, name), group in sorted(by_area.items()):
        kinds = Counter(r['cls'] for r in group)
        ranks = Counter(r['rank'] for r in group)
        vals = [f'{code} {name}', kinds.get('정상', 0), kinds.get('예외', 0), len(group)]
        vals += [ranks.get(k, 0) for k in range(4)]
        for c, v in enumerate(vals, 1):
            cell = ws.cell(row=line, column=c, value=v)
            cell.font, cell.border = BODY_FONT, BOX
            cell.alignment = WRAP if c == 1 else CENTER
        line += 1
    ws.cell(row=line + 1, column=1,
            value='시험 순위: 0순위 업무 흐름마다 대표 1건, 1순위 기능과 흐름을 한 번씩 덮는 범위, '
                  '2순위 나머지 정상, 3순위 예외').font = BODY_FONT


def build_excluded(wb, excluded):
    ws = wb.create_sheet('제외')
    head(ws, ['TEST ID', '기능 ID', '표식', '사유'], [14, 16, 8, 70])
    for n, (tid, fc, mark, reason) in enumerate(excluded, 2):
        for c, v in enumerate((tid, fc, mark, reason), 1):
            cell = ws.cell(row=n, column=c, value=v)
            cell.font, cell.border, cell.alignment = BODY_FONT, BOX, WRAP


def build(part):
    import testbook_items as ti
    label, codes = PARTS[part]
    rows, excluded = ti.ranked()
    rows = [r for r in rows if r['area'] in codes]
    excluded = [e for e in excluded if e[0].split('_')[-1][:2] in codes]
    rows.sort(key=lambda r: r['tid'])
    day = datetime.now(KST).strftime('%Y%m%d')
    wb = Workbook()
    build_summary(wb, rows, label, day)
    build_procedure(wb, rows, set(ti.load_results()))
    build_excluded(wb, excluded)
    os.makedirs(OUT_DIR, exist_ok=True)
    path = out_path(part, day)
    wb.save(path)
    print(f'{os.path.relpath(path, ROOT)}  {len(rows)}건  제외 {len(excluded)}건')
    return len(rows)


def main():
    parts = sys.argv[1:] or list(PARTS)
    bad = [p for p in parts if p not in PARTS]
    if bad:
        raise SystemExit(f'알 수 없는 출력 단위: {", ".join(bad)} (가능: {", ".join(PARTS)})')
    for part in parts:
        build(part)


if __name__ == '__main__':
    main()
